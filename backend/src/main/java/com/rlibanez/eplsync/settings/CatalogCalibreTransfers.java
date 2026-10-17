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
    public record Prepared(List<CalibreClient.Library> libraries,String library,List<CalibreTransfers.Candidate> files,List<Book> books,List<Book> missing) {}
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
        var page=catalog.search(selection.filters(),PageRequest.of(0,1000,Sort.by("eplId")));
        if(page.isEmpty() || page.getTotalElements()>1000) throw new UserInputException("Selecciona entre 1 y 1000 libros del catálogo");
        var books=page.getContent().stream().map(b->new Book(b.getEplId(),b.getRevision(),b.getTitle())).toList();
        var connection=settings.connection();var check=client.check(connection);
        var candidates=new ArrayList<CalibreTransfers.Candidate>();var missing=new ArrayList<Book>();
        var records=downloads.findByEplIdIn(books.stream().map(book -> Objects.requireNonNull(book).eplId()).toList());
        String instance=tracking.instanceId();long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
        for(var b:books) {
            int before=candidates.size();
            for(var d:records) {
                if(System.nanoTime()>deadline) throw new UserInputException("La preparación ha tardado demasiado; selecciona menos libros");
                if(d.getEplId()!=b.eplId() || Double.compare(d.getRevision(),b.revision())!=0 || !instance.equals(d.getClientInstanceId()) || d.getStatus()!=DownloadStatus.DOWNLOADED) continue;
                for(var f:epub.list(d.getId())) {
                    if(f.size()>268435456 || candidates.size()>=1000) throw new UserInputException("Máximo 1000 EPUB de 256 MiB por selección");
                    candidates.add(new CalibreTransfers.Candidate(d.getId(),f.index(),f.name(),f.size(),b.eplId(),b.revision(),f.expectedSize()));
                }
            }
            if(candidates.size()==before) missing.add(b);
        }
        return new Prepared(check.libraries(),connection.library(),List.copyOf(candidates),books,List.copyOf(missing));
    }
    public CalibreTransfers.Job create(Request request) {
        require();
        if(request==null || request.id()==null || !request.id().matches("[a-zA-Z0-9-]{1,100}") || request.books()==null || request.books().isEmpty() || request.books().size()>1000 || request.books().stream().anyMatch(Objects::isNull) || request.files()==null || request.files().size()>1000 || request.files().stream().anyMatch(Objects::isNull)) throw new UserInputException("Selección de Calibre inválida");
        if(request.pending()==null || request.pending().size()>1000 || request.pending().stream().anyMatch(Objects::isNull) || new HashSet<>(request.pending()).size()!=request.pending().size() || request.pending().stream().anyMatch(id->request.books().stream().noneMatch(b->b.eplId()==id))) throw new UserInputException("Selección de descargas pendientes inválida");
        if(json.writeValueAsString(request).length()>524288) throw new UserInputException("La selección es demasiado grande; selecciona menos libros");
        if(new HashSet<>(request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).toList()).size()!=request.books().size() || new HashSet<>(request.files()).size()!=request.files().size()) throw new UserInputException("No selecciones libros o archivos duplicados");
        return transfers.preparing(()-> {
            var existing=existing(request);if(existing!=null) return existing;
            var connection=settings.connection();String instance=tracking.instanceId();
            var filter=new CatalogBookFilter();filter.setSelectedIds(request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).toArray(Long[]::new));
            var prepared=prepare(new Selection(filter));
            if(!prepared.books().equals(request.books())) throw new UserInputException("El catálogo cambió; vuelve a preparar el envío");
            if(prepared.libraries().stream().noneMatch(l->l.id().equals(request.library()))) throw new UserInputException("La biblioteca de destino no está disponible");
            var selected=new ArrayList<>(request.files().stream().map(f->prepared.files().stream().filter(c->c.recordId().equals(f.recordId()) && c.index()==f.index()).findFirst().orElseThrow(()->new UserInputException("El EPUB seleccionado cambió"))).toList());
            var waits=new ArrayList<Book>();
            if(request.download()) for(long id:request.pending()) {
                if(selected.stream().anyMatch(f->f.eplId()==id)) throw new UserInputException("No selecciones el mismo libro como EPUB y descarga pendiente");
                var available=prepared.files().stream().filter(f->f.eplId()==id).toList();
                if(available.size()>1) throw new UserInputException("La descarga ya está disponible con varios EPUB; vuelve a preparar el envío para elegir uno");
                if(available.size()==1) selected.add(available.getFirst());
                else waits.add(prepared.books().stream().filter(b->b.eplId()==id).findFirst().orElseThrow());
            }
            if(selected.isEmpty() && waits.isEmpty()) throw new UserInputException("Selecciona algún EPUB o activa la descarga de los libros no disponibles");
            if(!waits.isEmpty()) {Permission.require(Permission.TORRENT_SEND);Permission.require(Permission.TORRENT_SYNC);epub.requireRoots();}
            return new TransactionTemplate(manager).execute(status->{
                var raced=existing(request);if(raced!=null) return raced;
                if(!connection.equals(settings.connection()) || !instance.equals(tracking.instanceId())) throw new UserInputException("La configuración cambió; vuelve a preparar el envío");
                var current=catalog.search(filter,PageRequest.of(0,1000,Sort.by("eplId"))).getContent().stream().map(b->new Book(b.getEplId(),b.getRevision(),b.getTitle())).toList();
                if(!current.equals(request.books())) throw new UserInputException("El catálogo cambió; vuelve a preparar el envío");
                var dependencies=new HashMap<Long,String>();var missing=new ArrayList<Long>();
                for(var b:waits) {
                    var active=jdbc.queryForList("SELECT i.job_id FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND i.revision=? AND j.target_fingerprint=? AND j.state<>'CANCELLED' AND i.state IN ('PENDING','IN_FLIGHT','ACCEPTED','ALREADY_EXISTS') AND NOT EXISTS (SELECT 1 FROM torrent_downloads d WHERE d.client_instance_id=? AND d.hash=i.hash AND d.status IN ('ERROR','NOT_FOUND')) ORDER BY j.created_at DESC LIMIT 1",String.class,b.eplId(),b.revision(),instance,instance);
                    if(active.isEmpty()) missing.add(b.eplId());else dependencies.put(b.eplId(),active.getFirst());
                }
                String downloadId=null;
                if(!missing.isEmpty()) {
                    var downloadFilter=new CatalogBookFilter();downloadFilter.setSelectedIds(missing.toArray(Long[]::new));
                    downloadId=bulk.create(downloadFilter,org.springframework.data.domain.Pageable.unpaged(),false,false,new BulkRequest(null,null,null,null,MultipleHashes.FIRST)).jobId();
                }
                var cleanup=new HashMap<Long,CalibreUpdateFlow.Choice>();
                for(var b:request.books()) cleanup.put(b.eplId(),new CalibreUpdateFlow.Choice(0,b.revision(),true,List.of(),"KEEP",List.of(),false));
                var waiting=waits.stream().map(b->new CalibreUpdateFlow.Book(0,b.eplId(),b.title(),b.revision(),List.of(),List.of(),null)).toList();
                String id=transfers.enqueueCatalog(connection,request.library(),selected,waiting,cleanup,downloadId,dependencies);
                jdbc.update("INSERT INTO calibre_update_requests(id,owner_id,payload,result,created_at) VALUES(?,?,?,?,?)","catalog:"+request.id(),EventContext.actor().id(),json.writeValueAsString(request),json.writeValueAsString(id),java.time.Instant.now().toString());
                return transfers.job(id);
            });
        });
    }
    private CalibreTransfers.Job existing(Request request) {
        var rows=jdbc.queryForList("SELECT owner_id,payload,result FROM calibre_update_requests WHERE id=?","catalog:"+request.id());
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!Objects.equals(row.get("owner_id"),EventContext.actor().id()) || !Objects.equals(row.get("payload"),json.writeValueAsString(request))) throw new UserInputException("La solicitud ya se utilizó con otra selección");
        return transfers.job(json.readValue((String)row.get("result"),String.class));
    }
}
