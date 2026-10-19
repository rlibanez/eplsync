package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.config.*;
import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** A single ordered, paginated projection. Cleanup keeps its durable executor and owner checks. */
@Service
public class WorkListings {
    private final JdbcTemplate jdbc;
    private final BulkStore bulk;
    private final CalibreTransfers calibre;
    private final RevisionCleanup cleanup;
    private final JsonMapper json=JsonMapper.builder().build();
    public WorkListings(JdbcTemplate jdbc,BulkStore bulk,CalibreTransfers calibre,RevisionCleanup cleanup) {this.jdbc=jdbc;this.bulk=bulk;this.calibre=calibre;this.cleanup=cleanup;}
    private record Listing(String base,String order,List<Object> args,boolean downloads) {}
    public record Neighbor(String id,boolean cleanup,long index) {}
    public record Neighbors(Neighbor previous,Neighbor next) {}
    private Listing query(String source,List<String> states,String type,String username,Instant from,Instant before,String sort,String statusOperator,String typeOperator) {
        boolean exclude=com.rlibanez.eplsync.ordering.StateOperator.excluded(statusOperator);
        boolean excludeType=com.rlibanez.eplsync.ordering.StateOperator.excluded(typeOperator);
        boolean downloads=source.equals("downloads");
        if(!downloads && !source.equals("calibre")) throw new UserInputException("Origen inválido");
        if(from!=null && before!=null && !from.isBefore(before)) throw new UserInputException("Intervalo de fechas inválido");
        if(username!=null && username.length()>64) throw new UserInputException("Usuario demasiado largo");
        var types=type==null || type.isEmpty()?List.<String>of():Arrays.stream(type.split(",",-1)).map(value -> Objects.requireNonNull(value).trim()).distinct().toList();
        if(!(downloads?Set.of("DOWNLOAD","UPDATE","CLEANUP"):Set.of("SEND","CLEANUP")).containsAll(types)) throw new UserInputException("Tipo de trabajo inválido");
        if(states!=null) {
            if(states.stream().anyMatch(Objects::isNull)) throw new UserInputException("Estado de trabajo inválido");
            states=states.stream().map(value -> Objects.requireNonNull(value).trim()).distinct().toList();
        }
        var allowed=downloads?Set.of("jobId","createdAt","type","status","progress","selectedBooks","accepted","failed","username"):Set.of("id","createdAt","type","library","state","total","accepted","failed","username");
        var orders=TableOrdering.parse(sort,allowed);
        boolean normal=downloads?Permission.has(Permission.TORRENT_JOBS_MANAGE):Permission.has(Permission.CALIBRE_SEND)||Permission.has(Permission.CALIBRE_JOBS_MANAGE);
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String owner=auth!=null && auth.getPrincipal() instanceof Account account && Permission.has(downloads?Permission.TORRENT_SYNC:Permission.CALIBRE_READ)?account.id():"";
        if(downloads && !owner.isEmpty()) cleanup.adoptCurrentRemovals();
        String normalSql=downloads?"""
            SELECT j.id,'normal' kind,CASE WHEN typeof(j.created_at) IN ('integer','real') THEN j.created_at ELSE cast(round((julianday(j.created_at)-2440587.5)*86400000) AS INTEGER) END createdAt,
            coalesce(j.type,'DOWNLOAD') type,j.state state,j.event_actor_username username,NULL library,j.selected_books selectedBooks,
            count(i.id) total,coalesce(sum(CASE WHEN j.track_downloads=1 THEN i.state IN ('SKIPPED','FAILED','CANCELLED') OR i.download_state IN ('DOWNLOADED','IGNORED') ELSE i.state IN ('ACCEPTED','ALREADY_EXISTS','SKIPPED','FAILED') END),0) processed,
            coalesce(sum(i.state='ACCEPTED'),0) accepted,coalesce(sum(i.state='FAILED'),0) failed
            FROM torrent_bulk_jobs j LEFT JOIN torrent_bulk_items i ON i.job_id=j.id GROUP BY j.id
            """:"""
            SELECT j.id,'normal' kind,cast(round((julianday(j.created_at)-2440587.5)*86400000) AS INTEGER) createdAt,
            'SEND' type,j.state state,j.owner_username username,j.library,count(i.id) selectedBooks,count(i.id) total,
            coalesce(sum(i.state IN ('ACCEPTED','SKIPPED','FAILED','CANCELLED')),0) processed,
            coalesce(sum(i.state='ACCEPTED'),0) accepted,coalesce(sum(i.state='FAILED'),0) failed
            FROM calibre_jobs j LEFT JOIN calibre_job_items i ON i.job_id=j.id GROUP BY j.id
            """;
        String projection="""
            SELECT j.id,'cleanup' kind,j.created_at createdAt,'CLEANUP' type,j.state state,j.owner_username username,j.library,
            CASE WHEN j.direct_removal=1 AND j.source='downloads' THEN count(i.id) ELSE coalesce(json_array_length(j.confirmation),0) END selectedBooks,CASE WHEN j.direct_removal=1 AND j.source='downloads' THEN count(i.id) ELSE coalesce(json_array_length(j.confirmation),0) END total,
            coalesce(sum(i.state IN ('SUCCEEDED','BLOCKED','FAILED','UNCERTAIN','CANCELLED') OR i.state='SKIPPED' AND EXISTS(SELECT 1 FROM json_each(j.confirmation) chosen WHERE chosen.value=i.id)),0) processed,
            coalesce(sum(i.state='SUCCEEDED'),0) accepted,coalesce(sum(i.state IN ('BLOCKED','FAILED','UNCERTAIN')),0) failed
            FROM revision_cleanup_jobs j LEFT JOIN revision_cleanup_items i ON i.job_id=j.id
            WHERE j.state<>'PREVIEW' AND j.source=? AND j.owner_id=? GROUP BY j.id
            """;
        var args=new ArrayList<Object>(List.of(source,owner));
        String base="WITH work AS ("+(normal?normalSql+" UNION ALL ":"")+projection+") SELECT * FROM work WHERE 1=1";
        if(states!=null && !states.isEmpty()) {
            var valid=downloads?Set.of("QUEUED","RUNNING","PAUSED","COMPLETED","CANCELLED","RETRY_WAIT","DOWNLOAD_WAIT","CLEANUP_WAIT"):Set.of("QUEUED","RUNNING","PAUSED","COMPLETED","CANCELLED");
            if(!valid.containsAll(states)) throw new UserInputException("Estado de trabajo inválido");
            base+=" AND state "+(exclude?"NOT IN (":"IN (")+String.join(",",Collections.nCopies(states.size(),"?"))+")";args.addAll(states);
        }
        if(!types.isEmpty()) {base+=" AND type "+(excludeType?"NOT IN (":"IN (")+String.join(",",Collections.nCopies(types.size(),"?"))+")";args.addAll(types);}
        if(username!=null && !username.isBlank()) {base+=" AND instr(lower(username),lower(?))>0";args.add(username.trim());}
        if(from!=null) {base+=" AND createdAt>=?";args.add(from.toEpochMilli());}
        if(before!=null) {base+=" AND createdAt<?";args.add(before.toEpochMilli());}
        String order=orders.stream().map(o->{String field=switch(o.getProperty()) {case "jobId"->"id";case "status"->"state";case "progress"->"CASE WHEN total=0 THEN 0 ELSE 1.0*processed/total END";case "username","library"->o.getProperty()+" COLLATE EPL_TEXT";default->o.getProperty();};return field+" "+o.getDirection().name();}).collect(java.util.stream.Collectors.joining(","));
        return new Listing(base,order+",createdAt DESC,id DESC",args,downloads);
    }
    public Neighbors neighbors(String source,String id,List<String> states,String type,String username,Instant from,Instant before,String sort) {
        return neighbors(source,id,states,type,username,from,before,sort,"eq");
    }
    public Neighbors neighbors(String source,String id,List<String> states,String type,String username,Instant from,Instant before,String sort,String statusOperator) {
        return neighbors(source,id,states,type,username,from,before,sort,statusOperator,"eq");
    }
    public Neighbors neighbors(String source,String id,List<String> states,String type,String username,Instant from,Instant before,String sort,String statusOperator,String typeOperator) {
        if(id==null || id.length()>64) throw new UserInputException("Identificador de trabajo inválido");
        var query=query(source,states,type,username,from,before,sort,statusOperator,typeOperator);var args=new ArrayList<>(query.args());args.add(id);
        String window=" OVER (ORDER BY "+query.order()+")";
        var rows=jdbc.queryForList("WITH scoped AS ("+query.base()+"), adjacent AS (SELECT id,row_number()"+window+" position,lag(id)"+window+" previousId,lag(kind)"+window+" previousKind,lead(id)"+window+" nextId,lead(kind)"+window+" nextKind FROM scoped) SELECT * FROM adjacent WHERE id=?",args.toArray());
        if(rows.isEmpty()) return new Neighbors(null,null);
        var row=rows.getFirst();
        return new Neighbors(row.get("previousId")==null?null:new Neighbor((String)row.get("previousId"),"cleanup".equals(row.get("previousKind")),((Number)row.get("position")).longValue()-2),row.get("nextId")==null?null:new Neighbor((String)row.get("nextId"),"cleanup".equals(row.get("nextKind")),((Number)row.get("position")).longValue()));
    }
    public PageResponse<Map<String,Object>> list(String source,int page,int size,List<String> states,String type,String username,Instant from,Instant before,String sort) {
        return list(source,page,size,states,type,username,from,before,sort,"eq");
    }
    public PageResponse<Map<String,Object>> list(String source,int page,int size,List<String> states,String type,String username,Instant from,Instant before,String sort,String statusOperator) {
        return list(source,page,size,states,type,username,from,before,sort,statusOperator,"eq");
    }
    public PageResponse<Map<String,Object>> list(String source,int page,int size,List<String> states,String type,String username,Instant from,Instant before,String sort,String statusOperator,String typeOperator) {
        QueryLimits.page(page,size);
        var query=query(source,states,type,username,from,before,sort,statusOperator,typeOperator);var args=new ArrayList<>(query.args());boolean downloads=query.downloads();
        long total=jdbc.queryForObject("SELECT count(*) FROM ("+query.base()+")",Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var rows=jdbc.queryForList(query.base()+" ORDER BY "+query.order()+" LIMIT ? OFFSET ?",args.toArray());
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            String id=(String)row.get("id");
            Map<String,Object> view;
            if(row.get("kind").equals("normal")) {
                Object value;if(downloads) {synchronized(bulk) {value=bulk.view(id);}}else value=calibre.job(id);
                view=json.readValue(json.writeValueAsString(value),new tools.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});
                view.put("type",row.get("type"));
            } else {
                var job=cleanup.get(id);view=new LinkedHashMap<>();view.put(downloads?"jobId":"id",id);view.put(downloads?"status":"state",job.state());
                view.put("type","CLEANUP");view.put("createdAt",Instant.ofEpochMilli(job.createdAt()).toString());view.put("updatedAt",Instant.ofEpochMilli(job.updatedAt()).toString());view.put("username",job.username());view.put("library",job.library());view.put("result",job.result());view.put("message",job.message());
                view.put("total",job.selected());view.put("selectedBooks",job.selected());view.put("selectedItems",job.selected());view.put("processedItems",row.get("processed"));
                view.put("accepted",job.succeeded());view.put("failed",job.blocked()+job.failed()+job.uncertain());view.put("pending",job.pending());view.put("uncertain",job.uncertain());view.put("skipped",0);
            }
            result.add(view);
        }
        int pages=(int)((total+size-1)/size);
        return new PageResponse<>(result,new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page+1<pages,page>0));
    }
}
