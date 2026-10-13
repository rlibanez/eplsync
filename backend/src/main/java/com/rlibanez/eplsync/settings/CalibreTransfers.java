package com.rlibanez.eplsync.settings;

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
    public record Prepared(List<CalibreClient.Library> libraries,String library,List<Candidate> files) {}
    public record Request(String library,List<FileChoice> files) {}
    public record Job(String id,String createdAt,String updatedAt,String username,String library,String state,String message,
        long total,long pending,long accepted,long skipped,long failed,long uncertain) {}
    public record Item(String id,String recordId,int index,String name,long size,long eplId,String revision,String state,Long calibreId,String message) {}
    public record Meta(int page,int size,long totalItems,long totalPages,boolean hasPrevious,boolean hasNext) {}
    public record Page<T>(List<T> items,Meta meta) {}
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
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> { var thread=new Thread(r,"calibre-transfers");thread.setDaemon(true);return thread; });
    @org.springframework.beans.factory.annotation.Value("${eplsync.calibre.transfers.worker-enabled:true}") private boolean enabled;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreTransfers.class);
    public CalibreTransfers(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,CalibreSettings settings,
            CalibreClient client,CalibreCatalogService catalog,EpubFiles files,DownloadRepository downloads,DownloadTrackingService tracking,
            TorrentProperties torrent,AccountStore accounts,EventJournal events) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.settings=settings;this.client=client;this.catalog=catalog;
        this.files=files;this.downloads=downloads;this.tracking=tracking;this.torrent=torrent;this.accounts=accounts;this.events=events;
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE calibre_job_items SET state='UNCERTAIN',message='El proceso se interrumpió; verifica el envío antes de continuar' WHERE state='SENDING'");
            jdbc.update("UPDATE calibre_jobs SET state='PAUSED',message='El proceso se interrumpió; reanuda para verificar los envíos' WHERE state='RUNNING' OR id IN (SELECT job_id FROM calibre_job_items WHERE state='UNCERTAIN') AND state='QUEUED'");
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
                if(file.size()>268435456 || candidates.size()>=1000) throw new UserInputException("Máximo 1000 EPUB de 256 MiB por selección");
                candidates.add(new Candidate(id,file.index(),file.name(),file.size(),row.getEplId(),row.getRevision(),file.expectedSize()));
            }
        }
        return new Prepared(check.libraries(),connection.library(),List.copyOf(candidates));
    }
    private static void validateIds(List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>1000 || ids.stream().anyMatch(id -> id==null || id.isBlank() || id.length()>64) || new HashSet<>(ids).size()!=ids.size())
            throw new UserInputException("Selecciona entre 1 y 1000 registros únicos");
    }
    public Job create(Request request) {
        requireSend();
        if(request==null || request.library()==null || request.library().isBlank() || request.library().length()>256 || request.files()==null || request.files().isEmpty() || request.files().size()>1000 || request.files().stream().anyMatch(f -> f==null || f.index()<0)) throw new UserInputException("Selecciona entre 1 y 1000 EPUB");
        if(new HashSet<>(request.files()).size()!=request.files().size()) throw new UserInputException("No selecciones el mismo EPUB dos veces");
        lifecycle.readLock().lock();
        try {
            var connection=settings.connection();String source=source();
            var prepared=prepare(request.files().stream().map(FileChoice::recordId).distinct().toList());
            if(prepared.libraries().stream().noneMatch(l -> l.id().equals(request.library()))) throw new UserInputException("La biblioteca de destino no está disponible");
            var byFile=new HashMap<FileChoice,Candidate>();prepared.files().forEach(f -> byFile.put(new FileChoice(f.recordId(),f.index()),f));
            var selected=request.files().stream().map(f -> {var candidate=byFile.get(f);if(candidate==null) throw new UserInputException("El EPUB seleccionado ya no está disponible");return candidate;}).toList();
            var actor=EventContext.actor();String id=UUID.randomUUID().toString(),now=Instant.now().toString();
            tx.executeWithoutResult(s -> {
                if(!connection.equals(settings.connection()) || !source.equals(source())) throw new UserInputException("La configuración cambió; vuelve a preparar el envío");
                if(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE state IN ('PENDING','SENDING','UNCERTAIN')",Long.class)+selected.size()>10000)
                    throw new UserInputException("Hay demasiados envíos pendientes a Calibre; espera a que finalicen");
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
    private static final String SUMMARY="SELECT j.*,count(i.id) AS total,sum(CASE WHEN i.state IN ('PENDING','SENDING') THEN 1 ELSE 0 END) AS pending,sum(CASE WHEN i.state='ACCEPTED' THEN 1 ELSE 0 END) AS accepted,sum(CASE WHEN i.state='SKIPPED' THEN 1 ELSE 0 END) AS skipped,sum(CASE WHEN i.state='FAILED' THEN 1 ELSE 0 END) AS failed,sum(CASE WHEN i.state='UNCERTAIN' THEN 1 ELSE 0 END) AS uncertain FROM calibre_jobs j LEFT JOIN calibre_job_items i ON i.job_id=j.id";
    private static Job mapJob(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Job(r.getString("id"),r.getString("created_at"),r.getString("updated_at"),r.getString("owner_username"),r.getString("library"),r.getString("state"),r.getString("message"),r.getLong("total"),r.getLong("pending"),r.getLong("accepted"),r.getLong("skipped"),r.getLong("failed"),r.getLong("uncertain")); }
    private static Item mapItem(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Item(r.getString("id"),r.getString("record_id"),r.getInt("file_index"),r.getString("name"),r.getLong("size"),r.getLong("epl_id"),r.getString("revision"),r.getString("state"),r.getObject("calibre_id")==null ? null : r.getLong("calibre_id"),r.getString("message")); }
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
        paging(page,size);if(!state.isEmpty() && !Set.of("QUEUED","RUNNING","PAUSED","COMPLETED","CANCELLED").contains(state)) throw new UserInputException("Estado de trabajo inválido");
        String where=state.isEmpty() ? "" : " WHERE j.state=?";var args=new ArrayList<Object>();if(!state.isEmpty()) args.add(state);
        long total=jdbc.queryForObject("SELECT count(*) FROM calibre_jobs j"+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query(SUMMARY+where+" GROUP BY j.id ORDER BY "+order(sort,Map.of("createdAt","j.created_at","username","j.owner_username COLLATE EPL_TEXT","library","j.library COLLATE EPL_TEXT","state","j.state","total","total","accepted","accepted","failed","failed"))+",j.id LIMIT ? OFFSET ?",CalibreTransfers::mapJob,args.toArray()),meta(page,size,total));
    }
    public Page<Item> items(String id,int page,int size,List<String> sort) {
        paging(page,size);job(id);long total=jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=?",Long.class,id);
        return new Page<>(jdbc.query("SELECT * FROM calibre_job_items WHERE job_id=? ORDER BY "+order(sort,Map.of("position","position","eplId","epl_id","revision","CAST(revision AS REAL)","name","name COLLATE EPL_TEXT","state","state","calibreId","calibre_id","message","message COLLATE EPL_TEXT"))+",position LIMIT ? OFFSET ?",CalibreTransfers::mapItem,id,size,(long)page*size),meta(page,size,total));
    }
    public Job control(String id,String command) {
        Permission.require(Permission.CALIBRE_JOBS_MANAGE);
        if(!Set.of("pause","resume","cancel").contains(command)) throw new UserInputException("Control de trabajo inválido");
        tx.executeWithoutResult(s -> {
            var j=job(id);if(j.state().equals("COMPLETED") || j.state().equals("CANCELLED") && !(command.equals("resume") && j.uncertain()>0)) throw new UserInputException("El trabajo ya ha finalizado");
            String next=command.equals("pause") ? "PAUSED" : command.equals("resume") ? "QUEUED" : "CANCELLED";
            jdbc.update("UPDATE calibre_jobs SET state=?,message=NULL,updated_at=? WHERE id=?",next,Instant.now().toString(),id);
            if(command.equals("cancel")) jdbc.update("UPDATE calibre_job_items SET state='CANCELLED' WHERE job_id=? AND state='PENDING'",id);
            event(EventContext.actor(),id,"CALIBRE_JOB_CONTROL",command.equals("pause")?EventJournal.Outcome.PAUSED:command.equals("resume")?EventJournal.Outcome.RESUMED:EventJournal.Outcome.CANCELLED,Map.of("command",command));
        });return job(id);
    }
    private void event(EventContext.Actor actor,String id,String action,EventJournal.Outcome outcome,Map<String,?> details) {
        var level=action.equals("CALIBRE_SEND") && Set.of(EventJournal.Outcome.STARTED,EventJournal.Outcome.SUCCEEDED,EventJournal.Outcome.PARTIAL).contains(outcome) ? log.atInfo() : log.atDebug();
        level.log("Trabajo de Calibre: operación={}, acción={}, resultado={}, userId={}, usuario={}, detalles={}",id,action,outcome,actor.id(),actor.username(),details);
        if(action.equals("CALIBRE_SEND_FILE")) return;
        if(action.equals("CALIBRE_JOB_CONTROL")) {
            String operation=UUID.randomUUID().toString();var context=new LinkedHashMap<String,Object>(details);context.put("jobId",id);
            events.recordAs(actor,EventJournal.Category.JOB,action,EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,operation,context);
            events.recordAs(actor,EventJournal.Category.JOB,action,outcome,EventContext.Origin.MANUAL,operation,context);
        } else events.recordAs(actor,EventJournal.Category.JOB,action,outcome,EventContext.Origin.MANUAL,id,details);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void schedule() {
        if(enabled && busy.compareAndSet(false,true)) executor.submit(() -> { try { cycle(); } catch(Exception ex) { log.error("Fallo en el coordinador de envíos a Calibre",ex); } finally {busy.set(false);} });
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void retention() {
        if(!lifecycle.writeLock().tryLock()) return;
        try { jdbc.update("DELETE FROM calibre_jobs WHERE id IN (SELECT id FROM calibre_jobs WHERE state IN ('COMPLETED','CANCELLED') AND updated_at<? AND NOT EXISTS (SELECT 1 FROM calibre_job_items i WHERE i.job_id=calibre_jobs.id AND i.state IN ('SENDING','UNCERTAIN')) ORDER BY updated_at LIMIT 100)",Instant.now().minus(Duration.ofDays(90)).toString());
            try(var abandoned=Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),"eplsync-calibre-send-*.epub")) {
                for(var path:abandoned) if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(Duration.ofHours(1)))) Files.deleteIfExists(path);
            } catch(java.io.IOException ex) {log.warn("No se pudo revisar la limpieza de EPUB temporales de Calibre");}
        }
        finally {lifecycle.writeLock().unlock();}
    }
    @jakarta.annotation.PreDestroy void close() { enabled=false;executor.shutdown();try { if(!executor.awaitTermination(65,TimeUnit.SECONDS)) executor.shutdownNow(); }catch(InterruptedException ex) {executor.shutdownNow();Thread.currentThread().interrupt();} }
    public <T> T exclusive(java.util.function.Supplier<T> work) {
        if(!lifecycle.writeLock().tryLock()) throw new UserInputException("Hay un envío a Calibre en curso; espera antes de reiniciar la base de datos");
        try { return work.get(); } finally { lifecycle.writeLock().unlock(); }
    }
    private void pause(String id,EventContext.Actor actor,String message) {
        tx.executeWithoutResult(s -> { if(jdbc.update("UPDATE calibre_jobs SET state='PAUSED',message=?,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')",message,Instant.now().toString(),id)>0) event(actor,id,"CALIBRE_SEND",EventJournal.Outcome.PAUSED,Map.of("reason",message)); });
    }
    public void cycle() {
        lifecycle.readLock().lock();
        try {
            var queued=jdbc.queryForList("SELECT * FROM calibre_jobs WHERE state IN ('QUEUED','RUNNING') ORDER BY created_at,id LIMIT 1");
            if(queued.isEmpty()) return;var j=queued.getFirst();String id=(String)j.get("id");
            var actor=new EventContext.Actor((String)j.get("owner_id"),(String)j.get("owner_username"),"USER");
            var owner=actor.id()==null ? null : accounts.find(actor.id());
            if(owner==null || !owner.status().equals("ACTIVE") || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now()) || owner.mustChangePassword() || !owner.permissions().containsAll(Set.of(Permission.CALIBRE_SEND,Permission.DOWNLOADS_FILES_READ))) {pause(id,actor,"El usuario ya no está autorizado para enviar archivos a Calibre");return;}
            CalibreSettings.Connection connection;
            try { var current=settings.connection();if(!destination(current).equals(j.get("destination")) || !source().equals(j.get("source"))) {pause(id,actor,"La conexión o las carpetas cambiaron; restaura el destino antes de reanudar");return;}
                connection=new CalibreSettings.Connection(current.url(),current.username(),current.password(),(String)j.get("library"));
            } catch(RuntimeException ex) {pause(id,actor,"Revisa la configuración de Calibre antes de continuar");return;}
            jdbc.update("UPDATE calibre_jobs SET state='RUNNING',updated_at=? WHERE id=? AND state='QUEUED'",Instant.now().toString(),id);
            var pending=jdbc.query("SELECT * FROM calibre_job_items WHERE job_id=? AND state IN ('PENDING','UNCERTAIN') ORDER BY CASE WHEN state='UNCERTAIN' THEN 0 ELSE 1 END,position LIMIT 1",CalibreTransfers::mapItem,id);
            if(pending.isEmpty()) { complete(id,actor);return; }
            var item=pending.getFirst();
            if(item.state().equals("PENDING")) event(actor,item.id(),"CALIBRE_SEND_FILE",EventJournal.Outcome.STARTED,Map.of("jobId",id,"eplId",item.eplId(),"revision",item.revision()));
            process(id,j,actor,connection,item);
            if(job(id).pending()==0 && job(id).uncertain()==0) complete(id,actor);
        } finally { lifecycle.readLock().unlock(); }
    }
    private void complete(String id,EventContext.Actor actor) {
        tx.executeWithoutResult(s -> { var j=job(id);if(jdbc.update("UPDATE calibre_jobs SET state='COMPLETED',updated_at=? WHERE id=? AND state='RUNNING'",Instant.now().toString(),id)>0) event(actor,id,"CALIBRE_SEND",j.failed()>0?EventJournal.Outcome.PARTIAL:EventJournal.Outcome.SUCCEEDED,Map.of("library",j.library(),"total",j.total(),"accepted",j.accepted(),"skipped",j.skipped(),"failed",j.failed(),"uncertain",j.uncertain())); });
    }
    private void process(String job,Map<String,Object> config,EventContext.Actor actor,CalibreSettings.Connection connection,Item item) {
        Path temp=null;boolean sending=false;Long remoteId=item.calibreId();String metadataNotice="";
        try {
            if(item.state().equals("UNCERTAIN")) {
                remoteId=remoteId==null && (item.message()==null || !item.message().contains("sin ")) ? client.findRevision(connection,item.eplId(),new BigDecimal(item.revision())) : remoteId;
                if(remoteId==null) {pause(job,actor,"El envío anterior no está confirmado. Comprueba Calibre; no se repetirá automáticamente");return;}
                publish(connection,client.book(connection,remoteId),item);finish(job,actor,item,"ACCEPTED",remoteId,"Envío anterior verificado");return;
            }
            temp=Files.createTempFile("eplsync-calibre-send-",".epub");
            try(var input=files.download(item.recordId(),item.index());var output=Files.newOutputStream(temp)) {
                if(input.size()!=item.size() || !input.name().equals(item.name())) throw new UserInputException("El archivo de origen cambió; prepara de nuevo el envío");
                byte[] buffer=new byte[65536];long total=0,deadline=System.nanoTime()+Duration.ofMinutes(1).toNanos();int count;
                while((count=input.stream().read(buffer))!=-1) { total+=count;if(total>268435456 || total>item.size() || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw new UserInputException("El EPUB supera el tamaño o tiempo permitido");output.write(buffer,0,count); }
                if(total!=item.size()) throw new UserInputException("El archivo de origen cambió durante la lectura");
            }
            var identity=CalibreEpubMetadata.read(temp);
            if(identity.eplId()!=null && identity.eplId()!=item.eplId() || identity.revision()!=null && identity.revision().compareTo(new BigDecimal(item.revision()))!=0) throw new UserInputException("El EPL ID o la revisión del EPUB no coinciden con el registro de descarga");
            metadataNotice=identity.eplId()==null && identity.revision()==null ? "EPUB enviado sin EPL ID ni revisión" : identity.eplId()==null ? "EPUB enviado sin EPL ID" : identity.revision()==null ? "EPUB enviado sin revisión" : "";
            remoteId=identity.eplId()!=null && identity.revision()!=null ? client.findRevision(connection,identity.eplId(),identity.revision()) : null;
            if(remoteId!=null) {publish(connection,client.book(connection,remoteId),item);finish(job,actor,item,"SKIPPED",remoteId,"Esta revisión ya está en Calibre");return;}
            if(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE j.destination=? AND j.library=? AND i.epl_id=? AND i.revision=? AND i.state IN ('SENDING','UNCERTAIN') AND i.id<>?",Long.class,config.get("destination"),connection.library(),item.eplId(),item.revision(),item.id())>0) {pause(job,actor,"Existe un envío de esta revisión pendiente de confirmación");return;}
            var current=settings.connection();var owner=accounts.find(actor.id());
            if(!destination(current).equals(config.get("destination")) || !source().equals(config.get("source")) || owner==null || !owner.status().equals("ACTIVE") || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now()) || owner.mustChangePassword() || !owner.permissions().containsAll(Set.of(Permission.CALIBRE_SEND,Permission.DOWNLOADS_FILES_READ))) {pause(job,actor,"La configuración o los permisos cambiaron antes del envío");return;}
            connection=new CalibreSettings.Connection(current.url(),current.username(),current.password(),connection.library());
            Boolean claimed=tx.execute(s -> {
                if(!job(job).state().equals("RUNNING")) return false;
                boolean acquired=jdbc.update("UPDATE calibre_job_items SET state='SENDING' WHERE id=? AND state='PENDING'",item.id())==1;
                return acquired;
            });if(!Boolean.TRUE.equals(claimed)) return;
            sending=true;
            remoteId=client.uploadEpub(connection,temp,item.name(),item.id());
            jdbc.update("UPDATE calibre_job_items SET calibre_id=? WHERE id=?",remoteId,item.id());
            publish(connection,client.book(connection,remoteId),item);
            finish(job,actor,item,"ACCEPTED",remoteId,metadataNotice.isEmpty()?"EPUB enviado y verificado":metadataNotice);
        } catch(Exception ex) {
            if((sending && !(ex instanceof org.springframework.web.server.ResponseStatusException)) || ex instanceof CalibreClient.UnconfirmedUpload) {
                finish(job,actor,item,"UNCERTAIN",remoteId,"No se ha confirmado el envío; no se repetirá automáticamente"+(metadataNotice.isEmpty()?"":". "+metadataNotice));pause(job,actor,"Hay un envío pendiente de verificación");
            } else if(sending && remoteId!=null || item.state().equals("UNCERTAIN")) {
                finish(job,actor,item,"UNCERTAIN",remoteId,"No se pudieron verificar los metadatos de Calibre");pause(job,actor,"Hay un envío pendiente de verificación");
            } else if(ex instanceof org.springframework.web.server.ResponseStatusException status && status.getStatusCode().value()!=404) {
                pause(job,actor,sending ? "Calibre rechazó el envío; revisa permisos de escritura y conexión" : "No se puede consultar Calibre; revisa la conexión");
                if(sending) jdbc.update("UPDATE calibre_job_items SET state=CASE WHEN (SELECT state FROM calibre_jobs WHERE id=?)='CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END WHERE id=?",job,item.id());
            } else finish(job,actor,item,"FAILED",remoteId,ex instanceof UserInputException || ex instanceof com.rlibanez.eplsync.exception.TorrentOperationException ? ex.getMessage() : "No se pudo preparar o enviar el EPUB");
        } finally {if(temp!=null) try { Files.deleteIfExists(temp); }catch(java.io.IOException ex) {log.warn("No se pudo eliminar un archivo temporal de envío a Calibre");}}
    }
    private void publish(CalibreSettings.Connection connection,CalibreClient.Book book,Item item) {
        if(book.eplId()!=null && !java.util.Objects.equals(book.eplId(),item.eplId()) || book.revision()!=null && book.revision().compareTo(new BigDecimal(item.revision()))!=0) throw new UserInputException("Los identificadores del libro recibido por Calibre no coinciden; comprueba sus metadatos");
        catalog.observe(connection,book);
    }
    private static final String SIZE_WARNING="El tamaño del EPUB no coincide con el registrado por el cliente torrent. El archivo podría haber sido modificado.";
    private void finish(String job,EventContext.Actor actor,Item item,String state,Long calibreId,String message) {
        tx.executeWithoutResult(s -> {
            String previous=jdbc.queryForObject("SELECT message FROM calibre_job_items WHERE id=?",String.class,item.id());
            String detail=previous!=null && previous.contains(SIZE_WARNING) && !message.contains(SIZE_WARNING) ? message+". "+SIZE_WARNING : message;
            jdbc.update("UPDATE calibre_job_items SET state=?,calibre_id=?,message=? WHERE id=?",state,calibreId,detail,item.id());
            jdbc.update("UPDATE calibre_jobs SET updated_at=? WHERE id=?",Instant.now().toString(),job);
            event(actor,item.id(),"CALIBRE_SEND_FILE",state.equals("UNCERTAIN")?EventJournal.Outcome.PAUSED:state.equals("FAILED")?EventJournal.Outcome.FAILED:EventJournal.Outcome.SUCCEEDED,Map.of("eplId",item.eplId(),"revision",item.revision(),"state",state,"reason",detail,"itemId",item.id(),"jobId",job));
        });
    }
}
