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
            org.springframework.jdbc.core.JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager) {
        this.catalog=catalog;this.downloads=downloads;this.tracking=tracking;this.epub=epub;this.settings=settings;
        this.client=client;this.transfers=transfers;this.bulk=bulk;this.jdbc=jdbc;this.manager=manager;
        this.json=tools.jackson.databind.json.JsonMapper.builder().build();
    }
    private static void require() {
        Permission.require(Permission.CATALOG_READ);Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);
    }
    public Prepared prepare(Selection selection) {
        require();
        if(selection==null || selection.filters()==null) throw new UserInputException("Selecciona libros del catálogo");
        var books=books(selection.filters());
        if(books.isEmpty()) throw new UserInputException("Selecciona libros del catálogo");
        var connection=settings.connection();var check=client.check(connection);
        var candidates=new ArrayList<CalibreTransfers.Candidate>();var missing=new ArrayList<Book>();var deferred=new ArrayList<Book>();var deferredIds=new HashMap<Long,String>();
        var records=new HashMap<Long,List<DownloadRecord>>();
        for(int offset=0;offset<books.size();offset+=500) {
            var ids=books.subList(offset,Math.min(offset+500,books.size())).stream().map(book -> Objects.requireNonNull(book).eplId()).toList();
            for(var d:downloads.findByEplIdIn(ids)) records.computeIfAbsent(d.getEplId(),ignored->new ArrayList<>()).add(d);
        }
        String instance=tracking.instanceId();
        for(var b:books) {
            var ready=records.getOrDefault(b.eplId(),List.of()).stream().filter(d->Double.compare(d.getRevision(),b.revision())==0 && instance.equals(d.getClientInstanceId()) && d.getStatus()==DownloadStatus.DOWNLOADED).toList();
            if(ready.isEmpty()) {missing.add(b);continue;}
            // Large selections are prepared locally. File discovery belongs to the worker.
            if(books.size()>1000) {deferred.add(b);deferredIds.put(b.eplId(),ready.getFirst().getId());continue;}
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
        if(filter.getSelectedIds()!=null && filter.getSelectedIds().length>500) {
            var ids=filter.getSelectedIds();
            for(int offset=0;offset<ids.length;offset+=500) {
                var chunk=json.readValue(json.writeValueAsString(filter),CatalogBookFilter.class);
                chunk.setSelectedIds(Arrays.copyOfRange(ids,offset,Math.min(offset+500,ids.length)));
                result.addAll(books(chunk));
            }
            result.sort(Comparator.comparingLong(book -> Objects.requireNonNull(book).eplId()));return List.copyOf(result);
        }
        for(int page=0;;page++) {
            var batch=catalog.search(filter,PageRequest.of(page,500,Sort.by("eplId")));
            batch.forEach(b->result.add(new Book(b.getEplId(),b.getRevision(),b.getTitle())));
            if(!batch.hasNext()) break;
        }
        return List.copyOf(result);
    }
    private String signature(Request request) {
        try {return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public CalibreTransfers.Job create(Request request) {
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
                for(int offset=0;offset<needed.size();offset+=500) {
                    var batch=needed.subList(offset,Math.min(offset+500,needed.size()));
                    var placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
                    var args=new ArrayList<Object>();args.add(instance);args.add(instance);batch.forEach(book->args.add(book.eplId()));
                    var active=jdbc.queryForList("SELECT i.epl_id,i.revision,i.job_id FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id WHERE j.target_fingerprint=? AND j.state<>'CANCELLED' AND i.state IN ('PENDING','IN_FLIGHT','ACCEPTED','ALREADY_EXISTS') AND NOT EXISTS(SELECT 1 FROM torrent_downloads d WHERE d.client_instance_id=? AND d.hash=i.hash AND d.status IN ('ERROR','NOT_FOUND')) AND i.epl_id IN ("+placeholders+") ORDER BY j.created_at DESC",args.toArray());
                    for(var row:active) {long epl=((Number)row.get("epl_id")).longValue();if(Double.compare(((Number)row.get("revision")).doubleValue(),pinned.get(epl).revision())==0) dependencies.putIfAbsent(epl,(String)row.get("job_id"));}
                    for(var book:batch) if(!dependencies.containsKey(book.eplId())) missing.add(book.eplId());
                }
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
                return transfers.job(id);
            });
        });
    }
    private CalibreTransfers.Job existing(Request request) {
        var rows=jdbc.queryForList("SELECT owner_id,payload,result FROM calibre_update_requests WHERE id=?","catalog:"+request.id());
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!Objects.equals(row.get("owner_id"),EventContext.actor().id()) || !(Objects.equals(row.get("payload"),signature(request)) || Objects.equals(row.get("payload"),json.writeValueAsString(request)))) throw new UserInputException("La solicitud ya se utilizó con otra selección");
        return transfers.job(json.readValue((String)row.get("result"),String.class));
    }
}
