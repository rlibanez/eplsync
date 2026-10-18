package com.rlibanez.eplsync.activity;

import com.rlibanez.eplsync.config.QueryLimits;
import com.rlibanez.eplsync.config.TableOrdering;
import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.ordering.StateOperator;
import com.rlibanez.eplsync.security.Account;
import com.rlibanez.eplsync.security.Permission;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only history from retained records. Never synchronize clients or adopt work here. */
@RestController
@RequestMapping("/api/catalog/books/{eplId}/activity")
@PreAuthorize("hasAuthority('CATALOG_READ')")
public class BookActivityController {
    public enum Operation { DOWNLOAD, DOWNLOAD_UPDATE, DOWNLOAD_COMPLETED, CALIBRE_SEND, DOWNLOAD_CLEANUP, CALIBRE_CLEANUP, DOWNLOAD_DELETE, CALIBRE_DELETE, DOWNLOAD_RETRY, CALIBRE_RETRY }
    public enum Result { PENDING, RUNNING, PAUSED, SUCCEEDED, FAILED, BLOCKED, UNCERTAIN, SKIPPED, CANCELLED }
    public record Entry(String id,Instant createdAt,String operation,String revision,String result,String message,String href) {}
    private final JdbcTemplate jdbc;
    public BookActivityController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private static String millis(String column) {
        return "CASE WHEN typeof("+column+") IN ('integer','real') THEN CAST("+column+" AS INTEGER) ELSE CAST(round((julianday("+column+")-2440587.5)*86400000) AS INTEGER) END";
    }
    private static final String RESULT = """
        CASE WHEN raw_state IN ('FAILED') THEN 'FAILED'
          WHEN raw_state IN ('UNCERTAIN','REQUESTED','VERIFYING') THEN 'UNCERTAIN'
          WHEN raw_state IN ('BLOCKED','BLOCKED_DOWNLOAD') THEN 'BLOCKED'
          WHEN raw_state='CANCELLED' THEN 'CANCELLED'
          WHEN raw_state IN ('SKIPPED','ALREADY_EXISTS','KEPT','EXCLUDED') THEN 'SKIPPED'
          WHEN raw_state IN ('ACCEPTED','SUCCEEDED','REMOVED','COMPLETED') THEN 'SUCCEEDED'
          WHEN job_state='CANCELLED' THEN 'CANCELLED'
          WHEN job_state='PAUSED' THEN 'PAUSED'
          WHEN raw_state IN ('SENDING','IN_FLIGHT','RUNNING') THEN 'RUNNING'
          ELSE 'PENDING' END
        """;
    private static String firstAttempt(String kind) {
        return """
            LEFT JOIN job_retry_attempt_items first_try ON first_try.item_id=i.id AND first_try.attempt_id=(
              SELECT a.id FROM job_retry_attempts a JOIN job_retry_attempt_items x ON x.attempt_id=a.id
              WHERE a.kind='%s' AND a.job_id=j.id AND x.item_id=i.id ORDER BY a.created_at,a.id LIMIT 1)
            """.formatted(kind);
    }
    private static final String INITIAL_STATE = "coalesce(json_extract(first_try.before_item,'$.state'),i.state)";
    private static final String INITIAL_MESSAGE = "CASE WHEN first_try.attempt_id IS NULL THEN i.message ELSE json_extract(first_try.before_item,'$.message') END";
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public PageResponse<Entry> list(@PathVariable long eplId,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String operation,
            @RequestParam(defaultValue="") String result,@RequestParam(defaultValue="eq") String operationOperator,
            @RequestParam(defaultValue="eq") String resultOperator,@RequestParam(defaultValue="createdAt,desc") String sort,
            jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(!Set.of("page","size","operation","result","operationOperator","resultOperator","sort").containsAll(request.getParameterMap().keySet())) throw new UserInputException("Parámetro de actividad desconocido");
        TableOrdering.validateParameters(request);
        QueryLimits.page(page,size);
        var operations=values(operation,Operation.class);var results=values(result,Result.class);
        boolean excludeOperation=StateOperator.excluded(operationOperator),excludeResult=StateOperator.excluded(resultOperator);
        var orders=TableOrdering.parse(TableOrdering.request(request,"createdAt,desc"),Set.of("createdAt","operation","revision","result","message"));
        if(eplId<=0) throw new UserInputException("EPL ID inválido");
        if(jdbc.queryForObject("SELECT count(*) FROM catalog_books WHERE epl_id=?",Long.class,eplId)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Libro no encontrado");
        var queries=new ArrayList<String>();var args=new ArrayList<Object>();
        boolean downloads=Permission.has(Permission.TORRENT_JOBS_MANAGE);
        boolean calibre=Permission.has(Permission.CALIBRE_SEND)||Permission.has(Permission.CALIBRE_JOBS_MANAGE);
        if(downloads) {
            queries.add("SELECT 'download:'||i.id id,"+millis("j.created_at")+" createdAt,CASE WHEN j.type='UPDATE' THEN 'DOWNLOAD_UPDATE' ELSE 'DOWNLOAD' END operation,CAST(i.revision AS TEXT) revision,"+INITIAL_STATE+" raw_state,j.state job_state,"+INITIAL_MESSAGE+" message,'downloads' source,j.id job_id,0 cleanup FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id "+firstAttempt("downloads")+" WHERE i.epl_id=?");args.add(eplId);
            queries.add("SELECT 'torrent-cleanup:'||c.id,"+millis("c.created_at")+",'DOWNLOAD_CLEANUP',CAST(d.revision AS TEXT),c.state,j.state,c.message,'downloads',j.id,0 FROM torrent_update_cleanup c LEFT JOIN torrent_bulk_jobs j ON j.id=c.job_id LEFT JOIN torrent_downloads d ON d.id=c.download_id WHERE c.epl_id=? AND (c.job_id IS NULL OR c.job_id NOT LIKE 'revision:%') AND c.state<>'KEPT'");args.add(eplId);
            retry(queries,args,eplId,"downloads","torrent_bulk_jobs","torrent_bulk_items");
        }
        if(Permission.has(Permission.BOOK_HISTORY_READ)) {
            String job=downloads?"(SELECT i.job_id FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id WHERE i.epl_id=d.epl_id AND i.hash=d.hash ORDER BY "+millis("j.created_at")+" DESC,j.id DESC LIMIT 1)":"NULL";
            queries.add("SELECT 'completed:'||d.id,"+millis("d.completed_at")+",'DOWNLOAD_COMPLETED',CAST(d.revision AS TEXT),'SUCCEEDED',NULL,NULL,'downloads',"+job+",0 FROM torrent_downloads d WHERE d.epl_id=? AND d.completed_at IS NOT NULL");args.add(eplId);
        }
        if(calibre) {
            queries.add("SELECT 'calibre:'||i.id,"+millis("j.created_at")+",'CALIBRE_SEND',i.revision,CASE WHEN "+INITIAL_STATE+"='CLEANUP_PENDING' THEN i.completion_state ELSE "+INITIAL_STATE+" END,j.state,"+INITIAL_MESSAGE+",'calibre',j.id,0 FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id "+firstAttempt("calibre")+" WHERE i.epl_id=?");args.add(eplId);
            queries.add("SELECT 'calibre-cleanup:'||i.id,"+millis("j.created_at")+",'CALIBRE_CLEANUP',NULL,CASE WHEN j.state='CANCELLED' THEN 'CANCELLED' WHEN i.state='CLEANUP_PENDING' THEN 'PENDING' ELSE 'SUCCEEDED' END,j.state,i.message,'calibre',j.id,0 FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND i.cleanup_policy<>'KEEP' AND json_array_length(i.cleanup_ids)>0 AND (i.state='CLEANUP_PENDING' OR i.state IN ('ACCEPTED','SKIPPED') AND i.calibre_id IS NOT NULL)");args.add(eplId);
            retry(queries,args,eplId,"calibre","calibre_jobs","calibre_job_items");
        }
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication.getPrincipal() instanceof Account owner) for(String source:List.of("downloads","calibre")) {
            if(!Permission.has(source.equals("downloads")?Permission.TORRENT_SYNC:Permission.CALIBRE_READ)) continue;
            queries.add("SELECT 'cleanup:'||i.id,j.created_at,CASE WHEN j.direct_removal=1 THEN ? ELSE ? END,i.revision,i.state,j.state,i.message,j.source,j.id,1 FROM revision_cleanup_items i JOIN revision_cleanup_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND j.source=? AND j.owner_id=? AND j.state<>'PREVIEW' AND (i.state NOT IN ('READY','EXCLUDED','SKIPPED') OR EXISTS(SELECT 1 FROM json_each(coalesce(j.confirmation,'[]')) c WHERE c.value=i.id))");
            args.addAll(List.of(source.equals("downloads")?"DOWNLOAD_DELETE":"CALIBRE_DELETE",source.equals("downloads")?"DOWNLOAD_CLEANUP":"CALIBRE_CLEANUP",eplId,source,owner.id()));
        }
        if(queries.isEmpty()) return new PageResponse<>(List.of(),meta(page,size,0));
        String base="WITH activity(id,createdAt,operation,revision,raw_state,job_state,message,source,job_id,cleanup) AS ("+String.join(" UNION ALL ",queries)+"), results AS (SELECT *,"+RESULT+" result FROM activity) SELECT * FROM results WHERE 1=1";
        if(!operations.isEmpty()) {base+=" AND operation "+(excludeOperation?"NOT IN (":"IN (")+String.join(",",Collections.nCopies(operations.size(),"?"))+")";args.addAll(operations);}
        if(!results.isEmpty()) {base+=" AND result "+(excludeResult?"NOT IN (":"IN (")+String.join(",",Collections.nCopies(results.size(),"?"))+")";args.addAll(results);}
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM ("+base+")",Long.class,args.toArray()));
        String order=orders.stream().map(o->(o.getProperty().equals("revision")?"CAST(revision AS REAL)":o.getProperty())+" "+o.getDirection()).collect(java.util.stream.Collectors.joining(","));
        args.add(size);args.add((long)page*size);
        var entries=jdbc.query(base+" ORDER BY "+order+",createdAt DESC,id DESC LIMIT ? OFFSET ?",(r,n)-> {
            Object timestamp=r.getObject("createdAt");String jobId=r.getString("job_id");
            String href=jobId==null?null:"/"+r.getString("source")+"/jobs/"+(r.getBoolean("cleanup")?"cleanup/":"")+org.springframework.web.util.UriUtils.encodePathSegment(jobId,java.nio.charset.StandardCharsets.UTF_8);
            return new Entry(r.getString("id"),timestamp==null?null:Instant.ofEpochMilli(((Number)timestamp).longValue()),r.getString("operation"),r.getString("revision"),r.getString("result"),r.getString("message"),href);
        },args.toArray());
        return new PageResponse<>(entries,meta(page,size,total));
    }
    private static void retry(List<String> queries,List<Object> args,long book,String kind,String jobs,String items) {
        queries.add("SELECT 'retry:'||a.id||':'||r.item_id,a.created_at,?,coalesce(json_extract(r.before_item,'$.revision'),CAST(i.revision AS TEXT)),coalesce(json_extract(r.after_item,'$.state'),i.state,json_extract(r.before_item,'$.state')),j.state,CASE WHEN r.after_item IS NOT NULL THEN json_extract(r.after_item,'$.message') ELSE i.message END,?,a.job_id,0 FROM job_retry_attempts a JOIN job_retry_attempt_items r ON r.attempt_id=a.id JOIN "+jobs+" j ON j.id=a.job_id LEFT JOIN "+items+" i ON i.id=r.item_id AND i.job_id=a.job_id WHERE a.kind=? AND coalesce(json_extract(r.before_item,'$.epl_id'),i.epl_id)=?");
        args.addAll(List.of(kind.equals("downloads")?"DOWNLOAD_RETRY":"CALIBRE_RETRY",kind,kind,book));
    }
    private static <T extends Enum<T>> List<String> values(String input,Class<T> type) {
        if(input.isEmpty()) return List.of();
        try {return Arrays.stream(input.split(",",-1)).map(value->Enum.valueOf(type,Objects.requireNonNull(value).trim()).name()).distinct().toList();}
        catch(IllegalArgumentException ex) {throw new UserInputException("Filtro de actividad inválido");}
    }
    private static PageResponse.PageMeta meta(int page,int size,long total) {int pages=(int)((total+size-1)/size);return new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page+1<pages,page>0);}
}
