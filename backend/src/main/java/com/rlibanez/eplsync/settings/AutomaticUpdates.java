package com.rlibanez.eplsync.settings;

import java.time.Instant;
import java.util.*;
import com.rlibanez.eplsync.tasks.*;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.updates.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A cron invocation plans work; its durable cycle remains pending until all linked phases finish. */
@Service
public class AutomaticUpdates {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"title","author"})
    public record Configuration(boolean updateCatalog,int limit,List<DownloadStatus> states,
        TorrentDownloadRequest options,int concurrency,int batchSize,String interval,MultipleHashes hashes,
        PreviousVersions torrentCleanup,String calibreCleanup,int maxRetries,String library,String torrentTarget,String calibreTarget) {}
    public record Job(String kind,String id,String state) {}
    public record Cycle(String id,String state,long createdAt,Long finishedAt,int attempts,String message,List<Job> jobs) {}
    public record ConfigurationView(Configuration configuration,Cycle cycle) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TorrentProperties torrent;
    private final com.rlibanez.eplsync.qbittorrent.QBittorrentProperties qb;
    private final DownloadTrackingService tracking;
    private final CalibreSettings calibre;
    private final UpdatePreferences preferences;
    private final UpdateSearches searches;
    @org.springframework.beans.factory.annotation.Autowired private SelectedUpdateSender selectedSender;
    private final CalibreUpdateFlow flow;
    private final JobRetries retries;
    private final AccountStore accounts;
    private final com.rlibanez.eplsync.service.CatalogImportService catalog;
    @org.springframework.beans.factory.annotation.Autowired private EpubFiles epub;
    @org.springframework.beans.factory.annotation.Autowired private EventJournal journal;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.torrent.MagnetLinkBuilder magnets;
    private final JsonMapper json=JsonMapper.builder().build();
    public AutomaticUpdates(JdbcTemplate jdbc,PlatformTransactionManager manager,TorrentProperties torrent,
        com.rlibanez.eplsync.qbittorrent.QBittorrentProperties qb,DownloadTrackingService tracking,CalibreSettings calibre,
        UpdatePreferences preferences,UpdateSearches searches,CalibreUpdateFlow flow,JobRetries retries,
        AccountStore accounts,com.rlibanez.eplsync.service.CatalogImportService catalog) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.torrent=torrent;this.qb=qb;this.tracking=tracking;this.calibre=calibre;
        this.preferences=preferences;this.searches=searches;this.flow=flow;this.retries=retries;this.accounts=accounts;this.catalog=catalog;
    }
    public static boolean isAutomation(ScheduledTasks.Operation operation) {return operation==ScheduledTasks.Operation.TORRENT_AUTO_UPDATE || operation==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE;}
    private String calibreTarget() {var c=calibre.view();return CalibreTransfers.destination(new CalibreSettings.Connection(c.url(),c.username(),"",c.library()))+"\n"+c.library();}
    public Configuration defaults() {
        var d=torrent.getDownload();var b=torrent.getBulk();var q=qb.getDownload();
        return new Configuration(false,100,preferences.get().states(),new TorrentDownloadRequest(null,d.isStart(),Objects.requireNonNullElse(d.getSavePath(),""),
            new TorrentDownloadRequest.Rename(torrent.getRename().isEnabled(),torrent.getRename().getPattern()),
            new TorrentDownloadRequest.QBittorrent(Objects.requireNonNullElse(q.getCategory(),""),List.copyOf(q.getTags()),q.isAutoManagement())),
            b.getConcurrency(),b.getBatchSize(),b.getInterval().toMillis()+"ms",MultipleHashes.FIRST,PreviousVersions.KEEP,"KEEP",1,calibre.view().library(),tracking.instanceId(),calibre.view().url().isBlank() || calibre.view().library().isBlank()?null:calibreTarget());
    }
    public Map<String,ConfigurationView> view() {
        var result=new LinkedHashMap<String,ConfigurationView>();
        for(var row:jdbc.queryForList("SELECT task_id,configuration FROM scheduled_automation_settings")) {
            String id=(String)row.get("task_id");result.put(id,new ConfigurationView(read((String)row.get("configuration")),latest(id)));
        }
        return result;
    }
    public void save(ScheduledTasks.Task task,Configuration input) {
        if(active(task.id())!=null) throw new UserInputException("Hay un ciclo pendiente; completa o cancela sus trabajos antes de editar la tarea");
        if(!isAutomation(task.operation())) {jdbc.update("DELETE FROM scheduled_automation_settings WHERE task_id=?",task.id());return;}
        if(input==null || input.limit()<1 || input.limit()>1000 || input.maxRetries()<0 || input.maxRetries()>3
            || input.options()==null || input.options().hash()!=null || input.options().start()==null || input.options().savePath()==null || input.options().rename()==null || input.options().rename().enabled()==null || input.options().rename().pattern()==null
            || input.options().qbittorrent()==null || input.options().qbittorrent().category()==null || input.options().qbittorrent().tags()==null || input.options().qbittorrent().autoManagement()==null
            || input.torrentCleanup()==null || !Set.of("KEEP","EPUB","BOOK").contains(Objects.requireNonNullElse(input.calibreCleanup(),"")) || input.hashes()!=MultipleHashes.FIRST)
            throw new UserInputException("Configuración de automatización inválida; selecciona entre 1 y 1000 libros y entre 0 y 3 reintentos");
        RevisionUpdates.validate(new UpdatePreferences.Preferences(input.states()),0,20,"eplId,asc");
        if(input.concurrency()<1 || input.concurrency()>16 || input.batchSize()<1 || input.batchSize()>1000 || input.interval()==null) throw new UserInputException("Opciones de envío inválidas");
        java.time.Duration duration;
        try {duration=org.springframework.boot.convert.DurationStyle.detectAndParse(input.interval());}
        catch(IllegalArgumentException ex) {throw new UserInputException("Intervalo de envío inválido");}
        if(duration.isNegative() || duration.compareTo(java.time.Duration.ofMinutes(1))>0) throw new UserInputException("El intervalo de envío debe estar entre 0 y 60s");
        if(input.options().rename().enabled()) com.rlibanez.eplsync.torrent.TorrentNameResolver.validatePattern(input.options().rename().pattern());
        if(!Objects.equals(input.torrentTarget(),tracking.instanceId()) || task.operation()==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE && (!Objects.equals(input.library(),calibre.connection().library()) || !Objects.equals(input.calibreTarget(),calibreTarget())))
            throw new UserInputException("La conexión o biblioteca cambió mientras se configuraba la tarea; cierra y vuelve a abrir el asistente");
        if(task.operation()==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE) epub.requireRoots();
        var normalized=new Configuration(input.updateCatalog(),input.limit(),List.copyOf(input.states()),input.options(),input.concurrency(),input.batchSize(),input.interval(),input.hashes(),input.torrentCleanup(),input.calibreCleanup(),input.maxRetries(),task.operation()==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE?calibre.connection().library():null,tracking.instanceId(),task.operation()==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE?calibreTarget():null);
        authorize(task,normalized);
        String value=json.writeValueAsString(normalized);if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw new UserInputException("Configuración demasiado grande");
        jdbc.update("INSERT INTO scheduled_automation_settings VALUES(?,?) ON CONFLICT(task_id) DO UPDATE SET configuration=excluded.configuration",task.id(),value);
    }
    private Configuration read(String value) {return json.readValue(value,Configuration.class);}
    private void authorize(ScheduledTasks.Task task,Configuration c) {
        var owner=task.ownerId()==null?null:accounts.find(task.ownerId());
        var required=new HashSet<>(task.operation().permissions());
        if(c.updateCatalog()) required.add(Permission.CATALOG_IMPORT);
        if(c.torrentCleanup()!=PreviousVersions.KEEP) required.add(Permission.TORRENT_CLEANUP);
        if(c.torrentCleanup()==PreviousVersions.REMOVE_TORRENT_AND_FILES) required.add(Permission.TORRENT_FILES_DELETE);
        if(!c.calibreCleanup().equals("KEEP")) required.add(Permission.CALIBRE_DELETE);
        if(owner==null || !owner.status().equals("ACTIVE") || owner.mustChangePassword() || owner.temporaryExpiresAt()!=null && !owner.temporaryExpiresAt().isAfter(Instant.now()) || !owner.permissions().containsAll(required))
            throw new UserInputException("El propietario no tiene todos los permisos necesarios para esta automatización");
        required.forEach(Permission::require);
    }
    private Map<String,Object> active(String task) {return jdbc.queryForList("SELECT * FROM scheduled_automation_runs WHERE task_id=? AND state IN ('WAITING','ATTENTION')",task).stream().findFirst().orElse(null);}
    public Cycle latest(String task) {
        var rows=jdbc.queryForList("SELECT * FROM scheduled_automation_runs WHERE task_id=? ORDER BY created_at DESC,id DESC LIMIT 1",task);
        if(rows.isEmpty()) return null;var r=rows.getFirst();
        return new Cycle((String)r.get("id"),(String)r.get("state"),((Number)r.get("created_at")).longValue(),r.get("finished_at")==null?null:((Number)r.get("finished_at")).longValue(),((Number)r.get("attempts")).intValue(),(String)r.get("message"),jobs((String)r.get("id")));
    }
    private List<Job> jobs(String run) {
        return jdbc.query("WITH RECURSIVE related(kind,id) AS (SELECT kind,job_id FROM scheduled_automation_jobs WHERE run_id=? UNION SELECT r.kind,j.child_id FROM related r JOIN job_retry_jobs j ON j.kind=r.kind AND j.parent_id=r.id) SELECT r.kind,r.id,CASE WHEN r.kind='downloads' THEN (SELECT state FROM torrent_bulk_jobs WHERE id=r.id) ELSE (SELECT state FROM calibre_jobs WHERE id=r.id) END AS state FROM related r WHERE NOT EXISTS(SELECT 1 FROM job_retry_jobs j WHERE j.kind=r.kind AND j.parent_id=r.id) ORDER BY r.kind,r.id",(r,n)->new Job(r.getString(1),r.getString(2),Objects.requireNonNullElse(r.getString(3),"MISSING")),run);
    }

    private void link(String run,String kind,String id) {if(id!=null) jdbc.update("INSERT INTO scheduled_automation_jobs VALUES(?,?,?) ON CONFLICT DO NOTHING",run,kind,id);}
    private boolean failed(Job job) {
        if(job.state().equals("CANCELLED") || job.state().equals("MISSING")) return true;
        String table=job.kind().equals("downloads")?"torrent_bulk_items":"calibre_job_items";
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE job_id=? AND state IN ('FAILED','UNCERTAIN','BLOCKED_DOWNLOAD')",Long.class,job.id())>0;
    }
    private void attention(String run) {
        var rows=jdbc.queryForList("SELECT r.owner_id,r.context,t.owner_username,t.id,t.operation FROM scheduled_automation_runs r JOIN scheduled_tasks t ON t.id=r.task_id WHERE r.id=?",run);
        if(rows.isEmpty()) return;
        if(jdbc.update("UPDATE scheduled_automation_runs SET state='ATTENTION',message='Revisa los trabajos enlazados: hay operaciones pendientes de confirmación o se agotaron los reintentos' WHERE id=? AND state='WAITING'",run)==0) return;
        var row=rows.getFirst();var actor=new EventContext.Actor((String)row.get("owner_id"),(String)row.get("owner_username"),"USER");
        var origin=EventContext.Origin.valueOf((String)row.get("context"));
        var details=Map.<String,Object>of("taskId",row.get("id"),"taskOperation",row.get("operation"),"reason","El ciclo requiere revisar sus trabajos pendientes");
        journal.recordAs(actor,EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.STARTED,origin,run,details);
        journal.recordAs(actor,EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.PARTIAL,origin,run,details);
        org.slf4j.LoggerFactory.getLogger(AutomaticUpdates.class).warn("Ciclo de actualización pendiente de revisión: ciclo={}, tarea={}",run,row.get("id"));
    }
    private void refresh(String run) {
        var jobs=jobs(run);if(jobs.isEmpty()) {
            jdbc.update("UPDATE scheduled_automation_runs SET state='COMPLETED',finished_at=?,message='Planificación interrumpida; se volverá a comprobar en la próxima ejecución' WHERE id=? AND NOT EXISTS(SELECT 1 FROM scheduled_tasks t WHERE t.id=task_id AND t.running=1)",Instant.now().toEpochMilli(),run);return;
        }
        if(jobs.stream().anyMatch(j->j.state().equals("PAUSED") || j.state().equals("MISSING"))) attention(run);
        else if(jobs.stream().noneMatch(this::failed)) jdbc.update("UPDATE scheduled_automation_runs SET state='WAITING',message=NULL WHERE id=? AND state='ATTENTION'",run);
        if(jobs.stream().allMatch(j->j.state().equals("COMPLETED") && !failed(j))) jdbc.update("UPDATE scheduled_automation_runs SET state='COMPLETED',finished_at=? WHERE id=?",Instant.now().toEpochMilli(),run);
    }
    @org.springframework.beans.factory.annotation.Value("${eplsync.tasks.worker-enabled:true}") private boolean workerEnabled;
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000)
    public void tick() {if(workerEnabled) refresh();}
    public void refresh() {
        for(var id:jdbc.queryForList("SELECT id FROM scheduled_automation_runs WHERE state IN ('WAITING','ATTENTION') LIMIT 200",String.class)) refresh(id);
    }
    public void close(String taskId) {
        var row=active(taskId);if(row==null) return;
        String run=(String)row.get("id");
        if(jobs(run).stream().anyMatch(j->!Set.of("COMPLETED","CANCELLED").contains(j.state()))) throw new UserInputException("Completa o cancela los trabajos pendientes antes de cerrar el ciclo");
        for(var job:jobs(run)) if(job.kind().equals("calibre") && jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=? AND state IN ('SENDING','UNCERTAIN')",Long.class,job.id())>0) throw new UserInputException("Verifica los envíos inciertos antes de cerrar el ciclo");
        jdbc.update("UPDATE scheduled_automation_runs SET state='COMPLETED',finished_at=?,message='Ciclo cerrado por el usuario; se permite una nueva ejecución' WHERE id=?",Instant.now().toEpochMilli(),run);
    }
    public Map<String,Object> execute(ScheduledTasks.Task task) {return AutomationContext.run(()->executeScoped(task));}
    private Map<String,Object> executeScoped(ScheduledTasks.Task task) {
        var values=jdbc.queryForList("SELECT configuration FROM scheduled_automation_settings WHERE task_id=?",String.class,task.id());
        if(values.isEmpty()) throw new UserInputException("Configura la automatización antes de ejecutarla");
        var c=read(values.getFirst());authorize(task,c);
        if(!Objects.equals(c.torrentTarget(),tracking.instanceId()) || task.operation()==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE && !Objects.equals(c.calibreTarget(),calibreTarget())) throw new UserInputException("La conexión o biblioteca cambió; revisa y guarda la automatización");
        var previous=active(task.id());
        if(previous!=null) {
            String run=(String)previous.get("id");refresh(run);previous=active(task.id());
            if(previous!=null) {
                var jobs=jobs(run);int attempts=((Number)previous.get("attempts")).intValue();
                boolean attention=jobs.stream().anyMatch(j->j.state().equals("PAUSED") || j.state().equals("MISSING"));
                if(attempts<c.maxRetries()) {
                    boolean retried=false;
                    for(var job:jobs) if(Set.of("COMPLETED","CANCELLED").contains(job.state()) && failed(job)) {
                        var preview=retries.preview(job.kind(),job.id(),new com.rlibanez.eplsync.jobs.JobRetryController.Selection(null,true));
                        if(!preview.changes().isEmpty() || preview.send()+preview.verify()+preview.waiting()+preview.cleanup()==0) attention=true;
                        if(preview.send()+preview.verify()+preview.waiting()+preview.cleanup()>0 && preview.changes().isEmpty()) {
                            var child=retries.create(job.kind(),job.id(),new com.rlibanez.eplsync.jobs.JobRetryController.Confirmation(preview.token(),false));
                            tx.executeWithoutResult(s->link(run,child.kind(),child.jobId()));retried=true;
                        }
                    }
                    if(retried) jdbc.update("UPDATE scheduled_automation_runs SET attempts=attempts+1,state='WAITING' WHERE id=?",run);
                } else if(jobs.stream().anyMatch(this::failed)) attention=true;
                if(attention) attention(run);
                return Map.of("cycle",run,"pending",1);
            }
        }
        if(c.updateCatalog()) {var imported=catalog.updateCatalog(null);if(!imported.success() || imported.errors()>0) throw new UserInputException("La actualización del catálogo no terminó correctamente; no se enviarán libros");}
        var selected=new ArrayList<Long>();
        if(task.operation()==ScheduledTasks.Operation.TORRENT_AUTO_UPDATE) {
            for(var row:searches.searchDownloads(task.ownerId(),new UpdatePreferences.Preferences(c.states())).rows()) {
                if(selected.size()>=c.limit()) break;
                var book=(RevisionUpdates.Row)row;if(selected.size()<c.limit()) selected.add(book.eplId());
            }
        } else {
            // Refresh torrent state before choosing between reusing and downloading an EPUB.
            searches.searchDownloads(task.ownerId(),new UpdatePreferences.Preferences(c.states()));
            var seen=new HashSet<Long>();
            for(var row:searches.searchCalibre(task.ownerId()).search().rows()) {
                if(selected.size()>=c.limit()) break;
                var book=(Map<?,?>)row;long epl=((Number)book.get("eplId")).longValue();
                if(seen.add(epl) && selected.size()<c.limit()) selected.add(((Number)book.get("calibreId")).longValue());
            }
        }
        jdbc.update("DELETE FROM scheduled_automation_runs WHERE task_id=? AND state='COMPLETED' AND id NOT IN(SELECT id FROM scheduled_automation_runs WHERE task_id=? AND state='COMPLETED' ORDER BY created_at DESC,id DESC LIMIT 19)",task.id(),task.id());
        String run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,owner_id,state,created_at,configuration,context) VALUES(?,?,?,'WAITING',?,?,?)",run,task.id(),task.ownerId(),Instant.now().toEpochMilli(),values.getFirst(),EventContext.origin().name());
        var request=new UpdateRequest(c.torrentCleanup(),c.options(),c.batchSize(),c.concurrency(),c.interval(),c.hashes(),CleanupTiming.AFTER_DOWNLOAD);
        int errors=0;
        try {
            if(!selected.isEmpty() && task.operation()==ScheduledTasks.Operation.TORRENT_AUTO_UPDATE) {
                tx.executeWithoutResult(s->{var job=selectedSender.create(new SelectedUpdateSender.Request(selected,c.states(),c.torrentCleanup(),true,c.options(),c.concurrency(),c.batchSize(),c.interval(),c.hashes(),CleanupTiming.AFTER_DOWNLOAD));link(run,"downloads",job.jobId());jdbc.update("UPDATE torrent_update_plans SET automatic_cleanup=1 WHERE job_id=? AND previous_versions<>'KEEP'",job.jobId());});
            } else if(!selected.isEmpty()) {
                var choices=new ArrayList<CalibreUpdateFlow.Choice>();
                for(var b:flow.prepare(selected)) {
                    var links=jdbc.queryForList("SELECT links FROM catalog_books WHERE epl_id=?",String.class,b.eplId());
                    if(b.problem()!=null || b.files().size()>1 || b.files().isEmpty() && (links.isEmpty() || magnets.hashes(links.getFirst()).isEmpty())) {errors++;continue;}
                    choices.add(new CalibreUpdateFlow.Choice(b.calibreId(),b.revision(),true,b.files().stream().map(f->new CalibreTransfers.FileChoice(f.recordId(),f.index())).toList(),c.calibreCleanup(),b.previous().stream().map((CalibreUpdateFlow.Previous revision) -> Objects.requireNonNull(revision).id()).toList(),c.calibreCleanup().equals("BOOK")));
                }
                if(!choices.isEmpty()) flow.createAutomated(new CalibreUpdateFlow.Request(run,choices,true,true,request),result->{
                    link(run,"downloads",result.downloadJobId());link(run,"calibre",result.calibreJobId());
                    if(result.downloadJobId()!=null) jdbc.update("UPDATE torrent_update_plans SET automatic_cleanup=1 WHERE job_id=? AND previous_versions<>'KEEP'",result.downloadJobId());
                });
            }
            if(jobs(run).isEmpty()) jdbc.update("UPDATE scheduled_automation_runs SET state='COMPLETED',finished_at=?,message=? WHERE id=?",Instant.now().toEpochMilli(),errors>0?"No se pudieron preparar algunos EPUB; revisa su disponibilidad":null,run);
            else if(errors>0) jdbc.update("UPDATE scheduled_automation_runs SET message=? WHERE id=?","EPUB excluidos por falta de acceso, torrent no disponible o selección ambigua: "+errors,run);
            return Map.of("cycle",run,"selected",selected.size(),"errors",errors);
        } catch(RuntimeException ex) {
            jdbc.update("UPDATE scheduled_automation_runs SET state=?,finished_at=?,message='No se pudo preparar el ciclo; revisa la configuración y los trabajos' WHERE id=?",jobs(run).isEmpty()?"COMPLETED":"ATTENTION",Instant.now().toEpochMilli(),run);throw ex;
        }
    }
}
