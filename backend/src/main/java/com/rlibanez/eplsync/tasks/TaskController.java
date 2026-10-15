package com.rlibanez.eplsync.tasks;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/tasks")
@PreAuthorize("hasRole('ADMIN')")
public class TaskController {
    private final org.springframework.beans.factory.ObjectProvider<TaskScheduler> scheduler;
    private final ScheduledTasks tasks;
    private final InternalTaskSettings internal;
    private final UpdateSearches searches;
    private final com.rlibanez.eplsync.security.SessionAccess sessions;
    public TaskController(ScheduledTasks tasks,InternalTaskSettings internal,com.rlibanez.eplsync.security.SessionAccess sessions,UpdateSearches searches,org.springframework.beans.factory.ObjectProvider<TaskScheduler> scheduler) {this.scheduler=scheduler;this.searches=searches;this.tasks=tasks;this.internal=internal;this.sessions=sessions;}
    public record View(List<ScheduledTasks.Task> tasks,List<InternalTaskSettings.Period> internal,String defaultTimezone) {}
    public record Enabled(boolean enabled) {}
    public record Interval(String interval) {}
    @GetMapping public View list() {return new View(tasks.list(),internal.view(),tasks.timezone());}
    @GetMapping("/cron") public ScheduledTasks.CronPreview cron(@RequestParam String cron) {return tasks.preview(cron,java.time.Instant.now());}
    public record SavedSearch(java.util.Map<String,Object> search) {}
    @GetMapping("/searches/{type}")
    @PreAuthorize("isAuthenticated()")
    public SavedSearch search(@PathVariable String type) {
        com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.CATALOG_READ);
        if(type.equals("downloads")) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_SYNC);
        else if(type.equals("calibre")) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.CALIBRE_READ);
        return new SavedSearch(searches.latest(type,sessions.current().id()));
    }
    @PostMapping("/searches/downloads")
    @PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('TORRENT_SYNC')")
    public java.util.Map<String,Object> searchDownloads(@RequestBody com.rlibanez.eplsync.security.UpdatePreferences.Preferences input) {
        return UpdateSearches.summary(searches.searchDownloads(sessions.current().id(),input));
    }
    @PostMapping("/searches/calibre")
    @PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_SYNC')")
    public java.util.Map<String,Object> searchCalibre() {
        var result=searches.searchCalibre(sessions.current().id());
        return java.util.Map.of("snapshot",result.snapshot(),"search",UpdateSearches.summary(result.search()));
    }
    @GetMapping("/searches/{type}/rows")
    @PreAuthorize("isAuthenticated()")
    public com.rlibanez.eplsync.dto.PageResponse<java.util.Map<String,Object>> rows(@PathVariable String type,
            @RequestParam long generation,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String title,
            @RequestParam(defaultValue="") String author,@RequestParam(defaultValue="") String eplId,
            @RequestParam(defaultValue="") String availability,jakarta.servlet.http.HttpServletRequest request) {
        com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.CATALOG_READ);
        if(type.equals("downloads")) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_SYNC);
        else if(type.equals("calibre")) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.CALIBRE_READ);
        com.rlibanez.eplsync.config.TableOrdering.validateParameters(request);
        return searches.page(type,sessions.current().id(),generation,page,size,
                com.rlibanez.eplsync.config.TableOrdering.request(request,"title,asc"),
                java.util.Map.of("status",status,"title",title,"author",author,"eplId",eplId,"availability",availability));
    }
    public record Input(String name,ScheduledTasks.Operation operation,String cron,boolean enabled) {
        ScheduledTasks.Input ownedBy(String id) {return new ScheduledTasks.Input(name,operation,cron,id,enabled);}
    }
    @PostMapping public ScheduledTasks.Task create(@RequestBody Input input) {return tasks.save(null,input.ownedBy(sessions.current().id()));}
    @PutMapping("/{id}") public ScheduledTasks.Task edit(@PathVariable String id,@RequestBody Input input) {return tasks.save(id,input.ownedBy(tasks.find(id).ownerId()));}
    @PostMapping("/{id}/run")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ScheduledTasks.Task run(@PathVariable String id) {
        var worker=scheduler.getIfAvailable();
        if(worker==null) throw new com.rlibanez.eplsync.exception.UserInputException("El programador no está disponible");
        return worker.runNow(id);
    }
    @PostMapping("/{id}/enabled") public ScheduledTasks.Task enable(@PathVariable String id,@RequestBody Enabled input) {return tasks.enable(id,input.enabled());}
    @PostMapping("/internal/{id}/restore") public InternalTaskSettings.Period restore(@PathVariable String id) {return internal.restore(id);}
    @PutMapping("/internal/{id}") public InternalTaskSettings.Period period(@PathVariable String id,@RequestBody Interval input) {return internal.save(id,input.interval());}
}
