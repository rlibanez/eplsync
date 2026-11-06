package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.exception.*;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.service.*;
import com.rlibanez.eplsync.specification.*;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import com.rlibanez.eplsync.config.TorrentProperties;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Freeze the read selection once, then commit bounded, inactive item batches. */
@Service
public class BulkCatalogPreparation {
    private static final int BATCH=100;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BulkCatalogPreparation.class);
    public static final class Failed extends TorrentOperationException {
        private final String jobId;
        Failed(String jobId,RuntimeException cause) {
            super(HttpStatus.UNPROCESSABLE_CONTENT,jobId==null
                ? "La preparación no pudo completarse. No se ha creado ningún trabajo ni enviado torrents"
                : "La preparación no pudo completarse. El trabajo se ha cancelado sin enviar torrents; puedes consultar su detalle");
            this.jobId=jobId;initCause(cause);
        }
        public String jobId() {return jobId;}
    }
    private final BulkJobRepository jobs;private final BulkItemRepository items;
    private final TorrentDownloadService downloads;private final TorrentClientService client;
    private final CatalogLibraryFilters libraries;private final MagnetLinkBuilder magnets;
    private final TransactionTemplate read,write;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Set<String> live=ConcurrentHashMap.newKeySet();
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public BulkCatalogPreparation(BulkJobRepository jobs,BulkItemRepository items,TorrentDownloadService downloads,TorrentClientService client,CatalogLibraryFilters libraries,TorrentProperties properties,PlatformTransactionManager manager) {
        this.jobs=jobs;this.items=items;this.downloads=downloads;this.client=client;this.libraries=libraries;this.magnets=new MagnetLinkBuilder(properties);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(600);
        write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    private <T> T writing(BulkStore store,Supplier<T> work) {
        for(int attempt=0;;attempt++) {
            try {synchronized(store) {return write.execute(status->work.get());}}
            catch(RuntimeException ex) {if(attempt>=2 || !com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;}
        }
    }
    public BulkStore.View create(BulkStore store,CatalogBookFilter filter,Pageable pageable,boolean paginated,boolean all,BulkRequest input) {
        TorrentPreparationProgress.allowCancellation();
        client.requireEnabled();filter.normalize();
        if(!paginated && !all && !store.hasFilter(filter)) throw new UserInputException("Sin filtros ni paginación se requiere all=true");
        var request=input==null?new BulkRequest(null,null,null,null):input;
        var job=store.newJob(request,false);job.setPreparing(true);job.setState(BulkJob.State.PAUSED);
        job.setMessage("Preparando el trabajo; no se enviarán torrents hasta completar la selección");
        live.add(job.getId());Path snapshot=null;
        try {
            if(!TorrentPreparationProgress.tracked()) log.info("Preparación torrent por lotes: trabajo={}, fase=SNAPSHOT",job.getId());
            snapshot=Files.createTempFile("eplsync-torrent-selection-",".json.gz");
            Path target=snapshot;
            long total=Objects.requireNonNull(read.execute(status->freeze(store,filter,pageable,paginated,target)));
            if(total==0) throw new UserInputException("No hay libros que coincidan con los filtros");
            job.setSelectedBooks(total);
            writing(store,()->{jobs.saveAndFlush(job);return true;});
            TorrentPreparationProgress.report("PREPARING",0,total,0,job.getId());
            long processed=0,position=0;
            try(var seen=new SelectionHashIndex();var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(snapshot)),java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    var batch=new ArrayList<BulkItem>();int books=0;
                    do {
                        TorrentPreparationProgress.checkCancelled();
                        if(Thread.currentThread().isInterrupted()) throw new UserInputException("Preparación interrumpida antes de activar el trabajo");
                        var book=json.readValue(line,CatalogBook.class);books++;
                        var hashes=magnets.hashes(book.getLinks());
                        if(hashes.isEmpty() || hashes.size()>1 && job.getMultipleHashes()==MultipleHashes.SKIP) {
                            var item=store.newItem(job,book.getEplId(),book.getRevision(),position++);
                            item.setState(BulkItem.State.SKIPPED);item.setMessage(hashes.isEmpty()?"El libro no tiene hashes torrent válidos":"Libro omitido por multipleHashes=skip: tiene varios hashes");batch.add(item);
                        } else for(String hash:job.getMultipleHashes()==MultipleHashes.ALL?hashes:List.of(hashes.getFirst())) {
                            var item=store.newItem(job,book.getEplId(),book.getRevision(),position++);item.setHash(hash);
                            if(!seen.add(hash)) {item.setState(BulkItem.State.SKIPPED);item.setMessage("Hash duplicado en la selección");}
                            else try {
                                var options=request.options();
                                var explicit=options==null?new TorrentDownloadRequest(hash,null,null,null,null):new TorrentDownloadRequest(hash,options.start(),options.savePath(),options.rename(),options.qbittorrent());
                                item.setCommandJson(BulkCommandSnapshot.encode(downloads.prepare(book,explicit),json));
                            } catch(TorrentOperationException | IllegalArgumentException ex) {item.setState(BulkItem.State.SKIPPED);item.setMessage(BulkStore.safeMessage(ex));}
                            batch.add(item);
                        }
                        if(books>=BATCH) break;
                    } while((line=reader.readLine())!=null);
                    writing(store,()->{
                        var current=jobs.findById(job.getId()).orElseThrow();
                        if(!current.isPreparing() || current.getState()!=BulkJob.State.PAUSED) throw new UserInputException("La preparación ya no está activa");
                        for(var item:batch) em.persist(item);
                        current.setUpdatedAt(Instant.now());em.flush();return true;
                    });
                    processed+=books;TorrentPreparationProgress.report("PREPARING",processed,total,position,job.getId());
                    if(!TorrentPreparationProgress.tracked() && processed==total) {
                        log.info("Preparación torrent por lotes: trabajo={}, librosProcesados={}/{}, elementos={}",job.getId(),processed,total,position);
                    }
                }
            }
            TorrentPreparationProgress.report("FINALIZING",processed,total,position,job.getId());
            return writing(store,()->{
                var current=jobs.findById(job.getId()).orElseThrow();
                if(!current.isPreparing()) return store.view(job.getId());
                if(current.getState()!=BulkJob.State.PAUSED) throw new UserInputException("La preparación ya no está activa");
                current.setPreparing(false);current.setMessage(null);current.setUpdatedAt(Instant.now());
                current.setState(items.countByJobIdAndState(job.getId(),BulkItem.State.PENDING)>0?BulkJob.State.QUEUED:BulkJob.State.COMPLETED);
                jobs.saveAndFlush(current);store.event(current,com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED);
                if(current.getState()==BulkJob.State.COMPLETED) store.event(current,com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
                return store.view(current.getId());
            });
        } catch(TorrentPreparationProgress.Cancelled ex) {
            writing(store,()->{
                var current=jobs.findById(job.getId()).orElse(null);
                if(current!=null && current.isPreparing()) {
                    em.createQuery("delete from BulkItem i where i.jobId=:id").setParameter("id",job.getId()).executeUpdate();
                    jobs.delete(current);jobs.flush();
                }
                return true;
            });
            throw ex;
        } catch(IOException ex) {return failed(store,job.getId(),new java.io.UncheckedIOException(ex));}
        catch(RuntimeException ex) {return failed(store,job.getId(),ex);
        } finally {
            live.remove(job.getId());
            if(snapshot!=null) try {Files.deleteIfExists(snapshot);}catch(IOException ex) {log.warn("No se pudo eliminar la selección temporal de torrents: tipo={}",ex.getClass().getSimpleName());}
        }
    }
    private BulkStore.View failed(BulkStore store,String id,RuntimeException cause) {
        boolean interrupted=Thread.interrupted();
        try {
            // A committed activation wins over a lost acknowledgement from its commit callback.
            var activated=read.execute(status->jobs.findById(id).filter(current->!current.isPreparing()).map(current->store.view(current.getId())).orElse(null));
            if(activated!=null) return activated;
            if(cause instanceof UserInputException && jobs.findById(id).isEmpty()) throw cause;
            throw abort(store,id,cause);
        } finally {if(interrupted) Thread.currentThread().interrupt();}
    }
    private Failed abort(BulkStore store,String id,RuntimeException cause) {
        String retained=writing(store,()->{
            var current=jobs.findById(id).orElse(null);if(current==null) return null;
            if(!current.isPreparing()) return id;
            items.transition(id,BulkItem.State.PENDING,BulkItem.State.CANCELLED);
            current.setPreparing(false);current.setState(BulkJob.State.CANCELLED);current.setUpdatedAt(Instant.now());
            current.setMessage("Preparación fallida; no se ha enviado ningún torrent. Puedes iniciar otro trabajo");jobs.saveAndFlush(current);return id;
        });return new Failed(retained,cause);
    }
    private long freeze(BulkStore store,CatalogBookFilter filter,Pageable pageable,boolean paginated,Path path) {
        TorrentPreparationProgress.report("COUNTING",0,0,0,null);
        var sort=pageable.getSort();if(sort.getOrderFor("eplId")==null) sort=sort.and(Sort.by("eplId"));store.validateSort(sort);
        var spec=CatalogBookSpecifications.fromFilter(filter);if(filter.hasLibraryFilters()) spec=spec.and(libraries.fromFilter(filter));
        var cb=em.getCriteriaBuilder();var count=cb.createQuery(Long.class);var root=count.from(CatalogBook.class);count.select(cb.count(root));
        var predicate=spec.toPredicate(root,count,cb);if(predicate!=null) count.where(predicate);
        long matching=em.createQuery(count).getSingleResult(),offset=paginated?pageable.getOffset():0;
        long total=paginated?Math.max(0,Math.min(matching-offset,pageable.getPageSize())):matching,processed=0;
        try(var writer=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)),java.nio.charset.StandardCharsets.UTF_8))) {
            var query=cb.createQuery(CatalogBook.class);var from=query.from(CatalogBook.class);query.select(from);
            var where=spec.toPredicate(from,query,cb);if(where!=null) query.where(where);
            var orders=new ArrayList<jakarta.persistence.criteria.Order>();
            for(var order:sort) orders.add(com.rlibanez.eplsync.ordering.TextOrdering.order(cb,from,order));query.orderBy(orders);
            var selection=em.createQuery(query).setFirstResult(Math.toIntExact(offset));
            if(paginated) selection.setMaxResults(Math.toIntExact(total));
            selection.setHint("org.hibernate.readOnly",true);
            TorrentPreparationProgress.report("SNAPSHOT",0,total,0,null);
            // One ordered cursor: repeated OFFSET queries re-sort and scan the growing prefix.
            try(var stream=selection.getResultStream()) {
                var cursor=stream.iterator();
                while(cursor.hasNext()) {
                    TorrentPreparationProgress.checkCancelled();
                    var book=cursor.next();writer.write(json.writeValueAsString(book));writer.newLine();
                    em.detach(book);processed++;
                    if(processed%BATCH==0 || processed==total) TorrentPreparationProgress.report("SNAPSHOT",processed,total,0,null);
                }
            }
            if(processed!=total) throw new IllegalStateException("La selección cambió durante su captura");
            return total;
        } catch(IOException ex) {throw new UncheckedIOException(ex);}
    }
    void recover() {
        for(var job:jobs.findAll((root,q,cb)->cb.isTrue(root.get("preparing")))) if(!live.contains(job.getId())) {
            items.transition(job.getId(),BulkItem.State.PENDING,BulkItem.State.CANCELLED);job.setPreparing(false);job.setState(BulkJob.State.CANCELLED);
            job.setMessage("Preparación interrumpida por reinicio; no se enviaron torrents. Crea un nuevo trabajo");job.setUpdatedAt(Instant.now());jobs.saveAndFlush(job);
        }
    }
}
