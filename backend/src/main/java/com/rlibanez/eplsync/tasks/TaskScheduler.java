package com.rlibanez.eplsync.tasks;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.settings.CalibreCatalogService;
import com.rlibanez.eplsync.torrent.updates.UpdateCleanupService;
import com.rlibanez.eplsync.exception.UserInputException;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="eplsync.tasks.worker-enabled",matchIfMissing=true)
public class TaskScheduler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TaskScheduler.class);
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.settings.AutomaticUpdates automations;
    private final ScheduledTasks tasks;
    private final CalibreCatalogService calibre;
    private final UpdateCleanupService torrent;
    private final EventJournal journal;
    private final UpdateSearches searches;
    private final com.rlibanez.eplsync.service.CatalogImportService catalog;
    private final ExecutorService executor=Executors.newFixedThreadPool(2,Thread.ofPlatform().name("scheduled-task-",0).factory());
    private record Completion(String outcome,String message) {}
    private final Map<String,Completion> pendingCompletions=new LinkedHashMap<>();
    private synchronized void finishTask(String id,String outcome,String message) {
        pendingCompletions.put(id,new Completion(outcome,message));
        flushCompletions();
    }
    private synchronized void flushCompletions() {
        var iterator=pendingCompletions.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();
            try {tasks.finish(entry.getKey(),entry.getValue().outcome(),entry.getValue().message());iterator.remove();}
            catch(RuntimeException ex) {
                if(!com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
                log.debug("Resultado de tarea pendiente de persistencia: id={}",entry.getKey());
            }
        }
    }
    private volatile boolean ready,closed;
    public TaskScheduler(ScheduledTasks tasks,CalibreCatalogService calibre,UpdateCleanupService torrent,EventJournal journal,com.rlibanez.eplsync.service.CatalogImportService catalog,UpdateSearches searches) {this.searches=searches;this.catalog=catalog;this.tasks=tasks;this.calibre=calibre;this.torrent=torrent;this.journal=journal;}
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void start() {tasks.recover(Instant.now());ready=true;}
    private final com.rlibanez.eplsync.config.WorkerActivity.Probe idleProbe=new com.rlibanez.eplsync.config.WorkerActivity.Probe();
    @Scheduled(fixedDelay=1000)
    public void tick() {
        if(!ready || closed) return;
        flushCompletions();
        if(!idleProbe.ready()) return;
        long beforeQuery=idleProbe.snapshot();
        for(int i=0;i<2;i++) {
            var task=tasks.claim(Instant.now());if(task.isEmpty()) {idleProbe.emptyUntil(beforeQuery,tasks.nextRunMillis());return;}
            try {executor.submit(()->run(task.get()));}
            catch(RejectedExecutionException ex) {finishTask(task.get().id(),"INTERRUPTED","La aplicación está cerrándose");}
        }
    }
    public ScheduledTasks.Task runNow(String id) {
        if(!ready || closed) throw new UserInputException("El programador no está disponible");
        var task=tasks.claimNow(id,Instant.now());
        try {executor.submit(()->run(task,EventContext.Origin.MANUAL));}
        catch(RejectedExecutionException ex) {
            finishTask(id,"INTERRUPTED","La aplicación está cerrándose");
            throw new UserInputException("El programador no está disponible");
        }
        return task;
    }
    void run(ScheduledTasks.Task task) {
        run(task,EventContext.Origin.SCHEDULED);
    }
    private void run(ScheduledTasks.Task task,EventContext.Origin origin) {
        tasks.withExecution(()->{ execute(task,origin);return null; });
    }
    private static EventJournal.Category category(ScheduledTasks.Operation operation) {
        return operation==ScheduledTasks.Operation.CATALOG_IMPORT?EventJournal.Category.CATALOG:
            operation==ScheduledTasks.Operation.TORRENT_SYNC?EventJournal.Category.TORRENT:EventJournal.Category.JOB;
    }
    private static String action(ScheduledTasks.Operation operation) {
        return switch(operation) {
            case CATALOG_IMPORT -> "UPDATE";
            case TORRENT_SYNC -> "SYNC";
            case CALIBRE_SYNC -> "CALIBRE_SYNC";
            case TORRENT_UPDATE_SEARCH -> "TORRENT_UPDATE_SEARCH";
            case CALIBRE_UPDATE_SEARCH -> "CALIBRE_UPDATE_SEARCH";
            default -> "SCHEDULED_TASK";
        };
    }
    private void execute(ScheduledTasks.Task task,EventContext.Origin origin) {
        String outcome="FAILED",message=null;
        Map<String,Object> details=Map.of();
        var previous=SecurityContextHolder.getContext();
        log.debug("Inicio de tarea programada: id={}, nombre={}, operación={}, usuario={}",task.id(),task.name(),task.operation(),task.ownerUsername());
        try {
            var context=SecurityContextHolder.createEmptyContext();
            SecurityContextHolder.setContext(context);
            var summary=EventContext.withActor(new EventContext.Actor(task.ownerId(),task.ownerUsername(),"USER"),()->EventContext.withOrigin(origin,()->
                journal.runWithPolicy(task.operation()!=ScheduledTasks.Operation.CATALOG_IMPORT,category(task.operation()),action(task.operation()),Map.of("taskId",task.id(),"taskName",task.name(),"taskOperation",task.operation().name()),()->{
                    var owner=tasks.owner(task.ownerId(),task.operation());
                    var authorities=new ArrayList<SimpleGrantedAuthority>();
                    owner.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
                    authorities.add(new SimpleGrantedAuthority("ROLE_"+owner.role()));
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,authorities));
                    // Permissions are fresh for every run. Existing services also prevent concurrent manual operations.
                    if(task.operation()==ScheduledTasks.Operation.TORRENT_AUTO_UPDATE || task.operation()==ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE) return automations.execute(task);
                    if(task.operation()==ScheduledTasks.Operation.TORRENT_UPDATE_SEARCH) return searches.downloads(owner.id());
                    if(task.operation()==ScheduledTasks.Operation.CALIBRE_UPDATE_SEARCH) return searches.calibre(owner.id());
                    if(task.operation()==ScheduledTasks.Operation.CALIBRE_SYNC) {
                        var result=calibre.sync();return Map.<String,Object>of("total",result.total(),"identified",result.identified());
                    }
                    if(task.operation()==ScheduledTasks.Operation.CATALOG_IMPORT) {
                        var result=catalog.updateCatalog(null);
                        if(!result.success()) throw new UserInputException("No se pudo importar el catálogo; revisa la URL configurada y el registro de importación");
                        return Map.<String,Object>of("processed",result.recordsProcessed(),"created",result.recordsCreated(),"updated",result.recordsUpdated(),"errors",result.errors());
                    }
                    var result=torrent.synchronize(false,false);
                    return Map.<String,Object>of("checked",result.records().checked(),"created",result.records().created(),"updated",result.records().updated());
                },resultDetails->resultDetails)));
            details=summary;
            outcome=summary.get("errors") instanceof Number errors && errors.longValue()>0 ? "PARTIAL" : "SUCCEEDED";
            if(outcome.equals("PARTIAL")) message="La operación se completó con errores: "+summary.get("errors");
        } catch(RuntimeException ex) {
            message=ex instanceof UserInputException || ex instanceof org.springframework.security.access.AccessDeniedException ? ex.getMessage() : "No se pudo completar la operación; revisa la conexión y la configuración";
            if(message!=null && message.length()>1024) message=message.substring(0,1024);
            log.debug("Fallo de tarea programada: id={}, tipo={}",task.id(),ex.getClass().getSimpleName());
        } finally {
            SecurityContextHolder.setContext(previous);finishTask(task.id(),outcome,message);
            if(outcome.equals("SUCCEEDED")) log.debug("Fin de tarea programada: id={}, nombre={}, resultado={}, resumen={}",task.id(),task.name(),outcome,details);
            else log.warn("Fin de tarea programada: id={}, nombre={}, resultado={}, motivo={}, resumen={}",task.id(),task.name(),outcome,message,details);
        }
    }
    @jakarta.annotation.PreDestroy void close() {
        closed=true;executor.shutdown();try {if(!executor.awaitTermination(10,TimeUnit.SECONDS)) executor.shutdownNow();}catch(InterruptedException ex){executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
