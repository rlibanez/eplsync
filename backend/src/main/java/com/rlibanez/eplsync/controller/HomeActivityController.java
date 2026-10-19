package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.security.Account;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.settings.CalibreSettings;
import com.rlibanez.eplsync.tasks.ScheduledTasks;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** Bounded local projections: opening Home never synchronizes clients or changes work. */
@RestController
@RequestMapping("/api/home")
@PreAuthorize("isAuthenticated()")
public class HomeActivityController {
    public record Attention(String id,String source,String kind,String state,String message,Instant updatedAt,String href) {}
    public record AttentionPage(List<Attention> items,long total) {}
    public record Task(String id,String name,String operation,boolean enabled,boolean running,Instant nextRun,Instant lastRun,String lastResult) {}
    public record Tasks(List<Task> items,int total) {}
    private final JdbcTemplate jdbc;
    private final CalibreSettings calibre;
    private final ScheduledTasks tasks;
    public HomeActivityController(JdbcTemplate jdbc,CalibreSettings calibre,ScheduledTasks tasks) {this.jdbc=jdbc;this.calibre=calibre;this.tasks=tasks;}
    private record Projection(String sql,List<Object> args,String source,String kind) {}
    @GetMapping("/attention")
    public AttentionPage attention(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var queries=new ArrayList<Projection>();
        if(Permission.has(Permission.TORRENT_JOBS_MANAGE)) queries.add(new Projection("""
            SELECT j.id,j.state,j.updated_at,
              coalesce((SELECT coalesce(nullif(i.download_message,''),nullif(i.message,'')) FROM torrent_bulk_items i WHERE i.job_id=j.id AND (i.state='FAILED' OR i.download_state IN ('ERROR','NOT_FOUND','PAUSED')) ORDER BY i.position LIMIT 1),nullif(j.message,''),(SELECT nullif(c.message,'') FROM torrent_update_cleanup c WHERE c.job_id=j.id AND c.state IN ('BLOCKED','REQUESTED') ORDER BY c.created_at LIMIT 1)) message
            FROM torrent_bulk_jobs j WHERE EXISTS(SELECT 1 FROM torrent_update_cleanup c WHERE c.job_id=j.id AND c.state='REQUESTED')
              OR j.state<>'CANCELLED' AND (
              j.state='PAUSED' AND nullif(j.message,'') IS NOT NULL
              OR EXISTS(SELECT 1 FROM torrent_bulk_items i WHERE i.job_id=j.id AND (i.state='FAILED' OR i.download_state IN ('ERROR','NOT_FOUND','PAUSED')))
              OR EXISTS(SELECT 1 FROM torrent_update_cleanup c WHERE c.job_id=j.id AND c.state='BLOCKED'))
            """,List.of(),"downloads","normal"));
        boolean calibreAllowed=Permission.has(Permission.CALIBRE_JOBS_MANAGE) || Permission.has(Permission.CALIBRE_SEND);
        boolean calibreConfigured=(calibreAllowed || Permission.has(Permission.CALIBRE_READ)) && !calibre.view().url().isBlank();
        if(calibreAllowed && calibreConfigured) queries.add(new Projection("""
            SELECT j.id,j.state,j.updated_at,
              coalesce((SELECT nullif(i.message,'') FROM calibre_job_items i WHERE i.job_id=j.id AND i.state IN ('UNCERTAIN','BLOCKED_DOWNLOAD','FAILED') ORDER BY CASE i.state WHEN 'UNCERTAIN' THEN 0 WHEN 'BLOCKED_DOWNLOAD' THEN 1 ELSE 2 END,i.position LIMIT 1),nullif(j.message,'')) message
            FROM calibre_jobs j WHERE EXISTS(SELECT 1 FROM calibre_job_items i WHERE i.job_id=j.id AND i.state='UNCERTAIN')
              OR j.state<>'CANCELLED' AND EXISTS(SELECT 1 FROM calibre_job_items i WHERE i.job_id=j.id AND i.state IN ('BLOCKED_DOWNLOAD','FAILED'))
            """,List.of(),"calibre","normal"));
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth.getPrincipal() instanceof Account account) for(String source:List.of("downloads","calibre")) {
            if(!(source.equals("downloads")?Permission.has(Permission.TORRENT_SYNC):Permission.has(Permission.CALIBRE_READ)&&calibreConfigured)) continue;
            queries.add(new Projection("""
                SELECT j.id,j.state,j.updated_at,
                  coalesce((SELECT nullif(i.message,'') FROM revision_cleanup_items i WHERE i.job_id=j.id AND i.state IN ('UNCERTAIN','BLOCKED','FAILED') ORDER BY CASE i.state WHEN 'UNCERTAIN' THEN 0 WHEN 'BLOCKED' THEN 1 ELSE 2 END,i.position LIMIT 1),nullif(j.message,'')) message
                FROM revision_cleanup_jobs j WHERE j.source=? AND j.owner_id=? AND j.state<>'PREVIEW' AND (
                  EXISTS(SELECT 1 FROM revision_cleanup_items i WHERE i.job_id=j.id AND i.state='UNCERTAIN')
                  OR j.state<>'CANCELLED' AND EXISTS(SELECT 1 FROM revision_cleanup_items i WHERE i.job_id=j.id AND i.state IN ('BLOCKED','FAILED')))
                """,List.of(source,account.id()),source,"cleanup"));
        }
        long total=0;
        var items=new ArrayList<Attention>();
        for(var query:queries) {
            total+=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM ("+query.sql()+")",Long.class,query.args().toArray()));
            items.addAll(jdbc.query(query.sql()+" ORDER BY j.updated_at DESC,j.id LIMIT 10",(r,n)->new Attention(r.getString("id"),query.source(),query.kind(),r.getString("state"),r.getString("message"),instant(r.getObject("updated_at")),"/"+query.source()+"/jobs/"+(query.kind().equals("cleanup")?"cleanup/":"")+r.getString("id")),query.args().toArray()));
        }
        items.sort(Comparator.comparing((Attention item)->item.updatedAt(),Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(item->item.id()));
        return new AttentionPage(List.copyOf(items.subList(0,Math.min(items.size(),10))),total);
    }
    private static Instant instant(Object value) {
        if(value==null) return null;
        if(value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        String text=value.toString();
        if(text.matches("-?[0-9]+")) return Instant.ofEpochMilli(Long.parseLong(text));
        try {return Instant.parse(text);} catch(java.time.format.DateTimeParseException ex) {
            return java.time.LocalDateTime.parse(text.replace(' ','T')).toInstant(java.time.ZoneOffset.UTC);
        }
    }
    @GetMapping("/tasks")
    @PreAuthorize("hasRole('ADMIN')")
    public Tasks tasks(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var all=new ArrayList<>(tasks.list());
        all.sort(Comparator.comparing((ScheduledTasks.Task task)->task.running()).reversed()
            .thenComparing(Comparator.comparing((ScheduledTasks.Task task)->task.enabled()).reversed())
            .thenComparing(task->task.nextRun(),Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(task->task.name()).thenComparing(task->task.id()));
        return new Tasks(all.stream().limit(10).map(task->new Task(task.id(),task.name(),task.operation().name(),task.enabled(),task.running(),task.nextRun(),task.lastRun(),task.lastResult())).toList(),all.size());
    }
}
