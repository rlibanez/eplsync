package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.jobs.JobRetryController;
import com.rlibanez.eplsync.exception.UserInputException;

import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.torrent.downloads.*;
import tools.jackson.databind.json.JsonMapper;

/** Immutable source history, durable previews and atomic, single-use item claims. Remote I/O is outside write transactions. */
@Service
public class JobRetries {
    private final java.util.concurrent.Semaphore preparing=new java.util.concurrent.Semaphore(2),creating=new java.util.concurrent.Semaphore(1);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionAccess sessions;
    private final TorrentProperties torrent;
    private final DownloadTrackingService tracking;
    private final CalibreSettings settings;
    private final CalibreClient calibre;
    private final EpubFiles files;
    private final EventJournal events;
    private final JsonMapper json=JsonMapper.builder().build();
    public JobRetries(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,SessionAccess sessions,
            TorrentProperties torrent,DownloadTrackingService tracking,CalibreSettings settings,CalibreClient calibre,EpubFiles files,EventJournal events) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.sessions=sessions;this.torrent=torrent;this.tracking=tracking;
        this.settings=settings;this.calibre=calibre;this.files=files;this.events=events;
    }
    public record Link(String kind,String jobId,String parentId,String rootId) {}
    public record History(Link source,List<Link> retries,boolean terminal,long failed,String reason) {}
    public record Issue(String id,String detail) {}
    public record Preview(String token,int send,int verify,int waiting,int cleanup,int excluded,List<Issue> issues,List<String> changes,String library) {}
    public record Work(String id,String phase,Map<String,Object> row,List<Map<String,Object>> cleanup) {}
    public record Plan(String kind,String parent,String signature,String configuration,List<Work> work,List<Issue> issues,List<String> changes,String library,boolean failedOnly,List<String> selection) {}
    private static ResponseStatusException conflict(String text) {return new ResponseStatusException(HttpStatus.CONFLICT,text);}
    private static void kind(String kind) {if(!Set.of("downloads","calibre").contains(kind)) throw new UserInputException("Tipo de trabajo desconocido");}
    private void permission(String kind) {kind(kind);Permission.require(kind.equals("downloads")?Permission.TORRENT_JOBS_MANAGE:Permission.CALIBRE_JOBS_MANAGE);}
    private String jobs(String kind) {return kind.equals("downloads")?"torrent_bulk_jobs":"calibre_jobs";}
    private String items(String kind) {return kind.equals("downloads")?"torrent_bulk_items":"calibre_job_items";}
    private Map<String,Object> job(String kind,String id) {
        var rows=jdbc.queryForList("SELECT * FROM "+jobs(kind)+" WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Trabajo no encontrado");return rows.getFirst();
    }
    private boolean terminal(String kind,String id,Map<String,Object> job) {
        if(!Set.of("COMPLETED","CANCELLED").contains(job.get("state"))) return false;
        if(jdbc.queryForObject("SELECT count(*) FROM "+items(kind)+" WHERE job_id=? AND state=?",Long.class,id,kind.equals("downloads")?"IN_FLIGHT":"SENDING")>0) return false;
        return !kind.equals("downloads") || jdbc.queryForObject("SELECT count(*) FROM torrent_update_cleanup WHERE job_id=? AND state='REQUESTED'",Long.class,id)==0;
    }
    public History history(String kind,String id) {
        permission(kind);var job=job(kind,id);boolean terminal=terminal(kind,id,job);
        var source=jdbc.query("SELECT * FROM job_retry_jobs WHERE kind=? AND child_id=?",(r,n)->new Link(kind,id,r.getString("parent_id"),r.getString("root_id")),kind,id);
        var children=jdbc.query("SELECT * FROM job_retry_jobs WHERE kind=? AND parent_id=? ORDER BY created_at,child_id",(r,n)->new Link(kind,r.getString("child_id"),id,r.getString("root_id")),kind,id);
        long failed=jdbc.queryForObject("SELECT count(*) FROM "+items(kind)+" i WHERE job_id=? AND state='FAILED' AND NOT EXISTS(SELECT 1 FROM job_retry_items r WHERE r.kind=? AND r.parent_id=? AND r.source_item_id=i.id)",Long.class,id,kind,id);
        return new History(source.isEmpty()?null:source.getFirst(),children,terminal,failed,terminal?null:"Para reintentar, termina o cancela el trabajo y espera a que finalicen las operaciones en curso. Los trabajos pausados se reanudan.");
    }
    private String configuration(String kind) {
        if(kind.equals("downloads")) return digest(torrent.getClient()+"\n"+torrent.getBaseUrl().replaceAll("/+$",""));
        var c=settings.connection();return digest(json.writeValueAsString(List.of(c.url(),c.username(),c.password(),c.library(),tracking.instanceId(),torrent.getFiles().getRemoteRoot(),torrent.getFiles().getLocalRoot())));
    }
    private String source() {return digest(tracking.instanceId()+"\n"+torrent.getFiles().getRemoteRoot()+"\n"+torrent.getFiles().getLocalRoot());}
    private String destination() {var c=settings.connection();return digest(c.url()+"\n"+c.username());}
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private List<Map<String,Object>> selected(String kind,String id,boolean failedOnly,List<String> selection) {
        if(!failedOnly && (selection==null || selection.isEmpty() || selection.size()>1000 || selection.stream().anyMatch(v->v==null || v.isBlank() || v.length()>64) || new HashSet<>(selection).size()!=selection.size())) throw new UserInputException("Selecciona entre 1 y 1000 elementos únicos");
        String where=failedOnly?" AND state='FAILED'":" AND id IN ("+String.join(",",Collections.nCopies(selection.size(),"?"))+")";
        var args=new ArrayList<Object>();args.add(id);if(!failedOnly) args.addAll(selection);
        var rows=jdbc.queryForList("SELECT * FROM "+items(kind)+" WHERE job_id=?"+where+" ORDER BY position LIMIT 1001",args.toArray());
        if(rows.size()>1000) throw new UserInputException("Hay más de 1000 elementos; selecciona un grupo menor");
        if(!failedOnly && rows.size()!=selection.size()) throw new UserInputException("Algún elemento no pertenece a este trabajo o ya no existe");return rows;
    }
    private String signature(Map<String,Object> job,List<Map<String,Object>> rows) {return digest(bounded(List.of(job,rows)));}
    public Preview preview(String kind,String id,JobRetryController.Selection selection) {
        permission(kind);
        if(!preparing.tryAcquire()) throw conflict("Hay dos reintentos en preparación; inténtalo de nuevo en unos segundos");
        try {return preparePreview(kind,id,selection);}finally {preparing.release();}
    }
    private Preview preparePreview(String kind,String id,JobRetryController.Selection selection) {
        permission(kind);sessions.current();
        if(selection==null) throw new UserInputException("Selección inválida");
        var plan=plan(kind,id,selection.failedOnly(),selection.items());
        String token=UUID.randomUUID().toString();long now=Instant.now().toEpochMilli();
        jdbc.update("DELETE FROM job_retry_previews WHERE id IN (SELECT id FROM job_retry_previews WHERE expires_at<? LIMIT 100)",now);
        if(jdbc.queryForObject("SELECT count(*) FROM job_retry_previews WHERE owner_id=? AND expires_at>? AND NOT EXISTS(SELECT 1 FROM job_retry_jobs WHERE request_id=job_retry_previews.id)",Long.class,sessions.current().id(),now)>=20) throw conflict("Hay demasiados reintentos preparados; espera unos minutos");
        jdbc.update("INSERT INTO job_retry_previews VALUES(?,?,?,?,?,?,?)",token,sessions.current().id(),kind,id,bounded(plan),now,now+900000);
        return new Preview(token,(int)plan.work().stream().filter(w->w.phase().equals("SEND")).count(),(int)plan.work().stream().filter(w->w.phase().equals("VERIFY")).count(),(int)plan.work().stream().filter(w->w.phase().equals("WAIT")).count(),(int)plan.work().stream().filter(w->w.phase().equals("CLEANUP") || !w.cleanup().isEmpty() || "calibre".equals(kind) && !"KEEP".equals(w.row().get("retry_policy"))).count(),plan.issues().size(),plan.issues().stream().limit(30).toList(),plan.changes(),plan.library());
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void expirePreviews() {
        jdbc.update("DELETE FROM job_retry_previews WHERE id IN (SELECT id FROM job_retry_previews WHERE expires_at<? LIMIT 1000)",Instant.now().toEpochMilli());
    }
    private Plan plan(String kind,String id,boolean failedOnly,List<String> selection) {
        var job=job(kind,id);if(!terminal(kind,id,job)) throw conflict("El trabajo debe estar terminado o cancelado y sin operaciones en curso. Reanuda los trabajos pausados.");
        String config=configuration(kind);var rows=selected(kind,id,failedOnly,selection);
        var work=new ArrayList<Work>();var issues=new ArrayList<Issue>();var changes=new LinkedHashSet<String>();
        boolean changed=kind.equals("downloads")?!Objects.equals(job.get("target_fingerprint"),config):!Objects.equals(job.get("destination"),destination());
        if(changed) changes.add("La conexión de destino ha cambiado. El reintento utilizará la conexión configurada actualmente.");
        if(kind.equals("calibre") && !Objects.equals(job.get("source"),source())) changes.add("El cliente torrent o las carpetas de origen han cambiado; se comprobará de nuevo el archivo.");
        var connection=kind.equals("calibre")?settings.connection():null;
        if(connection!=null) connection=new CalibreSettings.Connection(connection.url(),connection.username(),connection.password(),(String)job.get("library"));
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        for(var original:rows) {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw conflict("La preparación ha tardado demasiado; selecciona menos elementos");
            var row=new LinkedHashMap<>(original);String item=(String)row.get("id"),state=(String)row.get("state");
            if(jdbc.queryForObject("SELECT count(*) FROM job_retry_items WHERE kind=? AND parent_id=? AND source_item_id=?",Long.class,kind,id,item)>0) {issues.add(new Issue(item,"Este elemento ya tiene un reintento; continúa desde ese trabajo."));continue;}
            var cleanup=kind.equals("downloads")?jdbc.queryForList("SELECT * FROM torrent_update_cleanup WHERE job_id=? AND epl_id=? AND state='CANCELLED' AND NOT EXISTS(SELECT 1 FROM job_retry_items r WHERE r.kind='downloads' AND r.parent_id=? AND r.source_item_id=torrent_update_cleanup.id) ORDER BY id",id,row.get("epl_id"),id):new ArrayList<Map<String,Object>>();
            String phase;
            if(kind.equals("downloads")) {
                if(Set.of("FAILED","CANCELLED").contains(state) && row.get("command_json")!=null && row.get("hash")!=null) phase="SEND";
                else if(Set.of("ACCEPTED","ALREADY_EXISTS").contains(state) && !cleanup.isEmpty()) phase="CLEANUP";
                else {issues.add(new Issue(item,"Este elemento no tiene una operación recuperable pendiente."));continue;}
                if(changed && !cleanup.isEmpty()) {issues.add(new Issue(item,"Restaura el cliente original antes de reintentar una operación con borrados."));continue;}
            } else {
                String policy=Objects.toString(row.get("requested_cleanup_policy"),Objects.toString(row.get("cleanup_policy"),"KEEP"));
                row.put("retry_policy",policy);row.put("retry_cleanup_ids",Objects.toString(row.get("requested_cleanup_ids"),Objects.toString(row.get("cleanup_ids"),"[]")));
                if(state.equals("UNCERTAIN")) phase="VERIFY";
                else if(Set.of("ACCEPTED","SKIPPED").contains(state) && bool(row.get("cleanup_cancelled"))) phase="CLEANUP";
                else if(Set.of("FAILED","CANCELLED","BLOCKED_DOWNLOAD").contains(state)) phase=row.get("record_id")==null && row.get("download_job_id")!=null?"WAIT":"SEND";
                else {issues.add(new Issue(item,"El envío ya terminó correctamente o no se puede reintentar."));continue;}
                if(changed && (phase.equals("VERIFY") || phase.equals("CLEANUP") || !policy.equals("KEEP"))) {issues.add(new Issue(item,"Restaura la conexión original antes de verificar un envío incierto o reintentar borrados."));continue;}
                if(phase.equals("SEND") && !changed && bool(row.get("identity_complete"))) {
                    Long remote=row.get("calibre_id")==null?calibre.findRevision(connection,number(row,"epl_id"),new BigDecimal((String)row.get("revision"))):number(row,"calibre_id");
                    if(remote!=null && calibre.hasEpub(connection,remote)) {row.put("calibre_id",remote);phase="VERIFY";}
                }
                if(phase.equals("SEND")) {
                    if(row.get("record_id")==null || row.get("file_index")==null || row.get("size")==null) {issues.add(new Issue(item,"El elemento no conserva un archivo de origen válido; prepara un envío nuevo desde Estado de las descargas."));continue;}
                    try {
                        int index=((Number)row.get("file_index")).intValue();
                        var available=files.list((String)row.get("record_id")).stream().filter(f->f.index()==index).findFirst().orElseThrow(()->new UserInputException("El EPUB ya no está disponible"));
                        if(available.size()>268435456) throw new UserInputException("El EPUB supera 256 MiB");
                        if(!Objects.equals(row.get("name"),available.name()) || number(row,"size")!=available.size()) changes.add("EPL ID "+row.get("epl_id")+": el nombre o el tamaño del archivo ha cambiado; se volverán a validar sus identificadores antes del envío.");
                        row.put("size",available.size());row.put("name",available.name());
                        if(available.size()!=available.expectedSize()) row.put("retry_size_warning",true);
                    } catch(UserInputException | com.rlibanez.eplsync.exception.TorrentOperationException ex) {issues.add(new Issue(item,ex.getMessage()));continue;}
                }
            }
            work.add(new Work(item,phase,row,cleanup));
        }
        if(!work.isEmpty()) authorize(kind,work);
        if(!config.equals(configuration(kind))) throw conflict("La configuración cambió durante la preparación; vuelve a preparar el reintento");
        return new Plan(kind,id,signature(job,rows),config,work,issues,List.copyOf(changes),Objects.toString(job.get("library"),""),failedOnly,selection==null?List.of():List.copyOf(selection));
    }
    private String bounded(Object value) {
        var output=new java.io.ByteArrayOutputStream() {
            private void check(int bytes) {if(bytes>8*1024*1024-count) throw new UserInputException("El reintento supera 8 MiB; selecciona menos elementos");}
            @Override public synchronized void write(int value) {check(1);super.write(value);}
            @Override public synchronized void write(byte[] bytes,int offset,int length) {check(length);super.write(bytes,offset,length);}
        };
        try {json.writeValue(output,value);}
        catch(RuntimeException ex) {
            for(Throwable cause=ex;cause!=null;cause=cause.getCause()) if(cause instanceof UserInputException invalid) throw invalid;
            throw ex;
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static boolean bool(Object value) {return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0;}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private void authorize(String kind,List<Work> work) {
        if(kind.equals("downloads")) {
            if(work.stream().anyMatch(w->w.phase().equals("SEND"))) Permission.require(Permission.TORRENT_SEND);
            if(work.stream().anyMatch(w->!w.cleanup().isEmpty())) Permission.require(Permission.TORRENT_CLEANUP);
            if(work.stream().flatMap(w->w.cleanup().stream()).anyMatch(c->Boolean.TRUE.equals(c.get("delete_history")) || c.get("delete_history") instanceof Number n && n.intValue()==1)) Permission.require(Permission.DOWNLOADS_DELETE);
            if(work.stream().flatMap(w->w.cleanup().stream()).anyMatch(c->"REMOVE_TORRENT_AND_FILES".equals(c.get("previous_versions")))) Permission.require(Permission.TORRENT_FILES_DELETE);
        } else {
            Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);
            if(work.stream().anyMatch(w->!"KEEP".equals(w.row().get("retry_policy")))) Permission.require(Permission.CALIBRE_DELETE);
            if(work.stream().anyMatch(w->w.phase().equals("WAIT"))) Permission.require(Permission.TORRENT_SYNC);
        }
    }
    public Link create(String kind,String id,JobRetryController.Confirmation confirmation) {
        permission(kind);
        if(!creating.tryAcquire()) throw conflict("Hay otro reintento en creación; vuelve a intentarlo con esta misma solicitud");
        try {return createPrepared(kind,id,confirmation);}finally {creating.release();}
    }
    private Link createPrepared(String kind,String id,JobRetryController.Confirmation confirmation) {
        permission(kind);var actor=sessions.current();
        if(confirmation==null || confirmation.token()==null || confirmation.token().length()!=36) throw new UserInputException("Solicitud de reintento inválida");
        var previous=existing(kind,id,confirmation.token(),actor.id());if(previous!=null) return previous;
        var previews=jdbc.queryForList("SELECT payload FROM job_retry_previews WHERE id=? AND owner_id=? AND kind=? AND parent_id=? AND expires_at>?",confirmation.token(),actor.id(),kind,id,Instant.now().toEpochMilli());
        if(previews.isEmpty()) throw conflict("La preparación del reintento ha caducado; vuelve a prepararlo");
        var plan=json.readValue((String)previews.getFirst().get("payload"),Plan.class);
        if(plan.work().isEmpty()) throw new UserInputException("No hay elementos recuperables para reintentar");
        var fresh=plan(kind,id,plan.failedOnly(),plan.selection());
        if(!json.writeValueAsString(plan).equals(json.writeValueAsString(fresh))) throw conflict("El trabajo, los archivos o la configuración cambiaron; vuelve a preparar el reintento");
        if(!plan.changes().isEmpty() && !confirmation.confirmChanges()) throw new UserInputException("Confirma los cambios de conexión o archivo antes de continuar");
        authorize(kind,plan.work());
        return tx.execute(s->{
            var existing=existing(kind,id,confirmation.token(),actor.id());if(existing!=null) return existing;
            var parent=job(kind,id);
            if(!terminal(kind,id,parent) || !plan.signature().equals(signature(parent,selected(kind,id,plan.failedOnly(),plan.selection()))) || !plan.configuration().equals(configuration(kind))) throw conflict("El trabajo o la configuración cambiaron; vuelve a preparar el reintento");
            String child=UUID.randomUUID().toString();
            String root=jdbc.queryForList("SELECT root_id FROM job_retry_jobs WHERE kind=? AND child_id=?",String.class,kind,id).stream().findFirst().orElse(id);
            jdbc.update("INSERT INTO job_retry_jobs VALUES(?,?,?,?,?,?,?)",kind,child,id,root,confirmation.token(),actor.id(),Instant.now().toEpochMilli());
            if(kind.equals("downloads")) cloneDownloads(child,parent,plan,actor);else cloneCalibre(child,parent,plan,actor);
            events.live(EventContext.actor(),EventJournal.Category.JOB,kind.equals("downloads")?"DOWNLOAD":"CALIBRE_SEND",EventJournal.Outcome.STARTED,EventContext.origin(),child,Map.of("parentJobId",id,"rootJobId",root,"kind",kind,"selected",plan.work().size()));
            return new Link(kind,child,id,root);
        });
    }
    private Link existing(String kind,String parent,String token,String owner) {
        var rows=jdbc.queryForList("SELECT * FROM job_retry_jobs WHERE request_id=?",token);if(rows.isEmpty()) return null;
        var row=rows.getFirst();if(!Objects.equals(row.get("kind"),kind) || !Objects.equals(row.get("parent_id"),parent) || !Objects.equals(row.get("owner_id"),owner)) throw conflict("La solicitud pertenece a otro reintento");
        return new Link(kind,(String)row.get("child_id"),parent,(String)row.get("root_id"));
    }
    private void claim(String child,Plan plan,Work work,String item) {
        if(jdbc.queryForObject("SELECT count(*) FROM job_retry_items WHERE kind=? AND parent_id=? AND source_item_id=?",Long.class,plan.kind(),plan.parent(),work.id())>0) throw conflict("Este elemento ya tiene un reintento; continúa desde ese trabajo");
        jdbc.update("INSERT INTO job_retry_items VALUES(?,?,?,?,?,?)",plan.kind(),child,plan.parent(),work.id(),item,work.phase());
    }
    private void cloneDownloads(String child,Map<String,Object> parent,Plan plan,Account actor) {
        var now=java.sql.Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,state,type,previous_versions,client,target_fingerprint,multiple_hashes,batch_size,concurrency,interval_millis,selected_books,created_at,updated_at,event_actor_id,event_actor_username,event_actor_kind,event_origin) VALUES(?,'QUEUED',?,?,?,?,?,?,?,?,?,?,?,?,?,'USER',?)",child,parent.get("type"),parent.get("previous_versions"),torrent.getClient(),plan.configuration(),parent.get("multiple_hashes"),parent.get("batch_size"),parent.get("concurrency"),parent.get("interval_millis"),plan.work().stream().map(w->w.row().get("epl_id")).distinct().count(),now,now,actor.id(),actor.username(),EventContext.origin().name());
        var cleanups=new LinkedHashMap<String,Map<String,Object>>();int position=0;
        for(var work:plan.work()) {
            String item=UUID.randomUUID().toString();var row=work.row();claim(child,plan,work,item);
            jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,epl_id,hash,revision,state,attempts,message,command_json) VALUES(?,?,?,?,?,?,?,0,NULL,?)",item,child,position++,row.get("epl_id"),row.get("hash"),row.get("revision"),work.phase().equals("CLEANUP")?row.get("state"):"PENDING",row.get("command_json"));
            work.cleanup().forEach(c->cleanups.put((String)c.get("id"),c));
        }
        if("UPDATE".equals(parent.get("type"))) {
            jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,cleanup_timing,created_at,snapshot,automatic_cleanup,delete_history) SELECT ?,client_instance_id,previous_versions,cleanup_timing,?,'{\"items\":[]}',1,delete_history FROM torrent_update_plans WHERE job_id=?",child,now,plan.parent());
            for(var c:cleanups.values()) {
                String childItem=jdbc.queryForObject("SELECT id FROM torrent_bulk_items WHERE job_id=? AND epl_id=? ORDER BY position LIMIT 1",String.class,child,c.get("epl_id"));
                claim(child,plan,new Work((String)c.get("id"),"CLEANUP",c,List.of()),childItem);
                jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state,message,created_at,updated_at,client_instance_id,previous_versions,target_hashes,actor_id,actor_username,actor_kind,automatic,immediate,replacement_accepted,delete_history) VALUES(?,?,?,?,?,'WAITING',NULL,?,?,?,?,?,?,?,'USER',1,?,?,?)",UUID.randomUUID().toString(),child,c.get("download_id"),c.get("epl_id"),c.get("hash"),now,now,c.get("client_instance_id"),c.get("previous_versions"),c.get("target_hashes"),actor.id(),actor.username(),c.get("immediate"),plan.work().stream().noneMatch(w->Objects.equals(w.row().get("epl_id"),c.get("epl_id")) && w.phase().equals("SEND")),c.get("delete_history"));
            }
        }
    }
    private void cloneCalibre(String child,Map<String,Object> parent,Plan plan,Account actor) {
        String now=Instant.now().toString();
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,'QUEUED')",child,now,now,actor.id(),actor.username(),destination(),parent.get("library"),source());
        int position=0;
        for(var work:plan.work()) {
            var row=work.row();String item=UUID.randomUUID().toString();claim(child,plan,work,item);
            String state=switch(work.phase()) {case "VERIFY"->"UNCERTAIN";case "WAIT"->"WAITING_DOWNLOAD";case "CLEANUP"->"CLEANUP_PENDING";default->"PENDING";};
            String message=bool(row.get("retry_size_warning"))?CalibreTransfers.SIZE_WARNING:null;
            jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,calibre_id,identity_complete,download_job_id,wait_until,completion_state,cleanup_policy,cleanup_ids,requested_cleanup_policy,requested_cleanup_ids,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",item,child,position++,row.get("record_id"),row.get("file_index"),row.get("name"),row.get("size"),row.get("epl_id"),row.get("revision"),state,work.phase().equals("SEND")?null:row.get("calibre_id"),row.get("identity_complete"),row.get("download_job_id"),Instant.now().plus(Duration.ofDays(30)).toEpochMilli(),row.get("completion_state"),row.get("retry_policy"),row.get("retry_cleanup_ids"),row.get("retry_policy"),row.get("retry_cleanup_ids"),message);
        }
    }
}
