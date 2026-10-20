package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Resolves catalog selections locally; pins revisions before creating the existing transfer jobs. */
@Service
public class CatalogCalibreTransfers {
    public record Book(long eplId,double revision,String title) {}
    public record Selection(CatalogBookFilter filters) {}
    public record Prepared(List<CalibreClient.Library> libraries,String library,List<CalibreTransfers.Candidate> files,List<Book> books,List<Book> missing,List<Book> deferred,@com.fasterxml.jackson.annotation.JsonIgnore Map<Long,String> deferredRecords) {}
    public record Request(String id,String library,List<Book> books,List<CalibreTransfers.FileChoice> files,boolean download,List<Long> pending) {}
    private final CatalogBookService catalog;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final DownloadDependencies dependencies;
    private final EpubFiles epub;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final CalibreTransfers transfers;
    private final BulkStore bulk;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.transaction.PlatformTransactionManager manager;
    private final tools.jackson.databind.json.JsonMapper json;
    public CatalogCalibreTransfers(CatalogBookService catalog,DownloadRepository downloads,DownloadTrackingService tracking,
            EpubFiles epub,CalibreSettings settings,CalibreClient client,CalibreTransfers transfers,BulkStore bulk,
            org.springframework.jdbc.core.JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,DownloadDependencies dependencies) {
        this.catalog=catalog;this.downloads=downloads;this.tracking=tracking;this.epub=epub;this.settings=settings;
        this.client=client;this.transfers=transfers;this.bulk=bulk;this.jdbc=jdbc;this.manager=manager;this.dependencies=dependencies;
        this.json=tools.jackson.databind.json.JsonMapper.builder().build();
    }
    private static void require() {
        Permission.require(Permission.CATALOG_READ);Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);
    }
    public Prepared prepare(Selection selection) {
        require();
        if(selection==null || selection.filters()==null) throw new UserInputException("Selecciona libros del catálogo");
        CalibrePreparationContext.update("CATALOG",0,0);
        var books=books(selection.filters());
        if(books.isEmpty()) throw new UserInputException("Selecciona libros del catálogo");
        CalibrePreparationContext.update("TORRENT_STATE",0,books.size());
        if(Permission.has(Permission.TORRENT_SYNC)) dependencies.refresh(books.stream().map(book -> Objects.requireNonNull(book).eplId()).toList());
        CalibrePreparationContext.update("TORRENT_STATE",books.size(),books.size());
        CalibrePreparationContext.update("CALIBRE_CONNECTION",0,books.size());
        var connection=settings.connection();var check=client.check(connection);
        CalibrePreparationContext.update("CALIBRE_CONNECTION",books.size(),books.size());
        var candidates=new ArrayList<CalibreTransfers.Candidate>();var missing=new ArrayList<Book>();var deferred=new ArrayList<Book>();var deferredIds=new HashMap<Long,String>();
        var records=new HashMap<Long,List<DownloadRecord>>();
        for(int offset=0;offset<books.size();offset+=500) {
            var ids=books.subList(offset,Math.min(offset+500,books.size())).stream().map(book -> Objects.requireNonNull(book).eplId()).toList();
            for(var d:downloads.findByEplIdIn(ids)) records.computeIfAbsent(d.getEplId(),ignored->new ArrayList<>()).add(d);
            CalibrePreparationContext.update("DOWNLOAD_RECORDS",Math.min(offset+500,books.size()),books.size());
        }
        String instance=tracking.instanceId();
        int inspected=0;
        CalibrePreparationContext.update("FILES",0,books.size());
        for(var b:books) {
            inspected++;if(inspected%500==0 || inspected==books.size()) CalibrePreparationContext.update("FILES",inspected,books.size());
            var ready=records.getOrDefault(b.eplId(),List.of()).stream().filter(d->Double.compare(d.getRevision(),b.revision())==0 && instance.equals(d.getClientInstanceId()) && d.getStatus()==DownloadStatus.DOWNLOADED).toList();
            if(ready.isEmpty()) {missing.add(b);continue;}
            // Large selections are prepared locally. File discovery belongs to the worker.
            if(books.size()>1000 || CalibrePreparationContext.staged()) {deferred.add(b);deferredIds.put(b.eplId(),ready.getFirst().getId());continue;}
            for(var d:ready) for(var f:epub.list(d.getId())) {
                if(f.size()>268435456) throw new UserInputException("El EPUB supera 256 MiB");
                candidates.add(new CalibreTransfers.Candidate(d.getId(),f.index(),f.name(),f.size(),b.eplId(),b.revision(),f.expectedSize()));
            }
            if(candidates.stream().noneMatch(f->f.eplId()==b.eplId())) missing.add(b);
        }
        return new Prepared(check.libraries(),connection.library(),List.copyOf(candidates),books,List.copyOf(missing),List.copyOf(deferred),Map.copyOf(deferredIds));
    }
    private List<Book> books(CatalogBookFilter filter) {
        var result=new ArrayList<Book>();
        for(int page=0;;page++) {
            var batch=catalog.search(filter,PageRequest.of(page,500,Sort.by("eplId")));
            batch.forEach(b->result.add(new Book(b.getEplId(),b.getRevision(),b.getTitle())));
            CalibrePreparationContext.update("CATALOG",result.size(),batch.getTotalElements());
            if(!batch.hasNext()) break;
        }
        return List.copyOf(result);
    }
    private String signature(Request request) {
        try {return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public CalibreTransfers.Job create(Request request) {return create(request,id->{});}
    CalibreTransfers.Job createStagedBatch(Request request,java.util.function.Consumer<String> stage) {return create(request,stage);}
    private CalibreTransfers.Job create(Request request,java.util.function.Consumer<String> stage) {
        require();
        if(request==null || request.id()==null || !request.id().matches("[a-zA-Z0-9-]{1,100}") || request.books()==null || request.books().isEmpty() || request.books().stream().anyMatch(Objects::isNull) || request.files()==null || request.files().stream().anyMatch(Objects::isNull)) throw new UserInputException("Selección de Calibre inválida");
        if(request.pending()==null || request.pending().stream().anyMatch(Objects::isNull) || new HashSet<>(request.pending()).size()!=request.pending().size() || !new HashSet<>(request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).toList()).containsAll(request.pending())) throw new UserInputException("Selección de descargas pendientes inválida");
        if(new HashSet<>(request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).toList()).size()!=request.books().size() || new HashSet<>(request.files()).size()!=request.files().size()) throw new UserInputException("No selecciones libros o archivos duplicados");
        return transfers.preparing(()-> {
            var existing=existing(request);if(existing!=null) return existing;
            var connection=settings.connection();String instance=tracking.instanceId();
            var filter=new CatalogBookFilter();filter.setSelectedIds(request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).toArray(Long[]::new));
            var prepared=prepare(new Selection(filter));
            if(!prepared.books().equals(request.books())) throw new UserInputException("El catálogo cambió; vuelve a preparar el envío");
            if(prepared.libraries().stream().noneMatch(l->l.id().equals(request.library()))) throw new UserInputException("La biblioteca de destino no está disponible");
            var availableFiles=new HashMap<CalibreTransfers.FileChoice,CalibreTransfers.Candidate>();
            var byBook=new HashMap<Long,List<CalibreTransfers.Candidate>>();
            for(var file:prepared.files()) {availableFiles.put(new CalibreTransfers.FileChoice(file.recordId(),file.index()),file);byBook.computeIfAbsent(file.eplId(),ignored->new ArrayList<>()).add(file);}
            var selected=new ArrayList<CalibreTransfers.Candidate>();
            for(var file:request.files()) {var candidate=availableFiles.get(file);if(candidate==null) throw new UserInputException("El EPUB seleccionado cambió");selected.add(candidate);}
            var selectedBooks=new HashSet<>(selected.stream().map(candidate -> Objects.requireNonNull(candidate).eplId()).toList());
            var pinned=new HashMap<Long,Book>();prepared.books().forEach(book->pinned.put(book.eplId(),book));
            var deferredBooks=new HashSet<>(prepared.deferred().stream().map(book -> Objects.requireNonNull(book).eplId()).toList());
            var deferredRecords=new HashMap<Long,String>();
            var waits=new ArrayList<Book>();
            for(long id:request.pending()) {
                if(!request.download() && !deferredBooks.contains(id)) continue;
                if(selectedBooks.contains(id)) throw new UserInputException("No selecciones el mismo libro como EPUB y descarga pendiente");
                var available=byBook.getOrDefault(id,List.of());
                if(available.size()>1) throw new UserInputException("La descarga ya está disponible con varios EPUB; vuelve a preparar el envío para elegir uno");
                if(available.size()==1) selected.add(available.getFirst());
                else {
                    waits.add(pinned.get(id));
                    if(deferredBooks.contains(id)) {
                        deferredRecords.put(id,prepared.deferredRecords().get(id));
                    }
                }
            }
            if(!deferredRecords.isEmpty()) epub.requireRoots();
            if(selected.isEmpty() && waits.isEmpty()) throw new UserInputException("Selecciona algún EPUB o activa la descarga de los libros no disponibles");
            if(waits.stream().anyMatch(b->!deferredRecords.containsKey(b.eplId()))) {Permission.require(Permission.TORRENT_SEND);Permission.require(Permission.TORRENT_SYNC);epub.requireRoots();}
            return new TransactionTemplate(manager).execute(status->{
                var raced=existing(request);if(raced!=null) return raced;
                if(!connection.equals(settings.connection()) || !instance.equals(tracking.instanceId())) throw new UserInputException("La configuración cambió; vuelve a preparar el envío");
                var current=books(filter);
                if(!current.equals(request.books())) throw new UserInputException("El catálogo cambió; vuelve a preparar el envío");
                var dependencies=new HashMap<Long,String>();var missing=new ArrayList<Long>();
                var needed=waits.stream().filter(book->!deferredRecords.containsKey(book.eplId())).toList();
                var wanted=new HashMap<Long,Double>();needed.forEach(book->wanted.put(book.eplId(),book.revision()));
                dependencies.putAll(this.dependencies.reusable(wanted));
                needed.forEach(book->{if(!dependencies.containsKey(book.eplId())) missing.add(book.eplId());});
                String downloadId=null;
                if(!missing.isEmpty()) {
                    var downloadFilter=new CatalogBookFilter();downloadFilter.setSelectedIds(missing.toArray(Long[]::new));
                    downloadId=bulk.create(downloadFilter,org.springframework.data.domain.Pageable.unpaged(),false,false,new BulkRequest(null,null,null,null,MultipleHashes.FIRST)).jobId();
                }
                var cleanup=new HashMap<Long,CalibreUpdateFlow.Choice>();
                for(var b:request.books()) cleanup.put(b.eplId(),new CalibreUpdateFlow.Choice(0,b.revision(),true,List.of(),"KEEP",List.of(),false));
                var waiting=waits.stream().map(b->new CalibreUpdateFlow.Book(0,b.eplId(),b.title(),b.revision(),List.of(),List.of(),null)).toList();
                String id=transfers.enqueueCatalog(connection,request.library(),selected,waiting,cleanup,downloadId,dependencies,deferredRecords);
                jdbc.update("INSERT INTO calibre_update_requests(id,owner_id,payload,result,created_at) VALUES(?,?,?,?,?)","catalog:"+request.id(),EventContext.actor().id(),signature(request),json.writeValueAsString(id),java.time.Instant.now().toString());
                var result=transfers.job(id);
                stage.accept(id);
                return result;
            });
        });
    }
    CalibreTransfers.Job existing(Request request) {
        var rows=jdbc.queryForList("SELECT owner_id,payload,result FROM calibre_update_requests WHERE id=?","catalog:"+request.id());
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!Objects.equals(row.get("owner_id"),EventContext.actor().id()) || !(Objects.equals(row.get("payload"),signature(request)) || Objects.equals(row.get("payload"),json.writeValueAsString(request)))) throw new UserInputException("La solicitud ya se utilizó con otra selección");
        return transfers.job(json.readValue((String)row.get("result"),String.class));
    }
}
