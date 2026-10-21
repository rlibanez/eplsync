package com.rlibanez.eplsync.settings;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import tools.jackson.databind.json.JsonMapper;

/** Read-only preview and bounded, atomic creation batches. No staged job can send anything. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class CalibrePreparationOperations {
    public record PreviewRequest(String id,CatalogCalibreTransfers.Selection selection) {}
    public record Progress(String id,String kind,String state,String phase,long processed,long total,long startedAt,String message,Object result) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CatalogCalibreTransfers catalog;
    private final CalibreSettings settings;
    private final DownloadTrackingService tracking;
    private final AccountStore accounts;
    private final SessionAccess sessions;
    private final MaintenanceGate maintenance;
    private final EventJournal events;
    private final JsonMapper json=JsonMapper.builder().build();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final com.rlibanez.eplsync.config.WorkerActivity.Probe idle=new com.rlibanez.eplsync.config.WorkerActivity.Probe();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("calibre-preparation").factory());
    private final Map<String,String> logged=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Value("${eplsync.calibre.transfers.worker-enabled:true}") private boolean enabled;
    private volatile boolean closed;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibrePreparationOperations.class);
    public CalibrePreparationOperations(JdbcTemplate jdbc,PlatformTransactionManager manager,CatalogCalibreTransfers catalog,CalibreSettings settings,DownloadTrackingService tracking,AccountStore accounts,SessionAccess sessions,MaintenanceGate maintenance,EventJournal events) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.catalog=catalog;this.settings=settings;this.tracking=tracking;this.accounts=accounts;this.sessions=sessions;this.maintenance=maintenance;this.events=events;
    }
    @jakarta.annotation.PostConstruct void recover() {jdbc.update("UPDATE calibre_preparation_operations SET state='QUEUED',phase='RECOVERING' WHERE state='RUNNING'");}
    private void require() {Permission.require(Permission.CATALOG_READ);Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);}
    private String context() {return settings.updateSearchContext()+":"+tracking.instanceId();}
    private String digest(String input) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public Progress prepare(PreviewRequest input) {
        if(input==null || input.selection()==null || input.selection().filters()==null) throw new UserInputException("Selecciona libros del catálogo");
        return begin(input.id(),"PREPARE",json.writeValueAsString(input.selection()));
    }
    public Progress send(CatalogCalibreTransfers.Request input) {
        if(input==null || input.books()==null || input.books().isEmpty() || input.files()==null || !input.files().isEmpty() || input.pending()==null) throw new UserInputException("Prepara de nuevo la selección grande del catálogo");
        return begin(input.id(),"SEND",json.writeValueAsString(input));
    }
    private Progress begin(String id,String kind,String input) {
        require();if(id==null || !id.matches("[a-zA-Z0-9-]{1,100}")) throw new UserInputException("Solicitud de preparación inválida");
        var owner=sessions.current();String signature=digest(kind+":"+input);
        tx.executeWithoutResult(s->{
            var existing=jdbc.queryForList("SELECT owner_id,signature FROM calibre_preparation_operations WHERE id=?",id);
            if(!existing.isEmpty()) {if(!owner.id().equals(existing.getFirst().get("owner_id")) || !signature.equals(existing.getFirst().get("signature"))) throw new UserInputException("La solicitud pertenece a otra selección");return;}
            if(jdbc.queryForObject("SELECT count(*) FROM calibre_preparation_operations WHERE owner_id=? AND state IN ('QUEUED','RUNNING')",Long.class,owner.id())>=5) throw new UserInputException("Hay cinco preparaciones pendientes; espera a que terminen");
            long now=Instant.now().toEpochMilli();
            jdbc.update("INSERT INTO calibre_preparation_operations(id,owner_id,owner_username,kind,state,phase,context,input,signature,created_at,updated_at) VALUES(?,?,?,?,'QUEUED','QUEUED',?,?,?,?,?)",id,owner.id(),owner.username(),kind,context(),input,signature,now,now);
        });return status(id);
    }
    private Map<String,Object> row(String id) {return jdbc.queryForList("SELECT * FROM calibre_preparation_operations WHERE id=?",id).stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));}
    private long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    public Progress status(String id) {
        require();var row=jdbc.queryForList("SELECT id,owner_id,kind,state,phase,processed,total,created_at,message,result FROM calibre_preparation_operations WHERE id=?",id).stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));if(!sessions.current().id().equals(row.get("owner_id"))) throw new org.springframework.security.access.AccessDeniedException("La preparación pertenece a otra cuenta");
        return new Progress(id,(String)row.get("kind"),(String)row.get("state"),(String)row.get("phase"),number(row,"processed"),number(row,"total"),number(row,"created_at"),(String)row.get("message"),"COMPLETED".equals(row.get("state")) && row.get("result")!=null?json.readTree((String)row.get("result")):null);
    }
    private static final class PreparationCancelled extends RuntimeException {private static final long serialVersionUID=1L;}
    private void requireActive(String id) {if("CANCELLED".equals(row(id).get("state"))) throw new PreparationCancelled();}
    public Progress cancel(String id) {
        require();String owner=sessions.current().id();
        tx.executeWithoutResult(status->{
            var current=row(id);if(!owner.equals(current.get("owner_id"))) throw new org.springframework.security.access.AccessDeniedException("La preparación pertenece a otra cuenta");
            if(!List.of("QUEUED","RUNNING").contains(current.get("state"))) return;
            deleteStaged(current);
            jdbc.update("UPDATE calibre_preparation_operations SET state='CANCELLED',phase='CANCELLED',input='{}',calibre_job_id=NULL,download_job_id=NULL,message='Preparación cancelada; no se han enviado archivos ni torrents',updated_at=? WHERE id=?",Instant.now().toEpochMilli(),id);
            logged.remove(id);log.info("Preparación de envío a Calibre cancelada: operación={}",id);
        });return status(id);
    }
    private void progress(String id,String phase,long processed,long total) {
        if(jdbc.update("UPDATE calibre_preparation_operations SET phase=?,processed=?,total=?,updated_at=?,message=NULL WHERE id=? AND state='RUNNING'",phase,processed,total,Instant.now().toEpochMilli(),id)==0) {requireActive(id);return;}
        String checkpoint=phase+":"+(total>0?Math.min(10,processed*10/total):0);
        if(!checkpoint.equals(logged.put(id,checkpoint)) && (phase.equals("CREATING_JOBS") || total>0 && processed>=total)) {
            var entry=total>0 && processed>=total ? log.atInfo() : log.atDebug();
            entry.log("Preparación de envío a Calibre: operación={}, fase={}, procesados={}, total={}",id,phase,processed,total);
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000) public void tick() {
        if(enabled && !closed && idle.ready() && busy.compareAndSet(false,true)) executor.execute(()->{try {cycle();}finally {busy.set(false);}});
    }
    public void cycle() {
        if(closed || !maintenance.enter(false)) return;
        var previous=SecurityContextHolder.getContext();String id=null;long probe=idle.snapshot();
        try {
            var queue=jdbc.queryForList("SELECT id,owner_id FROM calibre_preparation_operations WHERE state IN ('QUEUED','RUNNING') ORDER BY updated_at,created_at,id LIMIT 1");
            if(queue.isEmpty()) {idle.empty(probe);return;}
            id=(String)queue.getFirst().get("id");String selected=id;var owner=accounts.find((String)queue.getFirst().get("owner_id"));
            if(owner==null || !owner.status().equals("ACTIVE") || owner.mustChangePassword() || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now())) throw new UserInputException("El propietario ya no está autorizado");
            var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,owner.permissions().stream().map(p->new SimpleGrantedAuthority(p.name())).toList()));SecurityContextHolder.setContext(security);require();
            var row=row(id);if(!Objects.equals(context(),row.get("context"))) throw new UserInputException("La conexión cambió; vuelve a preparar el envío");
            if(jdbc.update("UPDATE calibre_preparation_operations SET state='RUNNING' WHERE id=? AND state IN ('QUEUED','RUNNING')",id)==0) throw new PreparationCancelled();
            if(row.get("kind").equals("PREPARE")) {
                var input=json.readValue((String)row.get("input"),CatalogCalibreTransfers.Selection.class);
                var result=CalibrePreparationContext.run((phase,done,total)->progress(selected,phase,done,total),false,()->catalog.prepare(input));
                if(jdbc.update("UPDATE calibre_preparation_operations SET state='COMPLETED',input='{}',phase='READY',processed=?,total=?,result=?,updated_at=? WHERE id=? AND state='RUNNING'",result.books().size(),result.books().size(),json.writeValueAsString(result),Instant.now().toEpochMilli(),id)==0) throw new PreparationCancelled();
                log.info("Previsualización de Calibre preparada: operación={}, libros={}, disponibles={}, pendientes={}",id,result.books().size(),result.files().size()+result.deferred().size(),result.missing().size());
                logged.remove(id);
            } else {
                var request=json.readValue((String)row.get("input"),CatalogCalibreTransfers.Request.class);
                int cursor=Math.toIntExact(number(row,"cursor"));
                if(cursor==0) {
                    var existing=catalog.existing(request);
                    if(existing!=null) {complete(id,existing.id());return;}
                    if(request.books().stream().anyMatch(Objects::isNull) || new HashSet<>(request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).toList()).size()!=request.books().size() || request.pending().stream().anyMatch(Objects::isNull) || new HashSet<>(request.pending()).size()!=request.pending().size() || !request.books().stream().map(book -> Objects.requireNonNull(book).eplId()).collect(java.util.stream.Collectors.toSet()).containsAll(request.pending())) throw new UserInputException("Selección de libros pendientes inválida");
                }
                if(cursor>=request.books().size()) {activate(id,request);return;}
                int end=Math.min(cursor+500,request.books().size());var books=request.books().subList(cursor,end);var ids=books.stream().map(book -> Objects.requireNonNull(book).eplId()).collect(java.util.stream.Collectors.toSet());
                var batch=new CatalogCalibreTransfers.Request("stage-"+digest(id).substring(0,32)+"-"+cursor,request.library(),books,List.of(),request.download(),request.pending().stream().filter(ids::contains).toList());
                if(batch.pending().isEmpty()) {jdbc.update("UPDATE calibre_preparation_operations SET cursor=?,processed=?,total=?,phase='CREATING_JOBS',updated_at=? WHERE id=? AND state='RUNNING'",end,end,request.books().size(),Instant.now().toEpochMilli(),id);requireActive(id);return;}
                progress(id,"CREATING_JOBS",cursor,request.books().size());
                CalibrePreparationContext.run((phase,done,total)->{},true,()->catalog.createStagedBatch(batch,created->{merge(selected,created);jdbc.update("UPDATE calibre_preparation_operations SET cursor=?,processed=?,total=?,phase='CREATING_JOBS',updated_at=? WHERE id=?",end,end,request.books().size(),Instant.now().toEpochMilli(),selected);}));
                progress(id,"CREATING_JOBS",end,request.books().size());
            }
        } catch(RuntimeException ex) {
            if(ex instanceof PreparationCancelled) return;
            if(id!=null) {
                if(closed || Thread.currentThread().isInterrupted()) {
                    log.debug("Preparación de Calibre interrumpida al detener la aplicación; se retomará desde el último lote confirmado: operación={}",id);
                    return;
                }
                if(ex instanceof CalibreTransfers.OperationBusy || ex instanceof CalibreClient.TransientFailure || com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) {
                    jdbc.update("UPDATE calibre_preparation_operations SET state='QUEUED',phase='WAITING',message='Esperando automáticamente a que la operación en curso termine',updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')",Instant.now().toEpochMilli(),id);
                    log.debug("Preparación de Calibre aplazada sin repetir lotes: operación={}, tipo={}",id,ex.getClass().getSimpleName());
                } else {
                    String failed=id;tx.executeWithoutResult(s->{var current=row(failed);if("CANCELLED".equals(current.get("state")))return;cancelStaged(current);jdbc.update("UPDATE calibre_preparation_operations SET state='FAILED',input='{}',message=?,updated_at=? WHERE id=?",ex instanceof UserInputException || ex instanceof org.springframework.security.access.AccessDeniedException?ex.getMessage():"No se pudo preparar el envío; revisa los registros antes de reintentar",Instant.now().toEpochMilli(),failed);});
                    if("CANCELLED".equals(row(failed).get("state"))) return;
                    logged.remove(id);
                    log.warn("Fallo preparando envío a Calibre: operación={}, causa={}",id,com.rlibanez.eplsync.exception.OperationDiagnostics.technical(ex));
                }
            } else if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) {
                log.debug("Cola de preparación de Calibre aplazada por contención SQLite");
            } else log.warn("No se pudo comprobar la cola de preparación de Calibre: causa={}",com.rlibanez.eplsync.exception.OperationDiagnostics.technical(ex));
        } finally {SecurityContextHolder.setContext(previous);maintenance.leave(false);}
    }
    private void merge(String operation,String created) {
        requireActive(operation);var op=row(operation);String target=(String)op.get("calibre_job_id"),download=(String)op.get("download_job_id");
        for(String source:CalibrePreparationContext.downloads()) {
            if(download==null) {download=source;jdbc.update("UPDATE calibre_preparation_operations SET download_job_id=? WHERE id=?",download,operation);}
            else if(!download.equals(source)) {
                long offset=jdbc.queryForObject("SELECT coalesce(max(position),-1)+1 FROM torrent_bulk_items WHERE job_id=?",Long.class,download);
                jdbc.update("UPDATE torrent_bulk_items SET state='SKIPPED',message='Hash duplicado en la selección' WHERE job_id=? AND hash IN (SELECT hash FROM torrent_bulk_items WHERE job_id=? AND hash IS NOT NULL)",source,download);
                jdbc.update("UPDATE torrent_bulk_items SET position=position+?,job_id=? WHERE job_id=?",offset,download,source);
                jdbc.update("UPDATE torrent_bulk_jobs SET selected_books=selected_books+(SELECT selected_books FROM torrent_bulk_jobs WHERE id=?) WHERE id=?",source,download);
                jdbc.update("UPDATE calibre_job_items SET download_job_id=? WHERE job_id=? AND download_job_id=?",download,created,source);
                jdbc.update("DELETE FROM torrent_bulk_jobs WHERE id=?",source);
            }
        }
        if(target==null) {jdbc.update("UPDATE calibre_preparation_operations SET calibre_job_id=? WHERE id=?",created,operation);}
        else if(!target.equals(created)) {
            long offset=jdbc.queryForObject("SELECT coalesce(max(position),-1)+1 FROM calibre_job_items WHERE job_id=?",Long.class,target);
            jdbc.update("UPDATE calibre_job_items SET position=position+?,job_id=? WHERE job_id=?",offset,target,created);
            jdbc.update("UPDATE calibre_update_requests SET result=? WHERE result=?",json.writeValueAsString(target),json.writeValueAsString(created));
            jdbc.update("DELETE FROM calibre_jobs WHERE id=?",created);
        }
    }
    private void activate(String id,CatalogCalibreTransfers.Request request) {
        tx.executeWithoutResult(s->{
            var op=row(id);requireActive(id);if(op.get("state").equals("COMPLETED"))return;
            require();if(!Objects.equals(context(),op.get("context"))) throw new UserInputException("La conexión cambió; vuelve a preparar el envío");
            String calibre=(String)op.get("calibre_job_id"),download=(String)op.get("download_job_id");
            if(calibre==null) throw new UserInputException("No hay libros disponibles para enviar");
            if(download!=null) {Permission.require(Permission.TORRENT_SEND);Permission.require(Permission.TORRENT_SYNC);}
            if(download!=null) jdbc.update("UPDATE torrent_bulk_jobs SET state=CASE WHEN EXISTS(SELECT 1 FROM torrent_bulk_items WHERE job_id=? AND state='PENDING') THEN 'QUEUED' ELSE 'COMPLETED' END,updated_at=? WHERE id=? AND state='PAUSED'",download,Instant.now().toEpochMilli(),download);
            jdbc.update("UPDATE calibre_jobs SET state='QUEUED',updated_at=? WHERE id=? AND state='PAUSED'",Instant.now().toString(),calibre);
            jdbc.update("INSERT INTO calibre_update_requests(id,owner_id,payload,result,created_at) VALUES(?,?,?,?,?)","catalog:"+request.id(),op.get("owner_id"),"sha256:"+digest(json.writeValueAsString(request)),json.writeValueAsString(calibre),Instant.now().toString());
            complete(id,calibre);
            if(download!=null) events.live(new EventContext.Actor((String)op.get("owner_id"),(String)op.get("owner_username"),"USER"),EventJournal.Category.JOB,"DOWNLOAD",EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,download,Map.of("selected",jdbc.queryForObject("SELECT selected_books FROM torrent_bulk_jobs WHERE id=?",Long.class,download)));
            events.live(new EventContext.Actor((String)op.get("owner_id"),(String)op.get("owner_username"),"USER"),EventJournal.Category.JOB,"CALIBRE_SEND",EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,calibre,Map.of("selected",request.books().size(),"library",request.library()));
        });
    }
    private void complete(String id,String job) {if(jdbc.update("UPDATE calibre_preparation_operations SET state='COMPLETED',input='{}',phase='READY',processed=total,result=?,updated_at=? WHERE id=? AND state='RUNNING'",json.writeValueAsString(Map.of("id",job)),Instant.now().toEpochMilli(),id)==0) throw new PreparationCancelled();logged.remove(id);log.info("Creación de trabajos de Calibre finalizada: operación={}, trabajo={}",id,job);}
    private void deleteStaged(Map<String,Object> operation) {
        Object calibre=operation.get("calibre_job_id"),download=operation.get("download_job_id");
        // Only this operation's unpublished jobs can be discarded. Fail closed if they escaped staging.
        if(calibre!=null && (jdbc.queryForObject("SELECT count(*) FROM calibre_jobs WHERE id=? AND state='PAUSED'",Long.class,calibre)!=1
                || jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=? AND state NOT IN ('PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD')",Long.class,calibre)>0))
            throw new UserInputException("El trabajo de Calibre ya no está en preparación; revisa su estado antes de cancelar");
        if(download!=null && (jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs WHERE id=? AND state='PAUSED'",Long.class,download)!=1
                || jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE job_id=? AND (state NOT IN ('PENDING','SKIPPED') OR attempts>0)",Long.class,download)>0
                || jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE download_job_id=? AND job_id<>coalesce(?, '')",Long.class,download,calibre)>0))
            throw new UserInputException("El trabajo de descarga ya tiene actividad o dependencias; revisa su estado antes de cancelar");
        if(calibre!=null) {
            jdbc.update("DELETE FROM calibre_update_requests WHERE result=?",json.writeValueAsString(calibre));
            jdbc.update("DELETE FROM calibre_jobs WHERE id=?",calibre);
        }
        if(download!=null) {
            jdbc.update("DELETE FROM torrent_bulk_items WHERE job_id=?",download);
            jdbc.update("DELETE FROM torrent_bulk_jobs WHERE id=?",download);
        }
    }
    private void cancelStaged(Map<String,Object> row) {
        if(row.get("calibre_job_id")!=null) {jdbc.update("UPDATE calibre_job_items SET state='CANCELLED',message='Preparación interrumpida antes del envío' WHERE job_id=?",row.get("calibre_job_id"));jdbc.update("UPDATE calibre_jobs SET state='CANCELLED' WHERE id=? AND state='PAUSED'",row.get("calibre_job_id"));}
        if(row.get("download_job_id")!=null) {jdbc.update("UPDATE torrent_bulk_items SET state='CANCELLED' WHERE job_id=? AND state='PENDING'",row.get("download_job_id"));jdbc.update("UPDATE torrent_bulk_jobs SET state='CANCELLED' WHERE id=? AND state='PAUSED'",row.get("download_job_id"));}
    }
    @jakarta.annotation.PreDestroy void close() {closed=true;executor.shutdownNow();}
}
