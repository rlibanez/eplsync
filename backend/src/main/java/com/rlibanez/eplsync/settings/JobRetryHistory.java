package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.exception.UserInputException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Previous attempts are frozen before any item is reused. The latest result remains durable in its job. */
@Service
public class JobRetryHistory {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public JobRetryHistory(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private static String jobs(String kind) {return kind.equals("downloads")?"torrent_bulk_jobs":"calibre_jobs";}
    private static String items(String kind) {return kind.equals("downloads")?"torrent_bulk_items":"calibre_job_items";}
    public record Attempt(String id,long createdAt,Long finishedAt,String username,String state,long total) {}
    public record Detail(String id,Long eplId,String name,String phase,String beforeState,String state,String beforeMessage,String message,List<Cleanup> beforeCleanup,List<Cleanup> cleanup) {}
    public record Cleanup(String id,String state,String message) {}
    @Transactional
    public void freeze(String kind,String job) {
        var rows=jdbc.queryForList("SELECT * FROM "+jobs(kind)+" WHERE id=?",job);
        if(rows.isEmpty() || !Set.of("COMPLETED","CANCELLED").contains(rows.getFirst().get("state")) && !pausedBoundary(kind,job)) return;
        if(jdbc.queryForObject("SELECT count(*) FROM "+items(kind)+" WHERE job_id=? AND state=?",Long.class,job,kind.equals("downloads")?"IN_FLIGHT":"SENDING")>0) return;
        if(kind.equals("downloads") && jdbc.queryForObject("SELECT count(*) FROM torrent_update_cleanup WHERE job_id=? AND state='REQUESTED'",Long.class,job)>0) return;
        for(var attempt:jdbc.queryForList("SELECT id FROM job_retry_attempts WHERE kind=? AND job_id=? AND finished_at IS NULL",String.class,kind,job)) {
            for(var id:jdbc.queryForList("SELECT item_id FROM job_retry_attempt_items WHERE attempt_id=?",String.class,attempt)) {
                var current=jdbc.queryForList("SELECT * FROM "+items(kind)+" WHERE id=? AND job_id=?",id,job);
                if(!current.isEmpty()) jdbc.update("UPDATE job_retry_attempt_items SET after_item=? WHERE attempt_id=? AND item_id=?",json.writeValueAsString(snapshot(kind,job,current.getFirst())),attempt,id);
            }
            jdbc.update("UPDATE job_retry_attempts SET finished_at=?,result=? WHERE id=?",timestamp(rows.getFirst().get("updated_at")),rows.getFirst().get("state"),attempt);
        }
    }
    /** Explicit reconciliation is an attempt boundary too, including paused uncertain uploads. */
    @Transactional
    public String resolution(String job,String item,String phase,com.rlibanez.eplsync.events.EventContext.Actor actor) {
        if(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=? AND state='SENDING'",Long.class,job)>0) throw new com.rlibanez.eplsync.exception.UserInputException("Espera a que termine el envío en curso");
        long now=Instant.now().toEpochMilli();
        for(String attempt:jdbc.queryForList("SELECT id FROM job_retry_attempts WHERE kind='calibre' AND job_id=? AND finished_at IS NULL",String.class,job)) {
            for(String previous:jdbc.queryForList("SELECT item_id FROM job_retry_attempt_items WHERE attempt_id=?",String.class,attempt)) {
                var current=jdbc.queryForMap("SELECT * FROM calibre_job_items WHERE id=? AND job_id=?",previous,job);
                jdbc.update("UPDATE job_retry_attempt_items SET after_item=? WHERE attempt_id=? AND item_id=?",json.writeValueAsString(current),attempt,previous);
            }
            jdbc.update("UPDATE job_retry_attempts SET finished_at=?,result='PAUSED' WHERE id=?",now,attempt);
        }
        String id=UUID.randomUUID().toString();
        long created=Math.max(now,jdbc.queryForObject("SELECT coalesce(max(created_at),0)+1 FROM job_retry_attempts WHERE kind='calibre' AND job_id=?",Long.class,job));
        jdbc.update("INSERT INTO job_retry_attempts(id,kind,job_id,owner_id,username,created_at,before_job) VALUES(?,'calibre',?,?,?,?,?)",id,job,actor.id(),Objects.toString(actor.username(),"Sistema"),created,json.writeValueAsString(jdbc.queryForMap("SELECT * FROM calibre_jobs WHERE id=?",job)));
        jdbc.update("INSERT INTO job_retry_attempt_items(attempt_id,item_id,position,phase,before_item) VALUES(?,?,0,?,?)",id,item,phase,json.writeValueAsString(jdbc.queryForMap("SELECT * FROM calibre_job_items WHERE id=?",item)));
        return id;
    }
    @Transactional
    public void resolved(String attempt,String item) {
        jdbc.update("UPDATE job_retry_attempt_items SET after_item=? WHERE attempt_id=? AND item_id=?",json.writeValueAsString(jdbc.queryForMap("SELECT * FROM calibre_job_items WHERE id=?",item)),attempt,item);
        jdbc.update("UPDATE job_retry_attempts SET finished_at=?,result='COMPLETED' WHERE id=?",Instant.now().toEpochMilli(),attempt);
    }
    private boolean pausedBoundary(String kind,String job) {
        return kind.equals("downloads") && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM torrent_bulk_jobs j WHERE j.id=? AND j.state='PAUSED' AND j.type='UPDATE' AND EXISTS(SELECT 1 FROM torrent_bulk_items i WHERE i.job_id=j.id AND i.state='FAILED') AND NOT EXISTS(SELECT 1 FROM torrent_bulk_items i WHERE i.job_id=j.id AND i.state IN ('PENDING','IN_FLIGHT')))",Boolean.class,job));
    }
    private Map<String,Object> snapshot(String kind,String job,Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);
        if(kind.equals("downloads")) result.put("cleanup",jdbc.queryForList("SELECT id,state,message FROM torrent_update_cleanup WHERE job_id=? AND epl_id=? ORDER BY id",job,row.get("epl_id")));
        return result;
    }
    private static List<Cleanup> cleanup(tools.jackson.databind.JsonNode row) {
        var result=new ArrayList<Cleanup>();
        for(var entry:row.path("cleanup")) result.add(new Cleanup(entry.path("id").asString(""),entry.path("state").asString(""),entry.path("message").asString("")));
        return result;
    }
    private static long timestamp(Object value) {
        if(value instanceof Number n) return n.longValue();
        if(value instanceof java.util.Date d) return d.getTime();
        try {return Instant.parse(value.toString()).toEpochMilli();}catch(RuntimeException ex) {return Instant.now().toEpochMilli();}
    }
    private static PageResponse.PageMeta meta(int page,int size,long total) {int pages=(int)((total+size-1)/size);return new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page+1<pages,page>0);}
    @Transactional(readOnly=true)
    public PageResponse<Attempt> attempts(String kind,String job,int page,int size) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        long total=jdbc.queryForObject("SELECT count(*) FROM job_retry_attempts WHERE kind=? AND job_id=?",Long.class,kind,job);
        var current=jdbc.queryForMap("SELECT state,updated_at FROM "+jobs(kind)+" WHERE id=?",job);
        String state=current.get("state").toString();
        Long finished=Set.of("COMPLETED","CANCELLED").contains(state) || pausedBoundary(kind,job)?timestamp(current.get("updated_at")):null;
        var rows=jdbc.query("SELECT a.*,(SELECT count(*) FROM job_retry_attempt_items i WHERE i.attempt_id=a.id) total FROM job_retry_attempts a WHERE kind=? AND job_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->new Attempt(r.getString("id"),r.getLong("created_at"),r.getObject("finished_at")==null?finished:Long.valueOf(r.getLong("finished_at")),r.getString("username"),Objects.requireNonNullElse(r.getString("result"),state),r.getLong("total")),kind,job,size,(long)page*size);
        return new PageResponse<>(rows,meta(page,size,total));
    }
    @Transactional(readOnly=true)
    public PageResponse<Detail> details(String kind,String job,String attempt,int page,int size) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if(jdbc.queryForObject("SELECT count(*) FROM job_retry_attempts WHERE id=? AND kind=? AND job_id=?",Long.class,attempt,kind,job)!=1) throw new UserInputException("El intento no pertenece al trabajo");
        long total=jdbc.queryForObject("SELECT count(*) FROM job_retry_attempt_items WHERE attempt_id=?",Long.class,attempt);
        var rows=jdbc.queryForList("SELECT * FROM job_retry_attempt_items WHERE attempt_id=? ORDER BY position LIMIT ? OFFSET ?",attempt,size,(long)page*size);
        var result=new ArrayList<Detail>();
        for(var row:rows) {
            var before=json.readTree(row.get("before_item").toString());
            var current=jdbc.queryForList("SELECT * FROM "+items(kind)+" WHERE id=? AND job_id=?",row.get("item_id"),job);
            var after=row.get("after_item")!=null?json.readTree(row.get("after_item").toString()):current.isEmpty()?before:json.valueToTree(snapshot(kind,job,current.getFirst()));
            result.add(new Detail(row.get("item_id").toString(),before.path("epl_id").isNumber()?before.path("epl_id").asLong():null,before.path("name").asString("EPL "+before.path("epl_id").asString("")),row.get("phase").toString(),before.path("state").asString(""),after.path("state").asString(""),before.path("message").asString(""),after.path("message").asString(""),cleanup(before),cleanup(after)));
        }
        return new PageResponse<>(result,meta(page,size,total));
    }
}
