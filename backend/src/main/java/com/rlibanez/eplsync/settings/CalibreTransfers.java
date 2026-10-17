package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.OperationDiagnostics;

import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable single-sender queue. No database transaction is held while accessing remote servers. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class CalibreTransfers {
    public record FileChoice(String recordId,int index) {}
    public record Candidate(String recordId,int index,String name,long size,long eplId,double revision,long expectedSize) {}
    public record Prepared(List<CalibreClient.Library> libraries,String library,List<Candidate> files,List<String> deferredRecords) {
        public Prepared(List<CalibreClient.Library> libraries,String library,List<Candidate> files) {this(libraries,library,files,List.of());}
    }
    public record Request(String library,List<FileChoice> files,List<String> deferredRecords) {
        public Request {if(deferredRecords==null) deferredRecords=List.of();}
        public Request(String library,List<FileChoice> files) {this(library,files,List.of());}
    }
    public record Job(String id,String createdAt,String updatedAt,String username,String library,String state,String message,
        long total,long pending,long accepted,long skipped,long failed,long uncertain) {}
    public record Item(String id,String recordId,int index,String name,long size,long eplId,String revision,String state,Long calibreId,String message,boolean identityComplete,String downloadJobId,String coverUrl,Boolean coverAvailable,long position) {
        public Item(String id,String recordId,int index,String name,long size,long eplId,String revision,String state,Long calibreId,String message,boolean identityComplete,String downloadJobId) {
            this(id,recordId,index,name,size,eplId,revision,state,calibreId,message,identityComplete,downloadJobId,null,null,0);
        }
    }
    public record Meta(int page,int size,long totalItems,long totalPages,boolean hasPrevious,boolean hasNext) {}
    public record Page<T>(List<T> items,Meta meta) {}
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private CalibreUpdateFlow updates;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.repository.CatalogBookRepository catalogBooks;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final CalibreCatalogService catalog;
    private final EpubFiles files;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentProperties torrent;
    private final AccountStore accounts;
    private final EventJournal events;
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock();
    private final java.util.concurrent.locks.ReentrantLock cycleLock=new java.util.concurrent.locks.ReentrantLock();
    // Keep an accepted remote ID if SQLite temporarily rejects the following local write.
    private final Map<String,Long> acceptedRemoteIds=new ConcurrentHashMap<>();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> { var thread=new Thread(r,"calibre-transfers");thread.setDaemon(true);return thread; });
    @org.springframework.beans.factory.annotation.Value("${eplsync.calibre.transfers.worker-enabled:true}") private boolean enabled;
    private static final tools.jackson.databind.json.JsonMapper EPUB_IDENTITIES=tools.jackson.databind.json.JsonMapper.builder().build();
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreTransfers.class);
    public CalibreTransfers(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,CalibreSettings settings,
            CalibreClient client,CalibreCatalogService catalog,EpubFiles files,DownloadRepository downloads,DownloadTrackingService tracking,
            TorrentProperties torrent,AccountStore accounts,EventJournal events) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.settings=settings;this.client=client;this.catalog=catalog;
        this.files=files;this.downloads=downloads;this.tracking=tracking;this.torrent=torrent;this.accounts=accounts;this.events=events;
        recoverInterrupted();
    }
    void recoverInterrupted() {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE calibre_job_items SET state='UNCERTAIN',next_check=0,message=coalesce(message || '. ','') || 'El proceso se interrumpió; se comprobará el envío sin repetirlo' WHERE state='SENDING'");
            // Only active jobs are recovered. A persisted pause, including a user's pause during an upload, stays paused.
            int recovered=jdbc.update("UPDATE calibre_jobs SET state='QUEUED',next_check=0,next_probe=0,message='Recuperado tras reinicio; se comprobarán los envíos pendientes antes de continuar',updated_at=? WHERE state='RUNNING'",Instant.now().toString());
            jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE state IN ('UNCERTAIN','WAITING_DOWNLOAD','CLEANUP_PENDING') AND job_id IN (SELECT id FROM calibre_jobs WHERE state='QUEUED')");
            jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE state='QUEUED' AND id IN (SELECT job_id FROM calibre_job_items WHERE state='UNCERTAIN')");
            if(recovered>0) log.info("Recuperación de trabajos de Calibre tras reinicio: reanudados={}",recovered);
        });
    }
    static String destination(CalibreSettings.Connection c) { return fingerprint(c.url()+"\n"+c.username()); }
    private String source() { return fingerprint(tracking.instanceId()+"\n"+torrent.getFiles().getRemoteRoot()+"\n"+torrent.getFiles().getLocalRoot()); }
    private static String fingerprint(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void requireSend() { Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ); }
    public Prepared prepare(List<String> ids) {
        requireSend();validateIds(ids);
        var connection=settings.connection();var check=client.check(connection);
        if(ids.size()>1000) {
            files.requireRoots();deferredDownloads(ids);
            return new Prepared(check.libraries(),connection.library(),List.of(),List.copyOf(ids));
        }
        var candidates=new ArrayList<Candidate>();long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        for(String id:ids) {
            if(System.nanoTime()>deadline) throw new UserInputException("La preparación ha tardado demasiado; selecciona menos registros");
            var row=downloads.findById(id).orElseThrow(() -> new UserInputException("El registro de descarga ya no existe"));
            List<EpubFiles.Item> available;
            try { available=files.list(id); }
            catch(com.rlibanez.eplsync.exception.TorrentOperationException ex) {
                log.debug("Preparación de envío a Calibre rechazada: registro={}, EPL ID={}, revisión={}, motivo={}",id,row.getEplId(),row.getRevision(),ex.getMessage());
                throw new com.rlibanez.eplsync.exception.TorrentOperationException(ex.getStatus(),"EPL ID "+row.getEplId()+" · revisión "+row.getRevision()+": "+ex.getMessage());
            }
            for(var file:available) {
                if(file.size()>268435456) throw new UserInputException("El EPUB supera 256 MiB");
                candidates.add(new Candidate(id,file.index(),file.name(),file.size(),row.getEplId(),row.getRevision(),file.expectedSize()));
            }
        }
        return new Prepared(check.libraries(),connection.library(),List.copyOf(candidates));
    }
    private static void validateIds(List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.stream().anyMatch(id -> id==null || id.isBlank() || id.length()>64) || new HashSet<>(ids).size()!=ids.size())
            throw new UserInputException("Selecciona registros únicos");
    }
    private List<DownloadRecord> deferredDownloads(List<String> ids) {
        var result=new ArrayList<DownloadRecord>();
        for(int offset=0;offset<ids.size();offset+=500) result.addAll(downloads.findAllById(ids.subList(offset,Math.min(offset+500,ids.size()))));
        if(result.size()!=ids.size() || result.stream().anyMatch(d->!tracking.instanceId().equals(d.getClientInstanceId()) || d.getStatus()!=DownloadStatus.DOWNLOADED)) throw new UserInputException("La selección contiene descargas no disponibles en el cliente actual");
        var indexed=new HashMap<String,DownloadRecord>();result.forEach(record->indexed.put(record.getId(),record));
        return ids.stream().map(id->Objects.requireNonNull(indexed.get(id))).toList();
    }
    public Job create(Request request) {
        requireSend();
        if(request==null || request.library()==null || request.library().isBlank() || request.library().length()>256 || request.files()==null || (request.files().isEmpty() && request.deferredRecords().isEmpty()) || request.files().stream().anyMatch(f -> f==null || f.index()<0)) throw new UserInputException("Selecciona algún EPUB");
        if(new HashSet<>(request.files()).size()!=request.files().size()) throw new UserInputException("No selecciones el mismo EPUB dos veces");
        if(!request.deferredRecords().isEmpty()) {
            if(!request.files().isEmpty()) throw new UserInputException("Selección de archivos incompatible");
            validateIds(request.deferredRecords());
            return preparing(()-> {
                var connection=settings.connection();String selectedSource=source();var check=client.check(connection);
                if(check.libraries().stream().noneMatch(l->l.id().equals(request.library()))) throw new UserInputException("La biblioteca de destino no está disponible");
                var records=deferredDownloads(request.deferredRecords());
                return tx.execute(status->{
                    if(!selectedSource.equals(source())) throw new UserInputException("La configuración cambió; vuelve a preparar el envío");
                    String id=createQueuedJob(connection,request.library());int position=0;
                    for(var d:records) {
                        var candidate=new Candidate(d.getId(),0,"EPL ID "+d.getEplId(),0,d.getEplId(),d.getRevision(),0);
                        insertUpdate(id,position++,candidate,new CalibreUpdateFlow.Choice(0,d.getRevision(),true,List.of(),"KEEP",List.of(),false),null,true);
                    }
                    event(EventContext.actor(),id,"CALIBRE_SEND",EventJournal.Outcome.STARTED,Map.of("library",request.library(),"selected",records.size(),"waiting",records.size()));
                    return job(id);
                });
            });
        }
        lifecycle.readLock().lock();
        try {
            var connection=settings.connection();String source=source();
            var prepared=prepare(request.files().stream().map(choice -> java.util.Objects.requireNonNull(choice).recordId()).distinct().toList());
            if(prepared.libraries().stream().noneMatch(l -> l.id().equals(request.library()))) throw new UserInputException("La biblioteca de destino no está disponible");
            var byFile=new HashMap<FileChoice,Candidate>();prepared.files().forEach(f -> byFile.put(new FileChoice(f.recordId(),f.index()),f));
            var selected=request.files().stream().map(f -> {var candidate=byFile.get(f);if(candidate==null) throw new UserInputException("El EPUB seleccionado ya no está disponible");return candidate;}).toList();
            var actor=EventContext.actor();String id=UUID.randomUUID().toString(),now=Instant.now().toString();
            tx.executeWithoutResult(s -> {
                if(!connection.equals(settings.connection()) || !source.equals(source())) throw new UserInputException("La configuración cambió; vuelve a preparar el envío");
                jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,'QUEUED')",id,now,now,actor.id(),actor.username()==null ? "Sistema" : actor.username(),destination(connection),request.library(),source);
                for(int position=0;position<selected.size();position++) {
                    var f=selected.get(position);
                    jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?)",UUID.randomUUID().toString(),id,position,f.recordId(),f.index(),f.name(),f.size(),f.eplId(),BigDecimal.valueOf(f.revision()).stripTrailingZeros().toPlainString(),f.size()!=f.expectedSize()?SIZE_WARNING:null);
                }
                event(actor,id,"CALIBRE_SEND",EventJournal.Outcome.STARTED,Map.of("library",request.library(),"selected",selected.size()));
            });
            return job(id);
        } finally { lifecycle.readLock().unlock(); }
    }
    String enqueueUpdates(CalibreSettings.Connection connection,List<Candidate> selected,List<CalibreUpdateFlow.Book> waits,Map<Long,CalibreUpdateFlow.Choice> cleanup,String downloadId) {
        return enqueueUpdates(connection,selected,waits,cleanup,downloadId,Map.of());
    }
    String enqueueUpdates(CalibreSettings.Connection connection,List<Candidate> selected,List<CalibreUpdateFlow.Book> waits,Map<Long,CalibreUpdateFlow.Choice> cleanup,String downloadId,Map<Long,String> dependencies) {
        return enqueueCatalog(connection,connection.library(),selected,waits,cleanup,downloadId,dependencies);
    }
    String enqueueCatalog(CalibreSettings.Connection connection,String library,List<Candidate> selected,List<CalibreUpdateFlow.Book> waits,Map<Long,CalibreUpdateFlow.Choice> cleanup,String downloadId,Map<Long,String> dependencies) {
        return enqueueCatalog(connection,library,selected,waits,cleanup,downloadId,dependencies,Map.of());
    }
    String enqueueCatalog(CalibreSettings.Connection connection,String library,List<Candidate> selected,List<CalibreUpdateFlow.Book> waits,Map<Long,CalibreUpdateFlow.Choice> cleanup,String downloadId,Map<Long,String> dependencies,Map<Long,String> deferredRecords) {
        String id=createQueuedJob(connection,library);
        var actor=EventContext.actor();
        int position=0;
        for(var f:selected) insertUpdate(id,position++,f,cleanup.get(f.eplId()),null);
        for(var b:waits) insertUpdate(id,position++,new Candidate(deferredRecords.get(b.eplId()),0,b.title(),0,b.eplId(),b.revision(),0),cleanup.get(b.eplId()),dependencies.getOrDefault(b.eplId(),downloadId),true);
        event(actor,id,"CALIBRE_SEND",EventJournal.Outcome.STARTED,Map.of("library",library,"selected",position,"waiting",waits.size(),"cleanupBooks",cleanup.values().stream().filter(c -> !c.cleanup().equals("KEEP")).count()));
        return id;
    }
    private String createQueuedJob(CalibreSettings.Connection connection,String library) {
        if(!connection.equals(settings.connection())) throw new UserInputException("La configuración cambió; vuelve a preparar el envío");
        var actor=EventContext.actor();String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,'QUEUED')",id,now,now,actor.id(),actor.username(),destination(connection),library,source());
        return id;
    }
    private void insertUpdate(String job,int position,Candidate f,CalibreUpdateFlow.Choice cleanup,String downloadId) {
        insertUpdate(job,position,f,cleanup,downloadId,false);
    }
    private void insertUpdate(String job,int position,Candidate f,CalibreUpdateFlow.Choice cleanup,String downloadId,boolean waiting) {
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message,download_job_id,cleanup_policy,cleanup_ids,wait_until) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),job,position,f.recordId(),waiting?null:f.index(),f.name(),waiting?null:f.size(),f.eplId(),BigDecimal.valueOf(f.revision()).stripTrailingZeros().toPlainString(),waiting?"WAITING_DOWNLOAD":"PENDING",waiting?(downloadId==null?"EPUB pendiente de comprobación":"Esperando la descarga del EPUB"):(f.size()!=f.expectedSize()?SIZE_WARNING:null),downloadId,cleanup.cleanup(),cleanup.cleanup().equals("KEEP")?"[]":tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(cleanup.previous()),waiting?Instant.now().plus(Duration.ofDays(30)).toEpochMilli():null);
    }
    private static final String SUMMARY="SELECT j.*,count(i.id) AS total,sum(CASE WHEN i.state IN ('PENDING','SENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','CLEANUP_PENDING') THEN 1 ELSE 0 END) AS pending,sum(CASE WHEN i.state='ACCEPTED' THEN 1 ELSE 0 END) AS accepted,sum(CASE WHEN i.state='SKIPPED' THEN 1 ELSE 0 END) AS skipped,sum(CASE WHEN i.state='FAILED' THEN 1 ELSE 0 END) AS failed,sum(CASE WHEN i.state='UNCERTAIN' THEN 1 ELSE 0 END) AS uncertain FROM calibre_jobs j LEFT JOIN calibre_job_items i ON i.job_id=j.id";
    private static Job mapJob(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Job(r.getString("id"),r.getString("created_at"),r.getString("updated_at"),r.getString("owner_username"),r.getString("library"),r.getString("state"),r.getString("message"),r.getLong("total"),r.getLong("pending"),r.getLong("accepted"),r.getLong("skipped"),r.getLong("failed"),r.getLong("uncertain")); }
    private static Item mapItem(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Item(r.getString("id"),r.getString("record_id"),r.getInt("file_index"),r.getString("name"),r.getLong("size"),r.getLong("epl_id"),r.getString("revision"),r.getString("state"),r.getObject("calibre_id")==null ? null : r.getLong("calibre_id"),r.getString("message"),r.getBoolean("identity_complete"),r.getString("download_job_id"),null,null,r.getLong("position")); }
    public Job job(String id) {
        var result=jdbc.query(SUMMARY+" WHERE j.id=? GROUP BY j.id",CalibreTransfers::mapJob,id);
        if(result.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Trabajo de Calibre no encontrado");return result.getFirst();
    }
    private static Meta meta(int page,int size,long total) { long pages=(total+size-1)/size;return new Meta(page,size,total,pages,page>0,page+1<pages); }
    private static String order(List<String> sorts,Map<String,String> fields) {
        if(sorts.size()>8 || sorts.isEmpty()) throw new UserInputException("Ordenación inválida");
        var result=new ArrayList<String>();
        for(String sort:sorts) { var parts=sort.split(",",-1);if(parts.length!=2 || !fields.containsKey(parts[0]) || !Set.of("asc","desc").contains(parts[1])) throw new UserInputException("Ordenación inválida");result.add(fields.get(parts[0])+" "+parts[1]); }
        return String.join(",",result);
    }
    private static void paging(int page,int size) { if(page<0 || size<1 || size>1000) throw new UserInputException("Paginación inválida"); }
    public Page<Job> jobs(int page,int size,String state,List<String> sort) {
        return jobs(page,size,state,"",sort);
    }
    public Page<Job> jobs(int page,int size,String state,String username,List<String> sort) {
        return jobs(page,size,state,username,null,null,sort);
    }
    public Page<Job> jobs(int page,int size,String state,String username,Instant from,Instant before,List<String> sort) {
        if(from!=null && before!=null && !from.isBefore(before)) throw new UserInputException("Rango de fechas inválido");
        paging(page,size);if(!state.isEmpty() && !Set.of("QUEUED","RUNNING","PAUSED","COMPLETED","CANCELLED").contains(state)) throw new UserInputException("Estado de trabajo inválido");
        String where=state.isEmpty() ? "" : " WHERE j.state=?";var args=new ArrayList<Object>();if(!state.isEmpty()) args.add(state);
        if(!username.isBlank()) { where+=(where.isEmpty() ? " WHERE " : " AND ")+"instr(lower(j.owner_username),lower(?))>0";args.add(username.trim()); }
        if(from!=null) {where+=(where.isEmpty() ? " WHERE " : " AND ")+"julianday(j.created_at)>=julianday(?)";args.add(from.toString());}
        if(before!=null) {where+=(where.isEmpty() ? " WHERE " : " AND ")+"julianday(j.created_at)<julianday(?)";args.add(before.toString());}
        long total=jdbc.queryForObject("SELECT count(*) FROM calibre_jobs j"+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query(SUMMARY+where+" GROUP BY j.id ORDER BY "+order(sort,Map.of("id","j.id","createdAt","j.created_at","username","j.owner_username COLLATE EPL_TEXT","library","j.library COLLATE EPL_TEXT","state","j.state","total","total","accepted","accepted","failed","failed"))+",j.id LIMIT ? OFFSET ?",CalibreTransfers::mapJob,args.toArray()),meta(page,size,total));
    }
    public Page<Item> items(String id,int page,int size,List<String> sort) {return items(id,page,size,"",sort);}
    public Page<Item> items(String id,int page,int size,String state,List<String> sort) {
        return items(id,page,size,state,"",sort);
    }
    public Page<Item> items(String id,int page,int size,String state,String detail,List<String> sort) {
        return items(id,page,size,state,detail,sort,"eq");
    }
    public Page<Item> items(String id,int page,int size,String state,String detail,List<String> sort,String statusOperator) {
        boolean exclude=com.rlibanez.eplsync.ordering.StateOperator.excluded(statusOperator);
        paging(page,size);job(id);
        if(detail==null || detail.length()>4096) throw new UserInputException("Filtro de detalle inválido");
        if(state==null || !state.isEmpty() && !Set.of("PENDING","SENDING","ACCEPTED","SKIPPED","FAILED","UNCERTAIN","CANCELLED","WAITING_DOWNLOAD","BLOCKED_DOWNLOAD","CLEANUP_PENDING").contains(state)) throw new UserInputException("Estado de envío inválido");
        String where=" WHERE job_id=?";var args=new ArrayList<Object>();args.add(id);
        if(!state.isEmpty()) {where+=" AND state"+(exclude?"<>?":"=?");args.add(state);}
        if(!detail.isBlank()) {where+=" AND instr(lower(coalesce(message,'')),lower(?))>0";args.add(detail.trim());}
        long total=jdbc.queryForObject("SELECT count(*) FROM calibre_job_items"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var rows=jdbc.query("SELECT * FROM calibre_job_items"+where+" ORDER BY "+order(sort,Map.of("position","position","eplId","epl_id","revision","CAST(revision AS REAL)","name","name COLLATE EPL_TEXT","state","state","calibreId","calibre_id","message","message COLLATE EPL_TEXT"))+",position LIMIT ? OFFSET ?",CalibreTransfers::mapItem,args.toArray());
        if(Permission.has(Permission.CATALOG_READ) && !rows.isEmpty()) {
            var covers=new HashMap<Long,com.rlibanez.eplsync.model.CatalogBook>();
            catalogBooks.findAllById(rows.stream().map(item -> Objects.requireNonNull(item).eplId()).distinct().toList()).forEach(book->covers.put(book.getEplId(),book));
            rows=rows.stream().map(item->{
                var book=covers.get(item.eplId());
                return book==null?item:new Item(item.id(),item.recordId(),item.index(),item.name(),item.size(),item.eplId(),item.revision(),item.state(),item.calibreId(),item.message(),item.identityComplete(),item.downloadJobId(),book.getCoverUrl(),book.getCoverAvailable(),item.position());
            }).toList();
        }
        return new Page<>(rows,meta(page,size,total));
    }
    public Job control(String id,String command) {
        Permission.require(Permission.CALIBRE_JOBS_MANAGE);
        if(!Set.of("pause","resume","cancel").contains(command)) throw new UserInputException("Control de trabajo inválido");
        tx.executeWithoutResult(s -> {
            var j=job(id);if(j.state().equals("COMPLETED") || j.state().equals("CANCELLED") && !(command.equals("resume") && j.uncertain()>0)) throw new UserInputException("El trabajo ya ha finalizado");
            String next=command.equals("pause") ? "PAUSED" : command.equals("resume") ? "QUEUED" : "CANCELLED";
            jdbc.update("UPDATE calibre_jobs SET state=?,message=NULL,next_check=0,next_probe=0,updated_at=? WHERE id=?",next,Instant.now().toString(),id);
            if(command.equals("resume")) jdbc.update("UPDATE calibre_job_items SET state='WAITING_DOWNLOAD',next_check=0,wait_until=? WHERE job_id=? AND state='BLOCKED_DOWNLOAD'",Instant.now().plus(Duration.ofDays(30)).toEpochMilli(),id);
            if(command.equals("resume") && jdbc.queryForObject("SELECT count(*) FROM job_retry_items r JOIN calibre_job_items i ON i.id=r.source_item_id WHERE r.kind='calibre' AND r.parent_id=? AND i.state='UNCERTAIN'",Long.class,id)>0)
                throw new UserInputException("Hay envíos inciertos transferidos a un reintento; continúa desde ese trabajo");
            if(command.equals("cancel")) {
                jdbc.update("UPDATE calibre_job_items SET requested_cleanup_policy=coalesce(requested_cleanup_policy,cleanup_policy),requested_cleanup_ids=coalesce(requested_cleanup_ids,cleanup_ids),cleanup_cancelled=CASE WHEN state='CLEANUP_PENDING' THEN true ELSE cleanup_cancelled END WHERE job_id=? AND state NOT IN ('ACCEPTED','SKIPPED','FAILED','CANCELLED')",id);
                jdbc.update("UPDATE calibre_job_items SET cleanup_policy='KEEP',cleanup_ids='[]' WHERE job_id=? AND state NOT IN ('ACCEPTED','SKIPPED','FAILED','CANCELLED')",id);
                jdbc.update("UPDATE calibre_job_items SET state=completion_state,message=coalesce(message || '. ','') || 'Limpieza de revisiones anteriores cancelada' WHERE job_id=? AND state='CLEANUP_PENDING'",id);
                jdbc.update("UPDATE calibre_job_items SET state='CANCELLED' WHERE job_id=? AND state IN ('PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD')",id);
            }
            event(EventContext.actor(),id,"CALIBRE_JOB_CONTROL",command.equals("pause")?EventJournal.Outcome.PAUSED:command.equals("resume")?EventJournal.Outcome.RESUMED:EventJournal.Outcome.CANCELLED,Map.of("command",command));
        });return job(id);
    }
    private void event(EventContext.Actor actor,String id,String action,EventJournal.Outcome outcome,Map<String,?> details) {
        var origin=EventContext.origin();
        if(!action.equals("CALIBRE_JOB_CONTROL")) {
            String job=action.equals("CALIBRE_SEND_FILE")?Objects.toString(details.get("jobId"),id):id;
            var origins=jdbc.queryForList("SELECT r.context FROM scheduled_automation_runs r JOIN scheduled_automation_jobs j ON j.run_id=r.id WHERE j.kind='calibre' AND j.job_id=?",String.class,job);
            if(!origins.isEmpty()) {origin=EventContext.Origin.valueOf(origins.getFirst());}
        }
        boolean finalSummary=action.equals("CALIBRE_SEND") && Set.of(EventJournal.Outcome.SUCCEEDED,EventJournal.Outcome.PARTIAL).contains(outcome);
        boolean manualStart=origin!=EventContext.Origin.SCHEDULED && action.equals("CALIBRE_SEND") && outcome==EventJournal.Outcome.STARTED;
        var level=finalSummary || manualStart ? log.atInfo() : log.atDebug();
        level.log("Trabajo de Calibre: operación={}, acción={}, resultado={}, userId={}, usuario={}, detalles={}",id,action,outcome,actor.id(),actor.username(),details);
        if(action.equals("CALIBRE_SEND_FILE")) return;
        if(!action.equals("CALIBRE_JOB_CONTROL") && !Set.of(EventJournal.Outcome.FAILED,EventJournal.Outcome.PARTIAL).contains(outcome)) {
            events.live(actor,EventJournal.Category.JOB,action,outcome,origin,id,details);
            events.notifyCompletion(actor,EventJournal.Category.JOB,action,outcome,origin,id,details);
            return;
        }
        if(action.equals("CALIBRE_JOB_CONTROL")) {
            String operation=UUID.randomUUID().toString();var context=new LinkedHashMap<String,Object>(details);context.put("jobId",id);
            events.recordAs(actor,EventJournal.Category.JOB,action,EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,operation,context);
            events.recordAs(actor,EventJournal.Category.JOB,action,outcome,EventContext.Origin.MANUAL,operation,context);
        } else {
            events.recordAs(actor,EventJournal.Category.JOB,action,EventJournal.Outcome.STARTED,origin,id,details);
            events.recordAs(actor,EventJournal.Category.JOB,action,outcome,origin,id,details);
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void schedule() {
        if(enabled && busy.compareAndSet(false,true)) executor.submit(() -> { try { cycle(); } catch(Exception ex) { log.error("Fallo en el coordinador de envíos a Calibre",ex); } finally {busy.set(false);} });
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void retention() {
        if(!lifecycle.writeLock().tryLock()) return;
        try { jdbc.update("DELETE FROM calibre_update_requests WHERE id IN (SELECT id FROM calibre_update_requests WHERE created_at<? ORDER BY created_at LIMIT 100)",Instant.now().minus(Duration.ofDays(90)).toString());
            jdbc.update("DELETE FROM calibre_jobs WHERE id IN (SELECT id FROM calibre_jobs WHERE state IN ('COMPLETED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM scheduled_automation_jobs a JOIN scheduled_automation_runs r ON r.id=a.run_id WHERE a.kind='calibre' AND a.job_id=calibre_jobs.id AND r.state<>'COMPLETED') AND updated_at<? AND NOT EXISTS (SELECT 1 FROM calibre_job_items i WHERE i.job_id=calibre_jobs.id AND i.state IN ('SENDING','UNCERTAIN') AND NOT EXISTS(SELECT 1 FROM job_retry_items r WHERE r.kind='calibre' AND r.source_item_id=i.id)) AND NOT EXISTS(SELECT 1 FROM job_retry_jobs r JOIN calibre_jobs child ON child.id=r.child_id WHERE r.kind='calibre' AND (r.parent_id=calibre_jobs.id OR r.root_id=calibre_jobs.id)) ORDER BY updated_at LIMIT 100)",Instant.now().minus(Duration.ofDays(90)).toString());
            try(var abandoned=Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),"eplsync-calibre-send-*.epub")) {
                for(var path:abandoned) if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(Duration.ofHours(1)))) Files.deleteIfExists(path);
            } catch(java.io.IOException ex) {log.warn("No se pudo revisar la limpieza de EPUB temporales de Calibre");}
        }
        finally {lifecycle.writeLock().unlock();}
    }
    @jakarta.annotation.PreDestroy void close() { enabled=false;executor.shutdown();try { if(!executor.awaitTermination(65,TimeUnit.SECONDS)) executor.shutdownNow(); }catch(InterruptedException ex) {executor.shutdownNow();Thread.currentThread().interrupt();} }
    <T> T preparing(java.util.function.Supplier<T> work) {
        lifecycle.readLock().lock();
        try {return work.get();} finally {lifecycle.readLock().unlock();}
    }
    public <T> T exclusive(java.util.function.Supplier<T> work) {
        if(!lifecycle.writeLock().tryLock()) throw new UserInputException("Hay un envío a Calibre en curso; espera antes de reiniciar la base de datos");
        try { return work.get(); } finally { lifecycle.writeLock().unlock(); }
    }
    <T> T retryExclusive(java.util.function.Supplier<T> work) {
        if(!lifecycle.writeLock().tryLock()) throw new UserInputException("Hay una operación de Calibre en curso; espera a que termine antes de reintentar");
        try {return work.get();} finally {lifecycle.writeLock().unlock();}
    }
    private void postponeDownloads(String job) {
        updates.postponeDownloadCheck(job);
        log.debug("Comprobación de descargas de Calibre aplazada: trabajo={}, cliente torrent ocupado; se comprobará en el siguiente intervalo",job);
    }
    private void pause(String id,EventContext.Actor actor,String message) {
        tx.executeWithoutResult(s -> { if(jdbc.update("UPDATE calibre_jobs SET state='PAUSED',message=?,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')",message,Instant.now().toString(),id)>0) event(actor,id,"CALIBRE_SEND",EventJournal.Outcome.PAUSED,Map.of("reason",message)); });
    }
    public void cycle() {
        if(!cycleLock.tryLock()) return;
        lifecycle.readLock().lock();
        try {
            // A previous cycle may have completed HTTP but failed to persist its result.
            // cycleLock guarantees that no upload from this worker is still running.
            jdbc.update("UPDATE calibre_job_items SET state='UNCERTAIN',next_check=0 WHERE state='SENDING'");
            var queued=jdbc.queryForList("SELECT * FROM calibre_jobs WHERE state IN ('QUEUED','RUNNING') AND next_check<=? ORDER BY created_at,id LIMIT 1",Instant.now().toEpochMilli());
            if(queued.isEmpty()) return;var j=queued.getFirst();String id=(String)j.get("id");
            var actor=retryActor(id,(String)j.get("owner_id"),(String)j.get("owner_username"));
            var owner=actor.id()==null ? null : accounts.find(actor.id());
            if(owner==null || !owner.status().equals("ACTIVE") || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now()) || owner.mustChangePassword() || !owner.permissions().containsAll(Set.of(Permission.CALIBRE_SEND,Permission.DOWNLOADS_FILES_READ))) {pause(id,actor,"El usuario ya no está autorizado para enviar archivos a Calibre");return;}
            CalibreSettings.Connection connection;
            try { var current=settings.connection();if(!destination(current).equals(j.get("destination")) || !source().equals(j.get("source"))) {pause(id,actor,"La conexión o las carpetas cambiaron; restaura el destino antes de reanudar");return;}
                connection=new CalibreSettings.Connection(current.url(),current.username(),current.password(),(String)j.get("library"));
            } catch(RuntimeException ex) {if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;pause(id,actor,"Revisa la configuración de Calibre antes de continuar");return;}
            jdbc.update("UPDATE calibre_jobs SET state='RUNNING',message=NULL,updated_at=? WHERE id=? AND state='QUEUED'",Instant.now().toString(),id);
            var pending=jdbc.query("SELECT * FROM calibre_job_items WHERE job_id=? AND state IN ('PENDING','UNCERTAIN','WAITING_DOWNLOAD','CLEANUP_PENDING') AND next_check<=? ORDER BY CASE WHEN state='WAITING_DOWNLOAD' THEN 2 WHEN state='UNCERTAIN' THEN 0 ELSE 1 END,position LIMIT 1",CalibreTransfers::mapItem,id,Instant.now().toEpochMilli());
            if(pending.isEmpty()) {
                jdbc.update("UPDATE calibre_jobs SET next_check=coalesce((SELECT min(next_check) FROM calibre_job_items WHERE job_id=? AND state='WAITING_DOWNLOAD'),0) WHERE id=?",id,id);
                complete(id,actor);return;
            }
            var item=pending.getFirst();
            if(item.state().equals("PENDING")) event(actor,item.id(),"CALIBRE_SEND_FILE",EventJournal.Outcome.STARTED,Map.of("jobId",id,"eplId",item.eplId(),"revision",item.revision()));
            if(item.state().equals("WAITING_DOWNLOAD")) {
                try {
                    if(owner.permissions().contains(Permission.TORRENT_SYNC)) updates.refreshDownloads(id);
                    var waiting=jdbc.query("SELECT * FROM calibre_job_items WHERE job_id=? AND state='WAITING_DOWNLOAD' AND next_check<=? ORDER BY position LIMIT 50",CalibreTransfers::mapItem,id,Instant.now().toEpochMilli());
                    long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
                    for(var w:waiting) {
                        if(System.nanoTime()>deadline || !job(id).state().equals("RUNNING")) break;
                        try {if(w.downloadJobId()!=null && !owner.permissions().contains(Permission.TORRENT_SYNC)) throw new UserInputException("El usuario ya no puede consultar el cliente torrent");updates.waiting(id,w);} catch(RuntimeException ex) {
                            if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
                            if(ex instanceof com.rlibanez.eplsync.exception.TorrentBusyException) {postponeDownloads(id);break;}
                            log.warn("No se pudo comprobar la descarga pendiente de Calibre: trabajo={}, EPL ID={}, tipo={}",id,w.eplId(),OperationDiagnostics.technical(ex));
                            jdbc.update("UPDATE calibre_job_items SET state='BLOCKED_DOWNLOAD',message=? WHERE id=? AND state='WAITING_DOWNLOAD'",safeMessage(ex,"No se puede comprobar o leer la descarga; revisa conexión y carpetas"),w.id());
                            pause(id,actor,"Hay una descarga pendiente que requiere revisión");break;
                        }
                    }
                } catch(RuntimeException ex) {
                    if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
                    if(ex instanceof com.rlibanez.eplsync.exception.TorrentBusyException) {postponeDownloads(id);return;}
                    log.warn("No se pudo comprobar la descarga pendiente de Calibre: trabajo={}, EPL ID={}, tipo={}",id,item.eplId(),OperationDiagnostics.technical(ex));
                    jdbc.update("UPDATE calibre_job_items SET state='BLOCKED_DOWNLOAD',message=? WHERE id=? AND state='WAITING_DOWNLOAD'",safeMessage(ex,"No se puede comprobar o leer la descarga; revisa conexión y carpetas"),item.id());
                    pause(id,actor,"Hay una descarga pendiente que requiere revisión");
                }
                return;
            }
            if(item.state().equals("CLEANUP_PENDING")) {
                try {
                    if(!owner.permissions().contains(Permission.CALIBRE_DELETE)) throw new UserInputException("El usuario ya no tiene permiso para eliminar revisiones de Calibre");
                    updates.cleanup(id,connection,item);finish(id,actor,item,jdbc.queryForObject("SELECT completion_state FROM calibre_job_items WHERE id=?",String.class,item.id()),item.calibreId(),"EPUB verificado; limpieza de revisiones anteriores completada");
                } catch(RuntimeException ex) {
                    if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
                    log.warn("Limpieza de Calibre pendiente: trabajo={}, elemento={}, causa={}",id,item.id(),OperationDiagnostics.technical(ex));
                    jdbc.update("UPDATE calibre_job_items SET message=? WHERE id=?",safeMessage(ex,"No se pudo confirmar la limpieza de las revisiones anteriores"),item.id());
                    pause(id,actor,"Limpieza pendiente; reanuda para comprobarla sin repetir el envío");
                }
            } else process(id,j,actor,connection,item);
            if(job(id).pending()==0 && job(id).uncertain()==0) complete(id,actor);
        } catch(RuntimeException ex) {
            if(!com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
            log.debug("Ciclo de Calibre aplazado por contención SQLite; se verificará sin repetir envíos");
        } finally { lifecycle.readLock().unlock();cycleLock.unlock(); }
    }
    private EventContext.Actor retryActor(String job,String owner,String username) {
        var rows=jdbc.queryForList("SELECT owner_id,username FROM job_retry_attempts WHERE kind='calibre' AND job_id=? ORDER BY created_at DESC,id DESC LIMIT 1",job);
        return rows.isEmpty()?new EventContext.Actor(owner,username,"USER"):new EventContext.Actor((String)rows.getFirst().get("owner_id"),(String)rows.getFirst().get("username"),"USER");
    }
    void requireCleanupAllowed(String job,CalibreSettings.Connection c) {
        var row=jdbc.queryForMap("SELECT * FROM calibre_jobs WHERE id=?",job);
        var owner=accounts.find(retryActor(job,(String)row.get("owner_id"),(String)row.get("owner_username")).id());
        var current=settings.connection();
        if(!"RUNNING".equals(row.get("state")) || owner==null || !owner.status().equals("ACTIVE") || owner.mustChangePassword() || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now()) || !owner.permissions().contains(Permission.CALIBRE_DELETE) || !destination(current).equals(row.get("destination")) || !current.equals(new CalibreSettings.Connection(c.url(),c.username(),c.password(),current.library()))) throw new UserInputException("El trabajo, la conexión o los permisos cambiaron antes del borrado");
    }
    void removeObserved(CalibreSettings.Connection connection,long id) {catalog.removeObserved(connection,id);}
    private static String safeMessage(RuntimeException ex,String fallback) {
        String message=ex instanceof UserInputException?ex.getMessage():OperationDiagnostics.reason(ex,fallback);
        return message==null?fallback:message.substring(0,Math.min(1024,message.length()));
    }
    private void complete(String id,EventContext.Actor actor) {
        tx.executeWithoutResult(s -> { var j=job(id);if(j.pending()>0 || j.uncertain()>0) return;if(jdbc.update("UPDATE calibre_jobs SET state='COMPLETED',updated_at=? WHERE id=? AND state='RUNNING'",Instant.now().toString(),id)>0) event(actor,id,"CALIBRE_SEND",j.failed()>0?EventJournal.Outcome.PARTIAL:EventJournal.Outcome.SUCCEEDED,Map.of("library",j.library(),"total",j.total(),"accepted",j.accepted(),"skipped",j.skipped(),"failed",j.failed(),"uncertain",j.uncertain())); });
    }
    private void process(String job,Map<String,Object> config,EventContext.Actor actor,CalibreSettings.Connection connection,Item item) {
        Path temp=null;boolean sending=false;Long remoteId=item.calibreId()==null?acceptedRemoteIds.get(item.id()):item.calibreId();String metadataNotice="";
        try {
            if(item.state().equals("UNCERTAIN")) {
                var observed=expectedIdentity(item.id());
                remoteId=remoteId==null && item.identityComplete() && observed.eplId()!=null && observed.revision()!=null ? client.findRevision(connection,observed.eplId(),observed.revision()) : remoteId;
                if(remoteId==null) {pause(job,actor,"El envío anterior no está confirmado. Comprueba Calibre; no se repetirá automáticamente");return;}
                publish(connection,client.book(connection,remoteId),item);finish(job,actor,item,"ACCEPTED",remoteId,"Envío anterior verificado"+revisionNotice(item,observed));return;
            }
            temp=Files.createTempFile("eplsync-calibre-send-",".epub");
            try(var input=files.download(item.recordId(),item.index());var output=Files.newOutputStream(temp)) {
                if(input.size()!=item.size() || !input.name().equals(item.name())) throw new UserInputException("El archivo de origen cambió; prepara de nuevo el envío");
                byte[] buffer=new byte[65536];long total=0,deadline=System.nanoTime()+Duration.ofMinutes(1).toNanos();int count;
                while((count=input.stream().read(buffer))!=-1) { total+=count;if(total>268435456 || total>item.size() || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw new UserInputException("El EPUB supera el tamaño o tiempo permitido");output.write(buffer,0,count); }
                if(total!=item.size()) throw new UserInputException("El archivo de origen cambió durante la lectura");
            }
            var identity=CalibreEpubMetadata.read(temp);
            if(identity.eplId()!=null && identity.eplId()!=item.eplId() || identity.eplId()==null && identity.revision()!=null && identity.revision().compareTo(new BigDecimal(item.revision()))!=0) {
                String expected="EPL ID "+item.eplId()+", revisión "+item.revision();
                String found="EPL ID "+Objects.toString(identity.eplId(),"ausente")+", revisión "+(identity.revision()==null?"ausente":identity.revision().stripTrailingZeros().toPlainString());
                log.warn("Identidad del EPUB no coincidente; no se ha enviado a Calibre: trabajo={}, elemento={}, registro={}, esperado=[{}], encontrado=[{}]",job,item.id(),item.recordId(),expected,found);
                throw new UserInputException("Los identificadores internos del EPUB no coinciden. Esperado: "+expected+". Encontrado: "+found+". No se ha enviado a Calibre; comprueba los identificadores antes de reintentar.");
            }
            metadataNotice=identity.eplId()==null && identity.revision()==null ? "EPUB enviado sin EPL ID ni revisión" : identity.eplId()==null ? "EPUB enviado sin EPL ID" : identity.revision()==null ? "EPUB enviado sin revisión" : "";
            if(!revisionNotice(item,identity).isEmpty()) metadataNotice=(metadataNotice.isEmpty()?"EPUB enviado y verificado":metadataNotice)+revisionNotice(item,identity);
            jdbc.update("UPDATE calibre_job_items SET epub_identity=? WHERE id=? AND state='PENDING'",EPUB_IDENTITIES.writeValueAsString(identity),item.id());
            remoteId=identity.eplId()!=null && identity.revision()!=null ? client.findRevision(connection,identity.eplId(),identity.revision()) : null;
            if(remoteId!=null && client.hasEpub(connection,remoteId)) {publish(connection,client.book(connection,remoteId),item);finish(job,actor,item,"SKIPPED",remoteId,"Esta revisión ya está en Calibre"+revisionNotice(item,identity));return;}
            remoteId=null;
            if(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE j.destination=? AND j.library=? AND i.epl_id=? AND CAST(coalesce(json_extract(i.epub_identity,'$.revision'),i.revision) AS REAL)=? AND i.state IN ('SENDING','UNCERTAIN') AND i.id<>? AND NOT EXISTS(SELECT 1 FROM job_retry_items r WHERE r.kind='calibre' AND r.source_item_id=i.id)",Long.class,config.get("destination"),connection.library(),item.eplId(),identity.revision()==null?Double.parseDouble(item.revision()):identity.revision().doubleValue(),item.id())>0) {pause(job,actor,"Existe un envío de esta revisión pendiente de confirmación");return;}
            var current=settings.connection();var owner=accounts.find(actor.id());
            if(!destination(current).equals(config.get("destination")) || !source().equals(config.get("source")) || owner==null || !owner.status().equals("ACTIVE") || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now()) || owner.mustChangePassword() || !owner.permissions().containsAll(Set.of(Permission.CALIBRE_SEND,Permission.DOWNLOADS_FILES_READ))) {pause(job,actor,"La configuración o los permisos cambiaron antes del envío");return;}
            connection=new CalibreSettings.Connection(current.url(),current.username(),current.password(),connection.library());
            Boolean claimed=tx.execute(s -> {
                if(!job(job).state().equals("RUNNING")) return false;
                boolean acquired=jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=? WHERE id=? AND state='PENDING'",identity.eplId()!=null && identity.revision()!=null,item.id())==1;
                return acquired;
            });if(!Boolean.TRUE.equals(claimed)) return;
            sending=true;
            remoteId=client.uploadEpub(connection,temp,item.name(),item.id());
            acceptedRemoteIds.put(item.id(),remoteId);
            jdbc.update("UPDATE calibre_job_items SET calibre_id=? WHERE id=?",remoteId,item.id());
            publish(connection,client.book(connection,remoteId),item);
            finish(job,actor,item,"ACCEPTED",remoteId,metadataNotice.isEmpty()?"EPUB enviado y verificado":metadataNotice);
        } catch(Exception ex) {
            if(!sending && item.state().equals("PENDING") && ex instanceof com.rlibanez.eplsync.exception.TorrentBusyException) {postponeDownloads(job);return;}
            if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) {
                log.debug("Resultado de Calibre pendiente de persistencia: elemento={}",item.id());return;
            }
            log.warn("Operación de Calibre fallida: trabajo={}, elemento={}, EPL ID={}, fase={}, causa={}",job,item.id(),item.eplId(),sending?"ENVIO_O_VERIFICACION":item.state(),OperationDiagnostics.technical(ex));
            if((sending && !(ex instanceof org.springframework.web.server.ResponseStatusException)) || ex instanceof CalibreClient.UnconfirmedUpload) {
                finish(job,actor,item,"UNCERTAIN",remoteId,"No se ha confirmado el envío; no se repetirá automáticamente"+(metadataNotice.isEmpty()?"":". "+metadataNotice));pause(job,actor,"Hay un envío pendiente de verificación");
            } else if(sending && remoteId!=null || item.state().equals("UNCERTAIN")) {
                finish(job,actor,item,"UNCERTAIN",remoteId,"No se pudieron verificar los metadatos o el EPUB de Calibre");pause(job,actor,"Hay un envío pendiente de verificación");
            } else if(ex instanceof org.springframework.web.server.ResponseStatusException status && status.getStatusCode().value()!=404) {
                jdbc.update("UPDATE calibre_job_items SET message=? WHERE id=?", "Calibre respondió con HTTP "+status.getStatusCode().value()+"; revisa conexión y permisos",item.id());
                pause(job,actor,sending ? "Calibre rechazó el envío; revisa permisos de escritura y conexión" : "No se puede consultar Calibre; revisa la conexión");
                if(sending) jdbc.update("UPDATE calibre_job_items SET state=CASE WHEN (SELECT state FROM calibre_jobs WHERE id=?)='CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END WHERE id=?",job,item.id());
            } else finish(job,actor,item,"FAILED",remoteId,ex instanceof UserInputException || ex instanceof com.rlibanez.eplsync.exception.TorrentOperationException ? ex.getMessage() : OperationDiagnostics.reason(ex,"No se pudo preparar o enviar el EPUB; consulta el diagnóstico del servidor con el ID de este trabajo"));
        } finally {if(temp!=null) try { Files.deleteIfExists(temp); }catch(java.io.IOException ex) {log.warn("No se pudo eliminar un archivo temporal de envío a Calibre");}}
    }
    CalibreEpubMetadata.Identity expectedIdentity(String item) {
        var row=jdbc.queryForMap("SELECT epl_id,revision,epub_identity FROM calibre_job_items WHERE id=?",item);
        return storedIdentity(row);
    }
    static CalibreEpubMetadata.Identity storedIdentity(Map<String,Object> row) {
        var stored=row.get("epub_identity");
        if(stored!=null) return EPUB_IDENTITIES.readValue(stored.toString(),CalibreEpubMetadata.Identity.class);
        // Previously persisted uncertain uploads retain the original, stricter identity.
        return new CalibreEpubMetadata.Identity(((Number)row.get("epl_id")).longValue(),new BigDecimal(row.get("revision").toString()));
    }
    private static String revisionNotice(Item item,CalibreEpubMetadata.Identity identity) {
        return identity.revision()!=null && identity.revision().compareTo(new BigDecimal(item.revision()))!=0
            ? ". La revisión del EPUB (r"+identity.revision().stripTrailingZeros().toPlainString()+") difiere de la del registro de descarga (r"+item.revision()+")" : "";
    }
    private void publish(CalibreSettings.Connection connection,CalibreClient.Book book,Item item) {
        var observed=expectedIdentity(item.id());
        var revision=observed.revision()==null?new BigDecimal(item.revision()):observed.revision();
        if(book.eplId()!=null && !java.util.Objects.equals(book.eplId(),item.eplId()) || book.revision()!=null && book.revision().compareTo(revision)!=0) throw new UserInputException("Los identificadores del libro recibido por Calibre no coinciden; comprueba sus metadatos");
        if(!client.hasEpub(connection,book.calibreId())) throw new UserInputException("Calibre no confirma el formato EPUB del libro; el envío queda pendiente de verificación");
        catalog.observe(connection,book);
    }
    public static final String SIZE_WARNING="El tamaño del EPUB no coincide con el registrado por el cliente torrent. El archivo podría haber sido modificado.";
    private void finish(String job,EventContext.Actor actor,Item item,String state,Long calibreId,String message) {
        tx.executeWithoutResult(s -> {
            String previous=jdbc.queryForObject("SELECT message FROM calibre_job_items WHERE id=?",String.class,item.id());
            String detail=previous!=null && previous.contains(SIZE_WARNING) && !message.contains(SIZE_WARNING) ? message+". "+SIZE_WARNING : message;
            String next=state;
            if(Set.of("ACCEPTED","SKIPPED").contains(state) && !"KEEP".equals(jdbc.queryForObject("SELECT cleanup_policy FROM calibre_job_items WHERE id=?",String.class,item.id())) && !item.state().equals("CLEANUP_PENDING")) {
                if(job(job).state().equals("CANCELLED")) detail+=". Limpieza de revisiones anteriores cancelada";
                else {next="CLEANUP_PENDING";jdbc.update("UPDATE calibre_job_items SET completion_state=? WHERE id=?",state,item.id());detail+=". Limpieza de revisiones anteriores pendiente";}
            }
            jdbc.update("UPDATE calibre_job_items SET state=?,calibre_id=?,message=? WHERE id=?",next,calibreId,detail,item.id());
            jdbc.update("UPDATE calibre_jobs SET updated_at=? WHERE id=?",Instant.now().toString(),job);
            event(actor,item.id(),"CALIBRE_SEND_FILE",state.equals("UNCERTAIN")?EventJournal.Outcome.PAUSED:state.equals("FAILED")?EventJournal.Outcome.FAILED:EventJournal.Outcome.SUCCEEDED,Map.of("eplId",item.eplId(),"revision",item.revision(),"state",state,"reason",detail,"itemId",item.id(),"jobId",job));
        });
        acceptedRemoteIds.remove(item.id());
    }
}
