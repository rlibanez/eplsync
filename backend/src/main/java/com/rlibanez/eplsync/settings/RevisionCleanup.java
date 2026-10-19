package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.updates.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, frozen and owner-bound previews. Only confirmed jobs enter the bounded worker. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class RevisionCleanup {
    public record PreviewInput(String source,String action,List<String> ids,boolean allowUndownloaded,boolean deleteHistory) {
        public PreviewInput(String source,String action,List<String> ids,boolean allowUndownloaded) {this(source,action,ids,allowUndownloaded,false);}
        public PreviewInput(String source,String action,List<String> ids) {this(source,action,ids,false);}
    }
    public record RetryInput(String requestId,boolean confirm,boolean confirmDestructive) {}
    public record Confirmation(List<String> ids,boolean confirm,boolean confirmDestructive) {}
    public record Job(String id,String source,String action,String state,String result,String message,String username,String library,
        boolean allowUndownloaded,boolean deleteHistory,boolean directRemoval,long createdAt,long updatedAt,long expiresAt,long total,long selected,long ready,long excluded,long pending,long succeeded,long blocked,long failed,long uncertain,boolean libraryCleanup) {}
    public record Item(String id,Long eplId,String title,String revision,String oldId,String replacementId,String replacementRevision,
        List<String> formats,String state,String message,boolean dispatched,String oldHash,String replacementHash,String blockingCalibreJobId,Boolean blockingRequiresIntervention,String author,String reason,boolean previewSelected) {
        public Item(String id,Long eplId,String title,String revision,String oldId,String replacementId,String replacementRevision,List<String> formats,String state,String message,boolean dispatched,String oldHash,String replacementHash,String blockingCalibreJobId,Boolean blockingRequiresIntervention,String author,String reason) {this(id,eplId,title,revision,oldId,replacementId,replacementRevision,formats,state,message,dispatched,oldHash,replacementHash,blockingCalibreJobId,blockingRequiresIntervention,author,reason,false);}
        public Item(String id,Long eplId,String title,String revision,String oldId,String replacementId,String replacementRevision,List<String> formats,String state,String message,boolean dispatched,String oldHash,String replacementHash,String blockingCalibreJobId,Boolean blockingRequiresIntervention,String author) {this(id,eplId,title,revision,oldId,replacementId,replacementRevision,formats,state,message,dispatched,oldHash,replacementHash,blockingCalibreJobId,blockingRequiresIntervention,author,null);}
        public Item(String id,Long eplId,String title,String revision,String oldId,String replacementId,String replacementRevision,List<String> formats,String state,String message,boolean dispatched,String oldHash,String replacementHash,String blockingCalibreJobId,Boolean blockingRequiresIntervention) {this(id,eplId,title,revision,oldId,replacementId,replacementRevision,formats,state,message,dispatched,oldHash,replacementHash,blockingCalibreJobId,blockingRequiresIntervention,null);}
        public Item(String id,Long eplId,String title,String revision,String oldId,String replacementId,String replacementRevision,List<String> formats,String state,String message,boolean dispatched,String oldHash,String replacementHash) {this(id,eplId,title,revision,oldId,replacementId,replacementRevision,formats,state,message,dispatched,oldHash,replacementHash,null,null);}
    }
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RevisionCleanup.class);
    private static final int LIMIT=1000;
    private static final String SELECT="""
        SELECT j.*,count(i.id) total,
        CASE WHEN j.direct_removal=1 AND j.source='downloads' THEN count(i.id) WHEN EXISTS(SELECT 1 FROM calibre_cleanup_preparations p WHERE p.job_id=j.id) THEN coalesce(sum(i.preview_selected),0) ELSE coalesce(json_array_length(j.confirmation),0) END selected,
        coalesce(sum(i.state='READY'),0) ready,coalesce(sum(i.state='EXCLUDED'),0) excluded,
        coalesce(sum(i.state IN ('PENDING','RUNNING','VERIFYING')),0) pending,
        coalesce(sum(i.state='SUCCEEDED'),0) succeeded,coalesce(sum(i.state='BLOCKED'),0) blocked,
        coalesce(sum(i.state='FAILED'),0) failed,coalesce(sum(i.state='UNCERTAIN'),0) uncertain
        FROM revision_cleanup_jobs j LEFT JOIN revision_cleanup_items i ON i.job_id=j.id
        """;
    @org.springframework.beans.factory.annotation.Autowired private CalibreLibraryCleanup libraryCleanup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccountStore accounts;
    private final SessionAccess sessions;
    private final DownloadTrackingService tracking;
    private final EpubFiles files;
    private final TorrentClientService torrent;
    private final HistoryActions history;
    private final ServerSettings server;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final CalibreCatalogService catalog;
    private final CalibreTransfers transfers;
    private final EventJournal events;
    private final MaintenanceGate maintenance;
    private final JsonMapper json=JsonMapper.builder().build();
    private final ReentrantLock lifecycle=new ReentrantLock();
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("revision-cleanup").factory());
    @org.springframework.beans.factory.annotation.Value("${eplsync.revision-cleanup.worker-enabled:true}") private boolean enabled=true;
    public RevisionCleanup(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountStore accounts,SessionAccess sessions,
        DownloadTrackingService tracking,EpubFiles files,TorrentClientService torrent,HistoryActions history,ServerSettings server,
        CalibreSettings settings,CalibreClient client,CalibreCatalogService catalog,CalibreTransfers transfers,EventJournal events,MaintenanceGate maintenance) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.accounts=accounts;this.sessions=sessions;this.tracking=tracking;this.files=files;
        this.torrent=torrent;this.history=history;this.server=server;this.settings=settings;this.client=client;this.catalog=catalog;
        this.transfers=transfers;this.events=events;this.maintenance=maintenance;
    }
    @jakarta.annotation.PostConstruct void recover() {
        tx.executeWithoutResult(status->{
            jdbc.update("UPDATE revision_cleanup_items SET state=CASE WHEN dispatched=1 THEN 'UNCERTAIN' ELSE 'PENDING' END,message='Ejecución interrumpida; se comprobará el resultado antes de continuar' WHERE state='RUNNING'");
            // A dispatched delete is only verified, never dispatched again during startup recovery.
            jdbc.update("UPDATE revision_cleanup_items SET state='VERIFYING' WHERE state='UNCERTAIN' AND dispatched=1 AND job_id IN (SELECT id FROM revision_cleanup_jobs WHERE state IN ('QUEUED','RUNNING') AND cancel_requested=0)");
            jdbc.update("UPDATE revision_cleanup_items SET state=CASE WHEN state='VERIFYING' THEN 'UNCERTAIN' ELSE 'CANCELLED' END WHERE state IN ('PENDING','VERIFYING') AND job_id IN (SELECT id FROM revision_cleanup_jobs WHERE state IN ('QUEUED','RUNNING') AND cancel_requested=1)");
            int recovered=jdbc.update("UPDATE revision_cleanup_jobs SET state=CASE WHEN cancel_requested=1 THEN 'CANCELLED' ELSE 'QUEUED' END,result=CASE WHEN cancel_requested=1 THEN 'CANCELLED' ELSE NULL END,message='Recuperado tras reinicio; se comprobarán los borrados solicitados sin repetirlos',updated_at=? WHERE state='RUNNING' OR state='QUEUED' AND cancel_requested=1",Instant.now().toEpochMilli());
            if(recovered>0) log.info("Recuperación de trabajos de limpieza tras reinicio: recuperados={}",recovered);
        });
    }
    public <T> T exclusive(Supplier<T> work) {
        if(!lifecycle.tryLock()) throw new UserInputException("Hay una limpieza de revisiones en curso; espera a que termine la operación actual");
        try {return work.get();}finally {lifecycle.unlock();}
    }
    private String context(String source) {return source.equals("downloads")?server.updateSearchContext():settings.updateSearchContext();}
    private static String source(String source) {
        if(!Set.of("downloads","calibre").contains(Objects.toString(source,""))) throw new UserInputException("Origen de limpieza inválido");
        return source;
    }
    private static void requireCurrentAction(String source,String action) {
        if(source.equals("calibre") && !"BOOK".equals(action)) throw new UserInputException("La limpieza de Calibre elimina entradas completas; prepara una nueva previsualización y confirma todos sus formatos");
    }
    private static Set<Permission> permissions(String source,String action) {
        var needed=EnumSet.noneOf(Permission.class);
        if(source.equals("downloads")) {
            if(!Set.of("RECORD","TORRENT","FILES").contains(Objects.toString(action,""))) throw new UserInputException("Acción de limpieza inválida");
            needed.addAll(Set.of(Permission.TORRENT_SYNC,Permission.DOWNLOADS_FILES_READ,Permission.DOWNLOADS_DELETE));
            if(!action.equals("RECORD")) needed.add(Permission.TORRENT_CLEANUP);
            if(action.equals("FILES")) needed.add(Permission.TORRENT_FILES_DELETE);
        } else {
            // EPUB is retained only to authorize read-only verification of previously persisted deletions.
            if(!Set.of("EPUB","BOOK").contains(Objects.toString(action,""))) throw new UserInputException("Acción de limpieza inválida");
            needed.addAll(Set.of(Permission.CALIBRE_READ,Permission.CALIBRE_DELETE));
        }
        return needed;
    }
    private Account authorize(String id,String source,String action) {return authorize(id,source,action,false,false);}
    private Account authorize(String id,String source,String action,boolean direct,boolean deleteHistory) {
        var needed=permissions(source,action);
        if(direct) {needed.remove(Permission.DOWNLOADS_FILES_READ);if(!deleteHistory && !action.equals("RECORD")) needed.remove(Permission.DOWNLOADS_DELETE);}
        var account=accounts.find(id);
        if(account==null || !account.status().equals("ACTIVE") || account.mustChangePassword()
            || account.temporaryExpiresAt()!=null && !account.temporaryExpiresAt().isAfter(Instant.now())
            || !account.permissions().containsAll(needed))
            throw new org.springframework.security.access.AccessDeniedException("El propietario no tiene los permisos necesarios para esta limpieza");
        return account;
    }
    private void read(String source) {Permission.require(source.equals("downloads")?Permission.TORRENT_SYNC:Permission.CALIBRE_READ);}
    private Job find(String id) {return jdbc.query(SELECT+" WHERE j.id=? GROUP BY j.id",RevisionCleanup::job,id).stream().findFirst().orElseThrow(()->new UserInputException("El trabajo de limpieza ya no existe"));}
    public Job get(String id) {
        var job=find(id);read(job.source());
        if(!Objects.equals(jdbc.queryForObject("SELECT owner_id FROM revision_cleanup_jobs WHERE id=?",String.class,id),sessions.current().id()))
            throw new org.springframework.security.access.AccessDeniedException("El trabajo de limpieza pertenece a otra cuenta");
        return job;
    }
    public PageResponse<Job> jobs(String source,int page,int size) {
        read(source(source));com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if(source.equals("downloads")) adoptCurrentRemovals();
        String owner=sessions.current().id();
        long total=jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs WHERE source=? AND owner_id=? AND state<>'PREVIEW'",Long.class,source,owner);
        return new PageResponse<>(jdbc.query(SELECT+" WHERE j.source=? AND j.owner_id=? AND j.state<>'PREVIEW' GROUP BY j.id ORDER BY j.created_at DESC,j.id LIMIT ? OFFSET ?",RevisionCleanup::job,source,owner,size,(long)page*size),meta(page,size,total));
    }
    public PageResponse<Item> items(String id,int page,int size) {return items(id,page,size,new org.springframework.util.LinkedMultiValueMap<>());}
    public PageResponse<Item> items(String id,int page,int size,org.springframework.util.MultiValueMap<String,String> filters) {
        var currentJob=get(id);com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        var where=new StringBuilder(" WHERE job_id=?");var args=new ArrayList<Object>();args.add(id);
        JobItemFilters.append(where,args,filters,"book","title");
        String author="(SELECT b.author FROM catalog_books b WHERE b.epl_id=revision_cleanup_items.epl_id)";
        if(filters.containsKey("author")) Permission.require(Permission.CATALOG_READ);
        JobItemFilters.text(where,args,filters,"author",author);

        JobItemFilters.states(where,args,filters.getFirst("state")==null?"":filters.getFirst("state"),filters.getFirst("statusOperator")==null?"eq":filters.getFirst("statusOperator"),Set.of("READY","EXCLUDED","PENDING","RUNNING","VERIFYING","SUCCEEDED","BLOCKED","FAILED","UNCERTAIN","SKIPPED","CANCELLED"));
        long total=jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        String order="position";
        if(filters.getFirst("sort")!=null) {
            var fields=Map.of("eplId","epl_id","title","title COLLATE EPL_TEXT","revision","CAST(revision AS NUMERIC)","replacementRevision","CAST(replacement_revision AS NUMERIC)","state","state");
            var parsed=com.rlibanez.eplsync.config.TableOrdering.parse(filters.getFirst("sort"),fields.keySet());
            order=String.join(",",parsed.stream().map(o->fields.get(o.getProperty())+(o.isAscending()?" ASC":" DESC")).toList())+",position";
        }
        var pageRows=jdbc.query("SELECT *,"+(Permission.has(Permission.CATALOG_READ)?author:"NULL")+" AS item_author FROM revision_cleanup_items"+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->withAuthor(item(r,n),r.getString("item_author")),args.toArray());
        return new PageResponse<>(pageRows.stream().map(row->withAuthor(withBlocker(row,currentJob),row.author())).toList(),meta(page,size,total));
    }
    private static Item withAuthor(Item row,String author) {
        return new Item(row.id(),row.eplId(),row.title(),row.revision(),row.oldId(),row.replacementId(),row.replacementRevision(),row.formats(),row.state(),row.message(),row.dispatched(),row.oldHash(),row.replacementHash(),row.blockingCalibreJobId(),row.blockingRequiresIntervention(),author,row.reason(),row.previewSelected());
    }

    private static PageResponse.PageMeta meta(int page,int size,long total) {int pages=(int)((total+size-1)/size);return new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page+1<pages,page>0);}
    private static Job job(ResultSet r,int n) throws SQLException {return new Job(r.getString("id"),r.getString("source"),r.getString("action"),r.getString("state"),r.getString("result"),r.getString("message"),r.getString("owner_username"),r.getString("library"),r.getBoolean("allow_undownloaded"),r.getBoolean("delete_history"),r.getBoolean("direct_removal"),r.getLong("created_at"),r.getLong("updated_at"),r.getLong("expires_at"),r.getLong("total"),r.getLong("selected"),r.getLong("ready"),r.getLong("excluded"),r.getLong("pending"),r.getLong("succeeded"),r.getLong("blocked"),r.getLong("failed"),r.getLong("uncertain"),r.getString("library_plan")!=null);}
    private Item item(ResultSet r,int n) throws SQLException {
        var formats=new ArrayList<String>();for(var format:json.readTree(r.getString("formats"))) formats.add(format.asString());
        return new Item(r.getString("id"),r.getObject("epl_id")==null?null:((Number)r.getObject("epl_id")).longValue(),r.getString("title"),r.getString("revision"),r.getString("old_id"),r.getString("replacement_id"),r.getString("replacement_revision"),List.copyOf(formats),r.getString("state"),r.getString("message"),r.getBoolean("dispatched"),r.getString("old_hash"),r.getString("replacement_hash"),null,null,null,r.getString("cleanup_reason"),r.getBoolean("preview_selected"));
    }
    private Item withBlocker(Item row,Job job) {
        if(!"BLOCKED".equals(row.state()) || row.oldHash()==null || row.message()==null || !row.message().contains("Calibre")) return row;
        var blocker=history.blockingCalibre(row.oldHash(),tracking.instanceId());
        if(blocker==null) return new Item(row.id(),row.eplId(),row.title(),row.revision(),row.oldId(),row.replacementId(),row.replacementRevision(),row.formats(),row.state(),"El envío a Calibre ya no bloquea este EPUB. Requiere tu intervención: vuelve a intentar la limpieza para comprobar las demás protecciones",row.dispatched(),row.oldHash(),row.replacementHash(),null,true);
        boolean automatic=job.directRemoval() && !Set.of("PAUSED","CANCELLED").contains(job.state()) && !blocker.requiresIntervention()
            && jdbc.queryForObject("SELECT count(*) FROM torrent_update_cleanup WHERE job_id=? AND download_id=? AND state='WAITING'",Long.class,"revision:"+job.id(),row.oldId())>0;
        String detail=blocker.message();
        if(!automatic && !blocker.requiresIntervention()) detail="No se puede borrar: el trabajo de Calibre sigue utilizando este EPUB. Requiere tu intervención: espera a que termine y vuelve a intentar la limpieza";
        return new Item(row.id(),row.eplId(),row.title(),row.revision(),row.oldId(),row.replacementId(),row.replacementRevision(),row.formats(),row.state(),detail,row.dispatched(),row.oldHash(),row.replacementHash(),!(Permission.has(Permission.CALIBRE_SEND)||Permission.has(Permission.CALIBRE_JOBS_MANAGE))?null:blocker.jobId(),!automatic);
    }
    private static String revision(Object value) {return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();}
    private List<Item> candidates(String source,List<String> ids) {
        String sql;
        var args=new ArrayList<Object>();
        if(source.equals("downloads")) {
            // Prefer a completed replacement; otherwise include the pair so validation explains its exclusion.
            sql="""
                SELECT o.id old_id,n.id replacement_id,o.epl_id,coalesce(c.title,'EPL ID '||o.epl_id) title,
                o.revision,n.revision replacement_revision,o.hash old_hash,n.hash replacement_hash
                FROM torrent_downloads o JOIN torrent_downloads n ON n.id=(SELECT r.id FROM torrent_downloads r
                  WHERE r.client_instance_id=o.client_instance_id AND r.epl_id=o.epl_id AND r.revision>o.revision
                  ORDER BY (r.status='DOWNLOADED') DESC,r.revision DESC,r.id LIMIT 1)
                LEFT JOIN catalog_books c ON c.epl_id=o.epl_id
                WHERE o.client_instance_id=? AND o.epl_id>0 AND o.revision>0
                """;args.add(tracking.instanceId());
        } else sql="""
                SELECT cast(o.calibre_id AS TEXT) old_id,cast(n.calibre_id AS TEXT) replacement_id,o.epl_id,o.title,
                o.revision,n.revision replacement_revision,NULL old_hash,NULL replacement_hash
                FROM calibre_books o JOIN calibre_books n ON n.active=true AND n.calibre_id=(SELECT r.calibre_id FROM calibre_books r
                  WHERE r.active=true AND r.epl_id=o.epl_id AND r.revision>o.revision ORDER BY r.revision DESC,r.calibre_id LIMIT 1)
                WHERE o.active=true AND o.epl_id>0 AND o.revision>0
                """;
        if(!ids.isEmpty()) {sql+=" AND "+(source.equals("downloads")?"o.id":"cast(o.calibre_id AS TEXT)")+" IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";args.addAll(ids);}
        sql+=" ORDER BY o.epl_id,o.revision,old_id LIMIT 1001";
        var result=jdbc.query(sql,(r,n)->new Item(UUID.randomUUID().toString(),r.getObject("epl_id")==null?null:((Number)r.getObject("epl_id")).longValue(),r.getString("title").substring(0,Math.min(4096,r.getString("title").length())),revision(r.getObject("revision")),r.getString("old_id"),r.getString("replacement_id"),revision(r.getObject("replacement_revision")),List.of(),"READY",null,false,r.getString("old_hash"),r.getString("replacement_hash")),args.toArray());
        if(result.size()>LIMIT) throw new UserInputException("Hay más de 1000 revisiones antiguas; selecciona un grupo de registros en la tabla y vuelve a preparar la limpieza");
        return result;
    }
    public void adoptCurrentRemovals() {
        Permission.require(Permission.TORRENT_SYNC);
        if(!lifecycle.tryLock()) return;
        try {adoptRemovals();}finally {lifecycle.unlock();}
    }
    /** Attach old manual requests to private jobs without dispatching or changing their policy. */
    private void adoptRemovals() {
        var owner=sessions.current();long now=Instant.now().toEpochMilli();
        tx.executeWithoutResult(status->{
            var pending=jdbc.queryForList("SELECT c.*,d.revision,coalesce(b.title,'EPL ' || c.epl_id) title FROM torrent_update_cleanup c LEFT JOIN torrent_downloads d ON d.id=c.download_id LEFT JOIN catalog_books b ON b.epl_id=c.epl_id WHERE c.job_id LIKE 'manual:%' AND c.actor_id=? AND c.client_instance_id=? AND c.state IN ('WAITING','BLOCKED','REQUESTED') ORDER BY c.created_at,c.id LIMIT 1000",owner.id(),tracking.instanceId());
            for(var entry:pending) {
                String id=UUID.randomUUID().toString();String action=entry.get("previous_versions").equals("REMOVE_TORRENT_AND_FILES")?"FILES":"TORRENT";
                boolean history=Boolean.TRUE.equals(entry.get("delete_history")) || entry.get("delete_history") instanceof Number n && n.intValue()!=0;
                String itemId=UUID.randomUUID().toString();boolean uncertain=entry.get("state").equals("REQUESTED");
                jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,result,confirmation,delete_history,direct_removal,created_at,updated_at,expires_at) VALUES(?,'downloads',?,?,?,?, 'COMPLETED','PARTIAL',?,?,1,?,?,?)",id,action,owner.id(),owner.username(),context("downloads"),"[]",history,now,now,Long.MAX_VALUE);
                jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,old_hash,formats,state,message,dispatched) VALUES(?,?,0,?,'',?,?,?,'',?,'[]',?,?,?)",itemId,id,entry.get("download_id"),entry.get("epl_id"),entry.get("title"),entry.get("revision")==null?"0":revision(entry.get("revision")),entry.get("hash"),uncertain?"UNCERTAIN":"BLOCKED",entry.get("message"),uncertain);
                jdbc.update("UPDATE torrent_update_cleanup SET job_id=? WHERE id=?","revision:"+id,entry.get("id"));
            }
        });
    }
    public record RemovalInput(String requestId,HistoryActions.Removal removal) {}
    public Job removeSelected(RemovalInput input) {return exclusive(()->{
        if(input==null || input.requestId()==null || !input.requestId().matches("[a-f0-9-]{36}") || input.removal()==null) throw new UserInputException("Solicitud de borrado inválida");
        var r=input.removal();
        String action=switch(Objects.toString(r.clientAction(),"")) {case "keep"->"RECORD";case "torrent"->"TORRENT";case "files"->"FILES";default->throw new UserInputException("Acción de borrado inválida");};
        boolean history=Boolean.TRUE.equals(r.deleteHistory());
        if(action.equals("RECORD") && !history || !Boolean.TRUE.equals(r.confirm()) || action.equals("FILES") && !Boolean.TRUE.equals(r.confirmFiles())) throw new UserInputException("Confirma el borrado solicitado");
        if(r.ids()==null || r.ids().isEmpty() || r.ids().stream().anyMatch(v->v==null || v.isBlank() || v.length()>64) || new HashSet<>(r.ids()).size()!=r.ids().size()) throw new UserInputException("Selecciona al menos un registro sin duplicados");
        var owner=authorize(sessions.current().id(),"downloads",action,true,history);
        if(jdbc.queryForObject("SELECT count(*) FROM deleted_work_receipts WHERE source='downloads' AND kind='cleanup' AND request_id=?",Long.class,input.requestId())>0)
            throw new UserInputException("El trabajo de esta solicitud ya fue eliminado; prepara una nueva solicitud de borrado");
        String signature=json.writeValueAsString(input.removal());
        var existing=jdbc.queryForList("SELECT confirmation FROM revision_cleanup_jobs WHERE id=?",String.class,input.requestId());
        if(!existing.isEmpty()) {
            if(!Objects.equals(existing.getFirst(),signature)) throw new UserInputException("La solicitud ya se usó con otras opciones");
            return get(input.requestId());
        }
        String id=input.requestId();long now=Instant.now().toEpochMilli();String current=context("downloads");
        tx.executeWithoutResult(status->{
            String selected=json.writeValueAsString(r.ids());
            var rows=jdbc.queryForList("SELECT d.*,coalesce(b.title,'EPL ' || d.epl_id) title FROM json_each(?) selected JOIN torrent_downloads d ON d.id=selected.value LEFT JOIN catalog_books b ON b.epl_id=d.epl_id ORDER BY CAST(selected.key AS INTEGER)",selected);
            if(rows.size()!=r.ids().size()) throw new UserInputException("El registro ya no existe");
            for(var row:rows) {
                if(r.eplId()!=null && ((Number)row.get("epl_id")).longValue()!=r.eplId()) throw new UserInputException("El registro no pertenece al libro");
                if(!action.equals("RECORD") && !Objects.equals(row.get("client_instance_id"),tracking.instanceId())) throw new UserInputException("El registro pertenece a otro cliente torrent");
            }
            if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items i JOIN revision_cleanup_jobs j ON j.id=i.job_id WHERE i.old_id IN (SELECT value FROM json_each(?)) AND (i.state='UNCERTAIN' OR i.state IN ('PENDING','RUNNING','VERIFYING','BLOCKED','FAILED') AND j.state<>'CANCELLED')",Long.class,selected)>0)
                throw new UserInputException("El registro ya tiene un trabajo de limpieza pendiente; continúa o reintenta ese trabajo desde Trabajos de descargas");
            jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,confirmation,delete_history,direct_removal,created_at,updated_at,expires_at) VALUES(?,'downloads',?,?,?,?, 'QUEUED',?,?,1,?,?,?)",id,action,owner.id(),owner.username(),current,signature,history,now,now,Long.MAX_VALUE);
            var batch=new ArrayList<Object[]>();
            int position=0;
            for(var row:rows) batch.add(new Object[]{UUID.randomUUID().toString(),id,position++,row.get("id"),row.get("epl_id"),row.get("title"),revision(row.get("revision")),row.get("hash")});
            for(int offset=0;offset<batch.size();offset+=500)
                jdbc.batchUpdate("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,old_hash,formats,state) VALUES(?,?,?,?,'',?,?,?,'',?,'[]','PENDING')",batch.subList(offset,Math.min(offset+500,batch.size())));

        });
        events.live(new EventContext.Actor(owner.id(),owner.username(),"USER"),EventJournal.Category.JOB,"REVISION_CLEANUP",EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,id,Map.of("selected",r.ids().size(),"source","downloads"));
        return get(id);
    });}
    private void checkDirectPending(Item item,String id,Job job,boolean retry) {
        var blocker=history.blockingCalibre(item.oldHash(),tracking.instanceId());
        if(blocker!=null) throw new UserInputException(blocker.message());
        if(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND j.state NOT IN ('COMPLETED','CANCELLED','DOWNLOAD_WAIT','CLEANUP_WAIT')",Long.class,item.eplId())>0) throw new UserInputException("Este libro tiene un envío torrent pendiente; resuélvelo antes de borrarlo");
        if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items i JOIN revision_cleanup_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND i.job_id<>? AND (i.state='UNCERTAIN' OR j.state IN ('QUEUED','RUNNING','PAUSED') AND i.state IN ('PENDING','RUNNING','VERIFYING'))",Long.class,item.eplId(),id)>0) throw new UserInputException("Este libro tiene otra limpieza pendiente; resuélvela antes de borrarlo");
        for(var pending:jdbc.queryForList("SELECT * FROM torrent_update_cleanup WHERE epl_id=? AND state IN ('WAITING','BLOCKED','REQUESTED') AND job_id<>?",item.eplId(),"revision:"+id)) {
            if(pending.get("target_hashes")!=null) for(var target:json.readTree(pending.get("target_hashes").toString()))
                if(item.oldHash().equalsIgnoreCase(target.asString())) throw new UserInputException("Este torrent es la revisión que otro trabajo debe conservar; no puede borrarse mientras la actualización esté pendiente");
            if(!Objects.equals(pending.get("hash"),item.oldHash())) continue;
            boolean pendingHistory=Boolean.TRUE.equals(pending.get("delete_history")) || pending.get("delete_history") instanceof Number n && n.intValue()!=0;
            if(actionPolicy(job.action()).equals(pending.get("previous_versions")) && pendingHistory==job.deleteHistory() && (!pending.get("state").equals("REQUESTED") || retry)) continue;
            throw new UserInputException("Existe otro borrado pendiente con una política diferente o sin confirmar; compruébalo antes de continuar");
        }
    }
    private static String actionPolicy(String action) {return action.equals("FILES")?"REMOVE_TORRENT_AND_FILES":action.equals("TORRENT")?"REMOVE_TORRENT":"KEEP";}
    private void checkJobDownload(Job job,Item item,List<RemoteTorrent> remote) {
        if(!job.directRemoval()) {checkDownload(item,job.action(),remote,new HashMap<>(),job.allowUndownloaded());return;}
        var rows=jdbc.queryForList("SELECT * FROM torrent_downloads WHERE id=?",item.oldId());
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            if(((Number)row.get("epl_id")).longValue()!=item.eplId() || !revision(row.get("revision")).equals(item.revision()) || !Objects.equals(row.get("hash"),item.oldHash()) || !job.action().equals("RECORD") && !Objects.equals(row.get("client_instance_id"),tracking.instanceId())) throw new UserInputException("La identidad del registro cambió; prepara un nuevo borrado");
        }
        // Shared files, aliases and replacement torrents are checked again by CleanupQueue before dispatch.
        TorrentRemovalSafety.index(remote);
    }
    Job createLibraryDraft() {
        var owner=authorize(sessions.current().id(),"calibre","BOOK");
        long now=Instant.now().toEpochMilli();String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,library,state,library_plan,created_at,updated_at,expires_at) VALUES(?,'calibre','BOOK',?,?,?,?,'PREVIEW','{\"background\":true}',?,?,?)",id,owner.id(),owner.username(),context("calibre"),settings.connection().library(),now,now,now+Duration.ofMinutes(30).toMillis());
        return get(id);
    }
    void requireLibraryDraft(String id) {
        Permission.require(Permission.CALIBRE_READ);var rows=jdbc.queryForList("SELECT owner_id,source,state,context,library_plan FROM revision_cleanup_jobs WHERE id=?",id);
        if(rows.isEmpty()) throw new UserInputException("La previsualización ya no existe");var row=rows.getFirst();
        if(!Objects.equals(row.get("owner_id"),sessions.current().id())) throw new org.springframework.security.access.AccessDeniedException("La previsualización pertenece a otra cuenta");
        authorize(sessions.current().id(),"calibre","BOOK");
        if(row.get("library_plan")==null || !row.get("source").equals("calibre") || !row.get("state").equals("PREVIEW")) throw new UserInputException("La previsualización ya está confirmada o no es una limpieza de biblioteca");
        if(!Objects.equals(context("calibre"),row.get("context"))) throw new UserInputException("La conexión o biblioteca cambió; prepara una nueva limpieza");
    }
    record LibraryCheck(List<String> formats,String state,String message,String oldIdentity,String retainedIdentity,String retainedFormats) {}
    LibraryCheck checkLibraryCandidate(String id,String itemId,Runnable checkpoint) {
        if(!lifecycle.tryLock()) throw new CalibreTransfers.OperationBusy();
        try {return transfers.exclusive(()->{
            checkpoint.run();requireLibraryDraft(id);
            var item=jdbc.query("SELECT * FROM revision_cleanup_items WHERE job_id=? AND id=?",this::item,id,itemId).stream().findFirst().orElseThrow(java.util.concurrent.CancellationException::new);
            var connection=settings.connection();List<String> formats=List.of();
            try {
                noPending(item,id,false,"BOOK",false,false);checkCalibre(item,connection,false);
                formats=client.formats(connection,Long.parseLong(item.oldId()));
                var old=client.book(connection,Long.parseLong(item.oldId()));var kept=client.book(connection,Long.parseLong(item.replacementId()));checkOldCalibre(item,old);
                if(!Objects.equals(kept.eplId(),item.eplId()) || !sameRevision(kept.revision(),item.replacementRevision())) throw new UserInputException("La copia que se conserva cambió; vuelve a previsualizar");
                var retainedFormats=client.formats(connection,kept.calibreId());
                for(var copy:jdbc.queryForList("SELECT calibre_id,formats FROM calibre_cleanup_copies WHERE job_id=? AND calibre_id IN (?,?)",id,item.oldId(),item.replacementId())) {
                    var expected=json.readValue((String)copy.get("formats"),new tools.jackson.core.type.TypeReference<List<String>>(){});
                    var observed=copy.get("calibre_id").equals(item.oldId())?formats:retainedFormats;
                    if(!new HashSet<>(expected).equals(new HashSet<>(observed))) throw new UserInputException("Los formatos cambiaron mientras se preparaba la limpieza; vuelve a previsualizar");
                }
                boolean review="DUPLICATE".equals(item.reason()) && jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_groups WHERE job_id=? AND retained_id=? AND requires_choice=1",Long.class,id,item.replacementId())>0;
                checkpoint.run();requireLibraryDraft(id);
                return new LibraryCheck(formats,review?"EXCLUDED":"READY",review?"Los duplicados tienen formatos diferentes; elige la copia que se conservará":null,json.writeValueAsString(old),json.writeValueAsString(kept),json.writeValueAsString(retainedFormats));
            } catch(RuntimeException ex) {
                if(ex instanceof java.util.concurrent.CancellationException || ex instanceof CalibreClient.TransientFailure || com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
                return new LibraryCheck(formats,"EXCLUDED",reason(ex),null,null,null);
            }
        });}finally {lifecycle.unlock();}
    }
    Job confirmLibrary(String id,long version,boolean confirm,boolean destructive) {
        var owner=sessions.current();var created=new java.util.concurrent.atomic.AtomicBoolean();
        tx.executeWithoutResult(status->{
            var states=jdbc.queryForList("SELECT p.*,j.state job_state FROM calibre_cleanup_preparations p JOIN revision_cleanup_jobs j ON j.id=p.job_id WHERE p.job_id=?",id);
            if(states.isEmpty()) throw new UserInputException("La previsualización ya no existe");
            var prep=states.getFirst();if(((Number)prep.get("version")).longValue()!=version) throw new UserInputException("La previsualización cambió; actualiza la vista");
            if(!"PREVIEW".equals(prep.get("job_state"))) {get(id);return;}
            requireLibraryDraft(id);
            if(jdbc.queryForObject("SELECT expires_at FROM revision_cleanup_jobs WHERE id=?",Long.class,id)<Instant.now().toEpochMilli()) throw new UserInputException("La previsualización caducó; vuelve a prepararla");
            if(!confirm || !destructive || !"READY".equals(prep.get("state"))) throw new UserInputException("Espera a que termine la preparación y confirma la eliminación");
            if(jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_groups WHERE job_id=? AND requires_choice=1",Long.class,id)>0) throw new UserInputException("Elige las copias con formatos diferentes antes de confirmar");
            long selected=jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items WHERE job_id=? AND preview_selected=1 AND state='READY'",Long.class,id);
            if(selected==0) throw new UserInputException("Selecciona al menos una entrada disponible");
            if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items a JOIN revision_cleanup_items b ON b.job_id=a.job_id AND b.old_id=a.replacement_id WHERE a.job_id=? AND a.preview_selected=1 AND b.preview_selected=1",Long.class,id)>0) throw new UserInputException("La selección incluye una copia que debe conservarse");
            if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs WHERE owner_id=? AND state IN ('QUEUED','RUNNING','PAUSED')",Long.class,owner.id())>=5) throw new UserInputException("Hay cinco limpiezas pendientes; espera a que terminen");
            jdbc.update("UPDATE revision_cleanup_items SET state=CASE WHEN preview_selected=1 THEN 'PENDING' ELSE 'SKIPPED' END WHERE job_id=? AND state='READY'",id);
            jdbc.update("UPDATE revision_cleanup_jobs SET state='QUEUED',confirmation=?,updated_at=? WHERE id=?",json.writeValueAsString(Map.of("libraryVersion",version,"selected",selected)),Instant.now().toEpochMilli(),id);created.set(true);
        });var result=get(id);
        if(created.get()) events.live(new EventContext.Actor(owner.id(),owner.username(),"USER"),EventJournal.Category.JOB,"REVISION_CLEANUP",EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,id,Map.of("selected",result.selected(),"source","calibre"));
        return result;
    }
    public record LibraryPreview(Job job,List<CalibreLibraryCleanup.Group> groups) {}
    public LibraryPreview previewLibrary(CalibreLibraryCleanup.Input input) {return exclusive(()->transfers.exclusive(()->{
        authorize(sessions.current().id(),"calibre","BOOK");
        String original=context("calibre");
        if(input==null) throw new UserInputException("Selecciona una limpieza");
        if(input.previewId()!=null) {
            var job=get(input.previewId());
            if(!job.source().equals("calibre") || !job.state().equals("PREVIEW") || !job.libraryCleanup()) throw new UserInputException("Solo puede cambiarse una previsualización de limpieza de biblioteca");
            if(!Objects.equals(jdbc.queryForObject("SELECT context FROM revision_cleanup_jobs WHERE id=?",String.class,job.id()),original)) throw new UserInputException("La conexión o biblioteca cambió; prepara una nueva limpieza");
        }
        var plan=libraryCleanup.plan(input,settings.connection());
        if(!original.equals(context("calibre"))) throw new UserInputException("La conexión o biblioteca cambió; vuelve a previsualizar");
        var job=prepare(new PreviewInput("calibre","BOOK",input.ids()),plan,input.previewId());
        return new LibraryPreview(job,plan.groups());
    }));}
    public Job preview(PreviewInput input) {return exclusive(()->prepare(input));}
    private Job prepare(PreviewInput input) {return prepare(input,null,null);}
    private Job prepare(PreviewInput input,CalibreLibraryCleanup.Plan libraryPlan,String reuseId) {
        if(input==null) throw new UserInputException("Selecciona una limpieza");
        String source=source(input.source()),requestedAction=input.action();
        if(source.equals("downloads") && "KEEP".equals(requestedAction) && !input.deleteHistory()) throw new UserInputException("Selecciona al menos una acción de limpieza");
        String action=source.equals("downloads") && "KEEP".equals(requestedAction)?"RECORD":requestedAction;
        if(source.equals("calibre") && input.deleteHistory()) throw new UserInputException("El registro de EPL Sync solo se puede borrar en Descargas");
        requireCurrentAction(source,action);
        if(input.allowUndownloaded() && !source.equals("downloads")) throw new UserInputException("Esta opción solo está disponible para Descargas");
        var owner=authorize(sessions.current().id(),source,action);
        List<String> ids=input.ids()==null?List.of():input.ids();
        if(libraryPlan==null && ids.size()>LIMIT || ids.stream().anyMatch(id->id==null || id.isBlank() || id.length()>64) || new HashSet<>(ids).size()!=ids.size()) throw new UserInputException("Selecciona como máximo 1000 registros, sin duplicados");
        long now=Instant.now().toEpochMilli(),deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        jdbc.update("DELETE FROM revision_cleanup_jobs WHERE state='PREVIEW' AND expires_at<? AND NOT EXISTS(SELECT 1 FROM calibre_cleanup_preparations p WHERE p.job_id=revision_cleanup_jobs.id AND p.state IN ('QUEUED','RUNNING'))",now);
        if(reuseId==null && jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs WHERE state='PREVIEW' AND owner_id=?",Long.class,owner.id())>=10) throw new UserInputException("Hay demasiadas previsualizaciones abiertas; espera a que caduquen");
        String context=context(source),id=reuseId==null?UUID.randomUUID().toString():reuseId;
        var candidates=libraryPlan==null?candidates(source,ids):libraryPlan.candidates().stream().map(c->new Item(UUID.randomUUID().toString(),c.eplId(),c.title(),c.revision(),c.oldId(),c.retainedId(),c.retainedRevision(),List.of(),"READY",c.problem(),false,null,null,null,null,null,c.reason())).toList();
        var prepared=new ArrayList<Item>();
        var identities=new HashMap<String,String[]>();
        var displayedFormats=new HashMap<String,Set<String>>();
        if(libraryPlan!=null) for(var group:libraryPlan.groups()) for(var copy:group.copies()) displayedFormats.put(copy.id(),new HashSet<>(copy.formats()));
        var connection=source.equals("calibre")?settings.connection():null;
        var accessible=new HashMap<String,String>();
        java.util.function.Consumer<List<RemoteTorrent>> evaluate=remote->{
            for(var item:candidates) {
                if(Thread.currentThread().isInterrupted() || libraryPlan==null && System.nanoTime()>deadline) throw new UserInputException("La previsualización ha tardado demasiado; selecciona menos registros");
                List<String> formats=List.of();String problem=item.message();
                try {
                    noPending(item,id,input.allowUndownloaded(),action,false,input.deleteHistory() || action.equals("RECORD"));
                    if(source.equals("downloads")) checkDownload(item,action,remote,accessible,input.allowUndownloaded());
                    else {
                        checkCalibre(item,Objects.requireNonNull(connection),false);
                        formats=client.formats(connection,Long.parseLong(item.oldId()));
                        if(libraryPlan!=null) {
                            var old=client.book(connection,Long.parseLong(item.oldId()));var retained=client.book(connection,Long.parseLong(item.replacementId()));
                            checkOldCalibre(item,old);
                            if(!Objects.equals(retained.eplId(),item.eplId()) || !sameRevision(retained.revision(),item.replacementRevision())) throw new UserInputException("La copia que se conserva cambió; vuelve a previsualizar");
                            var retainedFormats=client.formats(connection,retained.calibreId());
                            if(displayedFormats.containsKey(item.oldId()) && !displayedFormats.get(item.oldId()).equals(new HashSet<>(formats)) || displayedFormats.containsKey(item.replacementId()) && !displayedFormats.get(item.replacementId()).equals(new HashSet<>(retainedFormats))) throw new UserInputException("Los formatos cambiaron mientras se preparaba la limpieza; vuelve a previsualizar");
                            identities.put(item.id(),new String[]{json.writeValueAsString(old),json.writeValueAsString(retained),json.writeValueAsString(retainedFormats)});
                        }
                    }
                } catch(RuntimeException ex) {problem=reason(ex);}
                prepared.add(new Item(item.id(),item.eplId(),item.title(),item.revision(),item.oldId(),item.replacementId(),item.replacementRevision(),formats,problem==null?"READY":"EXCLUDED",problem,false,item.oldHash(),item.replacementHash(),null,null,null,item.reason()));
            }
        };
        if(source.equals("downloads")) torrent.exclusiveClient(adapter->{evaluate.accept(adapter.listTorrents());return null;});else evaluate.accept(List.of());
        if(!context.equals(context(source))) throw new UserInputException("La conexión cambió; vuelve a preparar la limpieza");
        tx.executeWithoutResult(status->{
            if(reuseId!=null) {
                if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs WHERE id=? AND owner_id=? AND state='PREVIEW' AND library_plan IS NOT NULL AND context=?",Long.class,id,owner.id(),context)!=1) throw new UserInputException("La previsualización cambió o ya está confirmada");
                jdbc.update("DELETE FROM revision_cleanup_items WHERE job_id=?",id);
            }
            if(reuseId==null)
            jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,library,allow_undownloaded,delete_history,state,created_at,updated_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'PREVIEW',?,?,?)",id,source,action,owner.id(),owner.username(),context,connection==null?null:connection.library(),input.allowUndownloaded(),source.equals("downloads") && (action.equals("RECORD") || input.deleteHistory()),now,now,now+Duration.ofMinutes(30).toMillis());
            int position=0;
            for(var item:prepared) jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,old_hash,replacement_hash,formats,state,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",item.id(),id,position++,item.oldId(),item.replacementId(),item.eplId(),item.title(),item.revision(),item.replacementRevision(),item.oldHash(),item.replacementHash(),json.writeValueAsString(item.formats()),item.state(),item.message());
            if(libraryPlan!=null) {
                jdbc.update("UPDATE revision_cleanup_jobs SET library_plan=?,updated_at=?,expires_at=? WHERE id=?",json.writeValueAsString(Map.of("groups",libraryPlan.groups().size())),now,now+Duration.ofMinutes(30).toMillis(),id);
                for(var item:prepared) {
                    var identity=identities.get(item.id());
                    jdbc.update("UPDATE revision_cleanup_items SET cleanup_reason=?,calibre_identity=?,retained_identity=?,retained_formats=? WHERE id=?",item.reason(),identity==null?null:identity[0],identity==null?null:identity[1],identity==null?null:identity[2],item.id());
                }
            }
        });
        return get(id);
    }
    public Job start(String id,Confirmation confirmation) {
        var job=get(id);requireCurrentAction(job.source(),job.action());var owner=authorize(sessions.current().id(),job.source(),job.action(),job.directRemoval(),job.deleteHistory());
        if(confirmation==null || !confirmation.confirm() || confirmation.ids()==null || confirmation.ids().isEmpty() || (!job.directRemoval() && confirmation.ids().size()>LIMIT)
            || confirmation.ids().stream().anyMatch(value->value==null || value.length()!=36) || new HashSet<>(confirmation.ids()).size()!=confirmation.ids().size()) throw new UserInputException("Selecciona las revisiones y confirma la limpieza");
        if(Set.of("FILES","BOOK").contains(job.action()) && !confirmation.confirmDestructive()) throw new UserInputException("Confirma explícitamente la eliminación irreversible de archivos o de entradas completas");
        if(jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_preparations WHERE job_id=?",Long.class,id)>0) throw new UserInputException("Confirma la selección desde la previsualización de la biblioteca");
        String signature=json.writeValueAsString(confirmation.ids().stream().sorted().toList());
        String current=context(job.source());
        boolean created=Boolean.TRUE.equals(tx.execute(status->{
            var stored=jdbc.queryForMap("SELECT * FROM revision_cleanup_jobs WHERE id=?",id);
            if(!stored.get("state").equals("PREVIEW")) {
                if(!Objects.equals(stored.get("confirmation"),signature)) throw new UserInputException("Esta previsualización ya se confirmó con otra selección");
                return false;
            }
            if(((Number)stored.get("expires_at")).longValue()<Instant.now().toEpochMilli() || !stored.get("context").equals(current)) throw new UserInputException("La previsualización caducó o cambió la conexión; vuelve a preparar la limpieza");
            if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs WHERE owner_id=? AND state IN ('QUEUED','RUNNING','PAUSED')",Long.class,owner.id())>=5) throw new UserInputException("Hay cinco limpiezas pendientes; termina o cancela alguna antes de crear otra");
            if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items WHERE id IN (SELECT value FROM json_each(?)) AND job_id=? AND state='READY'",Long.class,signature,id)!=confirmation.ids().size()) throw new UserInputException("La selección contiene una revisión que no puede eliminarse");
            if(job.source().equals("calibre") && jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items a JOIN revision_cleanup_items b ON b.job_id=a.job_id AND b.old_id=a.replacement_id WHERE a.job_id=? AND a.id IN (SELECT value FROM json_each(?)) AND b.id IN (SELECT value FROM json_each(?))",Long.class,id,signature,signature)>0) throw new UserInputException("La selección incluye una copia que debe conservarse");
            jdbc.update("UPDATE revision_cleanup_items SET state='SKIPPED' WHERE job_id=? AND state='READY'",id);
            jdbc.update("UPDATE revision_cleanup_items SET state='PENDING' WHERE id IN (SELECT value FROM json_each(?)) AND job_id=?",signature,id);
            jdbc.update("UPDATE revision_cleanup_jobs SET state='QUEUED',confirmation=?,updated_at=? WHERE id=?",signature,Instant.now().toEpochMilli(),id);return true;
        }));
        if(created) events.live(new EventContext.Actor(owner.id(),owner.username(),"USER"),EventJournal.Category.JOB,"REVISION_CLEANUP",EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,id,Map.of("selected",confirmation.ids().size(),"source",job.source()));
        return get(id);
    }
    public Job retryUnconfirmed(String id,RetryInput input) {return exclusive(()->{
        var job=get(id);requireCurrentAction(job.source(),job.action());authorize(sessions.current().id(),job.source(),job.action(),job.directRemoval(),job.deleteHistory());
        if(!job.source().equals("downloads") || job.action().equals("RECORD")) throw new UserInputException("Solo se pueden reenviar los borrados de torrents de este trabajo");
        if(input==null || !input.confirm() || input.requestId()==null || !input.requestId().matches("[A-Za-z0-9-]{8,64}")) throw new UserInputException("Confirma el reintento de los borrados sin confirmar");
        if(job.action().equals("FILES") && !input.confirmDestructive()) throw new UserInputException("Confirma explícitamente la eliminación irreversible de archivos");
        tx.executeWithoutResult(status->{
            var previous=jdbc.queryForList("SELECT job_id FROM revision_cleanup_retries WHERE request_id=?",String.class,input.requestId());
            if(!previous.isEmpty()) {if(!previous.getFirst().equals(id)) throw new UserInputException("La solicitud pertenece a otro trabajo");return;}
            var stored=jdbc.queryForMap("SELECT * FROM revision_cleanup_jobs WHERE id=?",id);
            if(Set.of("PREVIEW","QUEUED","RUNNING").contains(stored.get("state")) || job.pending()>0) throw new UserInputException("Espera a que terminen los elementos pendientes");
            if(!Objects.equals(stored.get("context"),context(job.source()))) throw new UserInputException("La conexión cambió; restaura el destino del trabajo antes de reintentarlo");
            // Preserve dispatched=true: this durable one-shot intent may reuse only this job's removal request.
            if(jdbc.update("UPDATE revision_cleanup_items SET state='PENDING',message='Reintento solicitado explícitamente por el usuario' WHERE job_id=? AND state='UNCERTAIN' AND dispatched=1",id)==0) throw new UserInputException("No hay borrados sin confirmar para reintentar");
            jdbc.update("INSERT INTO revision_cleanup_retries(request_id,job_id,created_at) VALUES(?,?,?)",input.requestId(),id,Instant.now().toEpochMilli());
            jdbc.update("UPDATE revision_cleanup_jobs SET state='QUEUED',result=NULL,message=NULL,cancel_requested=0,updated_at=? WHERE id=?",Instant.now().toEpochMilli(),id);
        });return get(id);
    });}
    public Job control(String id,String command) {
        var job=get(id);
        if(!Set.of("cancel","continue","verify").contains(command)) throw new UserInputException("Acción desconocida");
        tx.executeWithoutResult(status->{
            if(command.equals("cancel")) {
                if(!Set.of("QUEUED","RUNNING","PAUSED").contains(job.state()) && !(job.directRemoval() && job.blocked()+job.failed()>0)) throw new UserInputException("El trabajo no está pendiente");
                jdbc.update("UPDATE revision_cleanup_jobs SET cancel_requested=1,updated_at=? WHERE id=?",Instant.now().toEpochMilli(),id);
                if(job.directRemoval()) jdbc.update("UPDATE torrent_update_cleanup SET state='CANCELLED' WHERE job_id=? AND state IN ('WAITING','BLOCKED')","revision:"+id);
                if(job.directRemoval()) jdbc.update("UPDATE revision_cleanup_items SET state='CANCELLED' WHERE job_id=? AND state IN ('BLOCKED','FAILED')",id);
                jdbc.update("UPDATE revision_cleanup_items SET state=CASE WHEN state='VERIFYING' THEN 'UNCERTAIN' ELSE 'CANCELLED' END,message='Cancelado antes de iniciar la operación' WHERE job_id=? AND state IN ('PENDING','VERIFYING')",id);
                if(!job.state().equals("RUNNING")) jdbc.update("UPDATE revision_cleanup_jobs SET state='CANCELLED',result='CANCELLED' WHERE id=?",id);
            } else {
                authorize(sessions.current().id(),job.source(),job.action(),job.directRemoval(),job.deleteHistory());
                if(Set.of("PREVIEW","QUEUED","RUNNING").contains(job.state())) throw new UserInputException("Espera a que termine la operación actual");
                if(command.equals("continue")) requireCurrentAction(job.source(),job.action());
                if(command.equals("verify")) {
                    if(job.pending()>0) throw new UserInputException("Continúa o cancela los elementos pendientes antes de comprobar los borrados sin confirmar");
                    if(jdbc.update("UPDATE revision_cleanup_items SET state='VERIFYING' WHERE job_id=? AND state='UNCERTAIN'",id)==0) throw new UserInputException("No hay borrados sin confirmar");
                } else {
                    if(!job.state().equals("PAUSED") && !(job.directRemoval() && job.blocked()+job.failed()>0)) throw new UserInputException("El trabajo no está interrumpido");
                    if(job.directRemoval()) jdbc.update("UPDATE revision_cleanup_items SET state='PENDING' WHERE job_id=? AND state IN ('BLOCKED','FAILED')",id);
                }
                jdbc.update("UPDATE revision_cleanup_jobs SET state='QUEUED',result=NULL,message=NULL,cancel_requested=0 WHERE id=?",id);
            }
        });return get(id);
    }
    private void noPending(Item item,String job,boolean force,String action,boolean explicitRetry,boolean deleteHistory) {
        long active=jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND j.state NOT IN ('COMPLETED','CANCELLED') AND (?=0 OR j.state NOT IN ('CLEANUP_WAIT','DOWNLOAD_WAIT'))",Long.class,item.eplId(),force);
        active+=jdbc.queryForObject("SELECT count(*) FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND (j.state IN ('QUEUED','RUNNING','PAUSED') OR i.state IN ('SENDING','UNCERTAIN','CLEANUP_PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD'))",Long.class,item.eplId());
        for(var pending:jdbc.queryForList("SELECT * FROM torrent_update_cleanup WHERE epl_id=? AND state IN ('WAITING','BLOCKED','REQUESTED') AND coalesce(job_id,'')<>?",item.eplId(),"revision:"+job)) {
            // Only an identical automatic deletion, including its local-record choice, can be fulfilled early.
            boolean pendingHistory=Boolean.TRUE.equals(pending.get("delete_history")) || pending.get("delete_history") instanceof Number history && history.intValue()!=0;
            boolean compatible=pendingHistory==deleteHistory && force && !action.equals("RECORD") && (!pending.get("state").equals("REQUESTED") || explicitRetry)
                && pending.get("automatic") instanceof Number automatic && automatic.intValue()==1
                && Objects.equals(pending.get("client_instance_id"),tracking.instanceId())
                && Objects.equals(pending.get("hash"),item.oldHash())
                && Objects.equals(pending.get("previous_versions"),action.equals("FILES")?"REMOVE_TORRENT_AND_FILES":"REMOVE_TORRENT")
                && pending.get("target_hashes")!=null && json.readTree(pending.get("target_hashes").toString()).isArray();
            if(compatible) {
                compatible=false;
                for(var target:json.readTree(pending.get("target_hashes").toString())) if(item.replacementHash().equalsIgnoreCase(target.asString())) compatible=true;
            }
            if(!compatible) active++;
        }
        active+=jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items i JOIN revision_cleanup_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND i.id<>? AND (i.job_id<>? OR i.state='UNCERTAIN') AND (i.state='UNCERTAIN' OR j.state IN ('QUEUED','RUNNING','PAUSED') AND i.state IN ('PENDING','RUNNING','VERIFYING'))",Long.class,item.eplId(),item.id(),job);
        if(active>0) throw new UserInputException("Este libro tiene trabajos o borrados pendientes; resuélvelos antes de limpiar sus revisiones");
    }
    private void checkDownload(Item item,String action,List<RemoteTorrent> remote,Map<String,String> checked,boolean force) {
        var rows=jdbc.queryForList("SELECT * FROM torrent_downloads WHERE id IN (?,?)",item.oldId(),item.replacementId());
        var old=rows.stream().filter(r->r.get("id").equals(item.oldId())).findFirst().orElse(null);
        var newer=rows.stream().filter(r->r.get("id").equals(item.replacementId())).findFirst().orElseThrow(()->new UserInputException("La revisión que se conservaba ya no existe"));
        if(old!=null && (!Objects.equals(old.get("epl_id"),newer.get("epl_id")) || !revision(old.get("revision")).equals(item.revision()) || !old.get("hash").equals(item.oldHash()))) throw new UserInputException("La identidad de la revisión antigua cambió");
        if(((Number)newer.get("epl_id")).longValue()!=item.eplId() || !revision(newer.get("revision")).equals(item.replacementRevision()) || !newer.get("hash").equals(item.replacementHash())
            || !newer.get("client_instance_id").equals(tracking.instanceId()) || (!force && !newer.get("status").equals("DOWNLOADED")) || new BigDecimal(item.replacementRevision()).compareTo(new BigDecimal(item.revision()))<=0)
            throw new UserInputException("La revisión que se conservaba cambió o no está descargada");
        if(old!=null && !old.get("client_instance_id").equals(tracking.instanceId())) throw new UserInputException("El registro pertenece a otro cliente torrent");
        var byHash=TorrentRemovalSafety.index(remote);var replacement=byHash.get(item.replacementHash());var previous=byHash.get(item.oldHash());
        if(replacement==null || (!force && replacement.status()!=DownloadStatus.DOWNLOADED)) throw new UserInputException("La nueva revisión no está completamente descargada en el cliente");
        if(previous!=null && previous.aliases().stream().anyMatch(replacement.aliases()::contains)) throw new UserInputException("Las revisiones comparten torrent; no puede eliminarse la copia que se conserva");
        if(!force && !checked.containsKey(item.replacementId())) {
            String problem=null;try {files.list(item.replacementId());}catch(RuntimeException ex) {problem="El EPUB de la revisión que se conserva no está accesible";}
            checked.put(item.replacementId(),problem==null?"":problem);
        }
        if(!force && !checked.get(item.replacementId()).isEmpty()) throw new UserInputException(checked.get(item.replacementId()));
        if(!action.equals("RECORD") && previous!=null) {
            if(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE client_instance_id=? AND upper(hash)=? AND epl_id<>?",Long.class,tracking.instanceId(),previous.hash(),item.eplId())>0) throw new UserInputException("El torrent está compartido con otro libro");
            if(action.equals("FILES") && !(previous.awaitingMetadataWithoutData() && (previous.contentPath()==null || previous.contentPath().isBlank()))) {
                var safety=TorrentRemovalSafety.assess(previous,TorrentRemovalSafety.paths(remote));
                if(safety!=TorrentRemovalSafety.PathSafety.EXCLUSIVE) throw new UserInputException(safety==TorrentRemovalSafety.PathSafety.SHARED?"Los archivos están compartidos con otro torrent":"No puede verificarse que las rutas sean exclusivas");
            }
        }
    }
    public record CalibreRemoval(List<Long> ids) {}
    public Job previewCalibreRemoval(CalibreRemoval input) {return exclusive(()->transfers.exclusive(()->{
        var owner=authorize(sessions.current().id(),"calibre","BOOK");
        if(input==null || input.ids()==null || input.ids().isEmpty() || input.ids().stream().anyMatch(v->v==null || v<1) || new HashSet<>(input.ids()).size()!=input.ids().size()) throw new UserInputException("Selecciona entradas de Calibre sin duplicados");
        String id=UUID.randomUUID().toString();long now=Instant.now().toEpochMilli();
        String frozenContext=context("calibre");var connection=settings.connection();
        var prepared=new ArrayList<Object[]>();int position=0;
        for(long bookId:input.ids()) {
            var observed=jdbc.queryForList("SELECT * FROM calibre_books WHERE active=1 AND calibre_id=?",bookId);
            if(observed.size()!=1) throw new UserInputException("La biblioteca ha cambiado; sincroniza y vuelve a seleccionar las entradas");
            var row=observed.getFirst();
            String itemId=UUID.randomUUID().toString(),state="READY",message=null,identity=null;
            List<String> formats=List.of();
            Long epl=row.get("epl_id")==null?null:((Number)row.get("epl_id")).longValue();
            var candidate=new Item(itemId,epl,Objects.toString(row.get("title"),""),Objects.toString(row.get("revision"),""),Long.toString(bookId),"","",List.of(),"READY",null,false,null,null);
            try {
                checkDirectCalibrePending(candidate,id);
                var remote=client.book(connection,bookId);
                if(remote.calibreId()!=bookId || !Objects.equals(remote.eplId(),epl) || !sameRevision(remote.revision(),row.get("revision")) || !Objects.equals(remote.title(),row.get("title")) || !Objects.equals(String.join(", ",remote.authors()),row.get("authors"))) throw new UserInputException("Los metadatos cambiaron; sincroniza Calibre antes de borrar");
                identity=json.writeValueAsString(remote);
                formats=client.formats(connection,bookId);
            } catch(RuntimeException ex) {state="EXCLUDED";message=reason(ex);}
            prepared.add(new Object[]{itemId,id,position++,Long.toString(bookId),epl,candidate.title(),candidate.revision(),json.writeValueAsString(formats),state,message,identity});
        }
        if(!frozenContext.equals(context("calibre"))) throw new UserInputException("La conexión o biblioteca cambió");
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,library,state,direct_removal,created_at,updated_at,expires_at) VALUES(?,'calibre','BOOK',?,?,?,?,'PREVIEW',1,?,?,?)",id,owner.id(),owner.username(),frozenContext,connection.library(),now,now,now+Duration.ofMinutes(30).toMillis());
            for(int offset=0;offset<prepared.size();offset+=500) jdbc.batchUpdate("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,formats,state,message,calibre_identity) VALUES(?,?,?,?,'',?,?,?,'',?,?,?,?)",prepared.subList(offset,Math.min(offset+500,prepared.size())));
        });
        return get(id);
    }));}
    private static boolean sameRevision(BigDecimal revision,Object stored) {
        return revision==null?stored==null:stored!=null && revision.compareTo(new BigDecimal(stored.toString()))==0;
    }
    private void checkDirectCalibrePending(Item item,String job) {
        long active=jdbc.queryForObject("SELECT count(*) FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE (i.calibre_id=? OR i.epl_id=? OR EXISTS (SELECT 1 FROM json_each(i.cleanup_ids) previous WHERE CAST(previous.value AS TEXT)=?)) AND (j.state IN ('QUEUED','RUNNING','PAUSED') OR i.state IN ('SENDING','UNCERTAIN','CLEANUP_PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD'))",Long.class,Long.parseLong(item.oldId()),item.eplId(),item.oldId());
        active+=jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items i JOIN revision_cleanup_jobs j ON j.id=i.job_id WHERE j.source='calibre' AND i.job_id<>? AND (i.old_id=? OR i.replacement_id=? OR i.epl_id=?) AND (i.state='UNCERTAIN' OR j.state IN ('QUEUED','RUNNING','PAUSED') AND i.state IN ('PENDING','RUNNING','VERIFYING','BLOCKED','FAILED'))",Long.class,job,item.oldId(),item.oldId(),item.eplId());
        if(active>0) throw new UserInputException("Esta entrada tiene envíos o borrados pendientes en Calibre; resuélvelos antes de eliminarla");
    }
    private void checkDirectCalibre(Item item,CalibreSettings.Connection connection,boolean allowAbsent) {
        var current=bookOrNull(connection,Long.parseLong(item.oldId()));
        if(current==null) {if(allowAbsent) return;throw new UserInputException("La entrada ya no existe en Calibre");}
        String frozen=jdbc.queryForObject("SELECT calibre_identity FROM revision_cleanup_items WHERE id=?",String.class,item.id());
        if(frozen==null || !frozen.equals(json.writeValueAsString(current))) throw new UserInputException("La entrada de Calibre cambió desde la previsualización; no se borrará");
        var expected=new HashSet<>(item.formats());var actual=new HashSet<>(client.formats(connection,current.calibreId()));
        if(!expected.equals(actual)) throw new UserInputException("Los formatos de Calibre cambiaron desde la previsualización; no se borrará");
    }
    private void checkCalibre(Item item,CalibreSettings.Connection c,boolean allowAbsentOld) {
        checkRetainedCalibre(item,c);
        var old=bookOrNull(c,Long.parseLong(item.oldId()));
        if(old==null) {if(allowAbsentOld) return;throw new UserInputException("La revisión antigua ya no existe en Calibre");}
        checkOldCalibre(item,old);
        checkFrozenCalibre(item,c,old,false);
    }
    private void checkRetainedCalibre(Item item,CalibreSettings.Connection c) {
        long retained=Long.parseLong(item.replacementId());
        if(item.oldId().equals(item.replacementId()) || !validRetainedRevision(item))
            throw new UserInputException("La revisión que se conserva debe ser distinta y estrictamente superior");
        var newer=bookOrNull(c,retained);
        if(newer==null) throw new UserInputException("La copia que se conserva ya no existe en Calibre; no se eliminará ninguna copia");
        if(newer.calibreId()!=retained || !Objects.equals(newer.eplId(),item.eplId()) || newer.revision()==null || newer.revision().compareTo(new BigDecimal(item.replacementRevision()))!=0 || !client.hasEpub(c,retained))
            throw new UserInputException("La revisión que se conserva no está verificada con EPUB e identificadores");
        checkFrozenCalibre(item,c,newer,true);
        if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items i JOIN revision_cleanup_jobs j ON j.id=i.job_id WHERE j.source='calibre' AND i.old_id=? AND (i.state='UNCERTAIN' OR j.state IN ('QUEUED','RUNNING','PAUSED') AND i.state IN ('PENDING','RUNNING','VERIFYING'))",Long.class,item.replacementId())>0) throw new UserInputException("La copia que se conserva tiene un borrado pendiente; no se eliminará ninguna copia");
    }
    private static boolean validRetainedRevision(Item item) {
        int comparison=new BigDecimal(item.revision()).compareTo(new BigDecimal(item.replacementRevision()));
        return comparison<0 && !"DUPLICATE".equals(item.reason()) || comparison==0 && "DUPLICATE".equals(item.reason());
    }
    private void checkFrozenCalibre(Item item,CalibreSettings.Connection connection,CalibreClient.Book book,boolean retained) {
        var rows=jdbc.queryForList("SELECT cleanup_reason,calibre_identity,retained_identity,retained_formats,preview_checked FROM revision_cleanup_items WHERE id=?",item.id());
        if(rows.isEmpty() || rows.getFirst().get("cleanup_reason")==null || !Boolean.TRUE.equals(rows.getFirst().get("preview_checked")) && !(rows.getFirst().get("preview_checked") instanceof Number n && n.intValue()!=0)) return;
        var row=rows.getFirst();String snapshot=(String)row.get(retained?"retained_identity":"calibre_identity");
        if(snapshot==null || !snapshot.equals(json.writeValueAsString(book))) throw new UserInputException("La entrada de Calibre cambió desde la previsualización; no se borrará");
        var formats=retained?json.readValue((String)row.get("retained_formats"),new tools.jackson.core.type.TypeReference<List<String>>(){}):item.formats();
        if(!new HashSet<>(formats).equals(new HashSet<>(client.formats(connection,book.calibreId())))) throw new UserInputException("Los formatos de Calibre cambiaron desde la previsualización; no se borrará");
    }
    private void checkOldCalibre(Item item,CalibreClient.Book old) {
        if(old.calibreId()!=Long.parseLong(item.oldId()) || !Objects.equals(old.eplId(),item.eplId()) || old.revision()==null || old.revision().compareTo(new BigDecimal(item.revision()))!=0 || !validRetainedRevision(item))
            throw new UserInputException("Los metadatos de las revisiones cambiaron; no se eliminará el libro");
    }
    private CalibreClient.Book bookOrNull(CalibreSettings.Connection c,long id) {
        try {return client.book(c,id);}catch(org.springframework.web.server.ResponseStatusException ex) {if(ex.getStatusCode().value()==404) return null;throw ex;}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000) public void schedule() {
        if(enabled && idleProbe.ready() && running.compareAndSet(false,true)) try {worker.execute(()->{try {cycle();}catch(RuntimeException ex) {log.warn("No se pudo completar el ciclo de limpieza: tipo={}",ex.getClass().getSimpleName());}finally {running.set(false);}});}catch(RejectedExecutionException ex) {running.set(false);}
    }
    private final com.rlibanez.eplsync.config.WorkerActivity.Probe idleProbe=new com.rlibanez.eplsync.config.WorkerActivity.Probe();
    /** No network calls or filesystem reads run on Spring's scheduler or under a SQL transaction. */
    public void cycle() {
        if(!lifecycle.tryLock()) return;
        try {
            if(!maintenance.enter(false)) return;
            try {
                long beforeQuery=idleProbe.snapshot();
                var jobs=jdbc.queryForList("SELECT id FROM revision_cleanup_jobs WHERE state IN ('QUEUED','RUNNING') ORDER BY updated_at,id LIMIT 1",String.class);
                if(jobs.isEmpty()) {idleProbe.empty(beforeQuery);return;}
                String id=jobs.getFirst();
                // Only remote deletions need verification. A local history deletion can be retried idempotently.
                jdbc.update("UPDATE revision_cleanup_items SET state=CASE WHEN dispatched=1 AND (SELECT action FROM revision_cleanup_jobs WHERE id=?)<>'RECORD' THEN 'VERIFYING' ELSE 'PENDING' END WHERE job_id=? AND state='RUNNING'",id,id);
                jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING',message=NULL,updated_at=? WHERE id=? AND state='QUEUED'",Instant.now().toEpochMilli(),id);
                long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                for(int n=0;n<20 && !Thread.currentThread().isInterrupted() && System.nanoTime()<deadline;n++) {
                    if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs WHERE id=? AND cancel_requested=0",Long.class,id)==0) break;
                    var pending=jdbc.query("SELECT * FROM revision_cleanup_items WHERE job_id=? AND state IN ('PENDING','VERIFYING') ORDER BY position LIMIT 1",this::item,id);
                    if(pending.isEmpty()) break;
                    try {execute(id,pending.getFirst());}
                    catch(CalibreTransfers.OperationBusy | com.rlibanez.eplsync.exception.TorrentBusyException ex) {
                        String waiting=ex instanceof CalibreTransfers.OperationBusy ? "Esperando a que termine la operación de Calibre; la limpieza se reintentará automáticamente" : "Esperando a que termine la operación del cliente torrent; la limpieza se reintentará automáticamente";
                        jdbc.update("UPDATE revision_cleanup_jobs SET message=?,updated_at=? WHERE id=?",waiting,Instant.now().toEpochMilli(),id);
                        break;
                    }
                }
                finish(id);
            } finally {maintenance.leave(false);}
        } finally {lifecycle.unlock();}
    }
    private void execute(String id,Item item) {
        var job=find(id);var stored=jdbc.queryForMap("SELECT owner_id,context FROM revision_cleanup_jobs WHERE id=?",id);
        var previous=SecurityContextHolder.getContext();
        boolean verify=item.state().equals("VERIFYING");
        try {
            var owner=authorize((String)stored.get("owner_id"),job.source(),job.action(),job.directRemoval(),job.deleteHistory());
            var security=SecurityContextHolder.createEmptyContext();var authorities=new ArrayList<SimpleGrantedAuthority>();owner.permissions().forEach(p->authorities.add(new SimpleGrantedAuthority(p.name())));authorities.add(new SimpleGrantedAuthority("ROLE_"+owner.role()));security.setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,authorities));SecurityContextHolder.setContext(security);
            EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->EventContext.withOrigin(EventContext.Origin.MANUAL,()->transfers.exclusive(()->{
                Runnable guard=()->{authorize(owner.id(),job.source(),job.action(),job.directRemoval(),job.deleteHistory());if(!Objects.equals(stored.get("context"),context(job.source()))) throw new UserInputException("La conexión o biblioteca cambió; prepara una nueva limpieza");if(job.directRemoval() && job.source().equals("calibre")) checkDirectCalibrePending(item,id);else if(job.directRemoval()) checkDirectPending(item,id,job,!verify && item.dispatched());else noPending(item,id,job.allowUndownloaded(),job.action(),!verify && item.dispatched(),job.deleteHistory());};
                jdbc.update("UPDATE revision_cleanup_jobs SET message=NULL WHERE id=?",id);
                guard.run();
                if(!verify) requireCurrentAction(job.source(),job.action());
                if(jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',message=NULL WHERE id=? AND state=?",item.id(),item.state())!=1) return null;
                if(job.source().equals("downloads")) executeDownload(id,job,item,verify,guard);
                else executeCalibre(id,job,item,verify,guard);
                return null;
            })));
        } catch(RuntimeException ex) {
            if(ex instanceof CalibreTransfers.OperationBusy || ex instanceof com.rlibanez.eplsync.exception.TorrentBusyException || com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) throw ex;
            var fresh=jdbc.queryForList("SELECT dispatched FROM revision_cleanup_items WHERE id=?",Boolean.class,item.id());
            update(item.id(),verify || !fresh.isEmpty() && Boolean.TRUE.equals(fresh.getFirst())?"UNCERTAIN":ex instanceof UserInputException || ex instanceof org.springframework.security.access.AccessDeniedException?"BLOCKED":"FAILED",reason(ex));
        } finally {SecurityContextHolder.setContext(previous);}
    }
    private void dispatched(String id) {jdbc.update("UPDATE revision_cleanup_items SET dispatched=1 WHERE id=? AND state='RUNNING'",id);}
    private void executeDownload(String id,Job job,Item item,boolean verify,Runnable guard) {
        if(job.action().equals("RECORD")) {
            if(job.directRemoval()) {
                guard.run();checkJobDownload(job,item,List.of());
                boolean exists=jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=?",Long.class,item.oldId())>0;
                if(verify && exists) throw new UserInputException("El registro sigue presente; no se ha repetido el borrado");
                if(!verify && exists) history.deleteRevisionHistory(item.eplId(),item.oldId());
                update(item.id(),"SUCCEEDED","Registro eliminado; torrent y archivos conservados");return;
            }
            torrent.exclusiveClient(adapter->{guard.run();checkJobDownload(job,item,adapter.listTorrents());boolean exists=jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=?",Long.class,item.oldId())>0;
                if(verify && exists) throw new UserInputException("El registro sigue presente; no se ha repetido el borrado");
                if(!verify && exists) {dispatched(item.id());history.deleteRevisionHistory(item.eplId(),item.oldId());}return null;});
            update(item.id(),"SUCCEEDED","Registro eliminado; torrent y archivos conservados");return;
        }
        if(verify) {
            torrent.exclusiveClient(adapter->{guard.run();var remote=adapter.listTorrents();checkJobDownload(job,item,remote);
                if(TorrentRemovalSafety.index(remote).containsKey(item.oldHash())) throw new UserInputException("El torrent sigue presente; no se ha repetido el borrado");
                if(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=?",Long.class,item.oldId())>0) {history.refreshRevision(new HistoryActions.Selection(item.eplId(),List.of(item.oldId()),null,null));if(job.deleteHistory() && jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=?",Long.class,item.oldId())>0) history.deleteRevisionHistory(item.eplId(),item.oldId());}
                return null;});update(item.id(),"SUCCEEDED",job.deleteHistory()?"Ausencia del torrent confirmada; registro eliminado":"Ausencia del torrent confirmada; registro conservado");return;
        }
        if(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=?",Long.class,item.oldId())==0) {update(item.id(),"SKIPPED","El registro ya no existe; no se ha enviado ningún borrado");return;}
        var result=history.removeRevision(new HistoryActions.Selection(item.eplId(),List.of(item.oldId()),job.action().equals("FILES"),true),id,item.dispatched(),remote->{guard.run();checkJobDownload(job,item,remote);dispatched(item.id());});
        var outcome=result.items().getFirst();
        if("REMOVED".equals(outcome.cleanupState())) {
            if(job.deleteHistory() && jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=?",Long.class,item.oldId())>0) history.deleteRevisionHistory(item.eplId(),item.oldId());update(item.id(),"SUCCEEDED",(job.action().equals("FILES")?"Torrent y archivos eliminados":"Torrent eliminado; archivos conservados")+(job.deleteHistory()?"; registro eliminado":"; registro conservado"));
        } else {
            boolean uncertain="REQUESTED".equals(outcome.cleanupState());
            if(!uncertain && !job.directRemoval()) jdbc.update("UPDATE torrent_update_cleanup SET state='CANCELLED' WHERE job_id=? AND download_id=? AND state IN ('WAITING','BLOCKED')","revision:"+id,item.oldId());
            update(item.id(),uncertain?"UNCERTAIN":"BLOCKED",outcome.message());
        }
    }
    private void executeCalibre(String id,Job job,Item item,boolean verify,Runnable guard) {
        var c=settings.connection();long old=Long.parseLong(item.oldId());
        catalog.exclusive(()->{
            guard.run();if(job.directRemoval()) checkDirectCalibre(item,c,true);else checkCalibre(item,c,true);
            if(verify) {if(!removed(c,old,job.action())) throw new UserInputException("Calibre no confirma el borrado; no se ha repetido la orden");return null;}
            var current=bookOrNull(c,old);
            if(current==null) return null;
            if(job.directRemoval()) checkDirectCalibre(item,c,false);else {checkOldCalibre(item,current);checkFrozenCalibre(item,c,current,false);}
            requireCurrentAction(job.source(),job.action());
            // Never discard a changed observation; revalidate the retained EPUB immediately before dispatch.
            if(!job.directRemoval()) checkRetainedCalibre(item,c);
            guard.run();dispatched(item.id());
            try {client.remove(c,old,true);}catch(RuntimeException ex) {if(!removed(c,old,job.action())) throw ex;}
            if(!removed(c,old,job.action())) throw new UserInputException("Calibre no confirmó el borrado; comprueba su resultado sin reenviar la orden");
            return null;
        });
        if(job.action().equals("BOOK")) catalog.removeObserved(c,old);
        update(item.id(),"SUCCEEDED",job.action().equals("BOOK")?"Entrada de Calibre eliminada y ausencia verificada":"Borrado anterior del EPUB verificado; no se ha eliminado la entrada completa");
    }
    private boolean removed(CalibreSettings.Connection c,long id,String action) {return action.equals("BOOK")?bookOrNull(c,id)==null:bookOrNull(c,id)==null || !client.hasEpub(c,id);}
    private void update(String id,String state,String message) {jdbc.update("UPDATE revision_cleanup_items SET state=?,message=? WHERE id=?",state,message==null?null:message.substring(0,Math.min(1024,message.length())),id);}
    private void finish(String id) {
        var j=find(id);
        boolean cancelled=Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancel_requested FROM revision_cleanup_jobs WHERE id=?",Boolean.class,id));
        if(j.pending()>0 && !cancelled) {jdbc.update("UPDATE revision_cleanup_jobs SET updated_at=? WHERE id=?",Instant.now().toEpochMilli(),id);return;}
        long errors=j.blocked()+j.failed()+j.uncertain();String outcome=cancelled?"CANCELLED":errors==0?"SUCCEEDED":j.succeeded()>0?"PARTIAL":"FAILED";
        jdbc.update("UPDATE revision_cleanup_jobs SET state=?,result=?,updated_at=? WHERE id=?",cancelled?"CANCELLED":"COMPLETED",outcome,Instant.now().toEpochMilli(),id);
        var actor=new EventContext.Actor(jdbc.queryForObject("SELECT owner_id FROM revision_cleanup_jobs WHERE id=?",String.class,id),j.username(),"USER");
        var details=Map.<String,Object>of("source",j.source(),"selected",j.selected(),"removed",j.succeeded(),"blocked",j.blocked(),"failed",j.failed(),"unconfirmed",j.uncertain(),"jobId",id);
        var terminal=EventJournal.Outcome.valueOf(outcome);
        if(errors>0) {
            String signature=json.writeValueAsString(jdbc.queryForList("SELECT id,state,message FROM revision_cleanup_items WHERE job_id=? ORDER BY position",id));
            tx.executeWithoutResult(status -> {
                if(jdbc.update("UPDATE revision_cleanup_jobs SET reported_signature=? WHERE id=? AND (reported_signature IS NULL OR reported_signature<>?)",signature,id,signature)>0)
                    events.recordAs(actor,EventJournal.Category.JOB,"REVISION_CLEANUP",terminal,EventContext.Origin.MANUAL,id,details);
            });
        }
        else events.live(actor,EventJournal.Category.JOB,"REVISION_CLEANUP",terminal,EventContext.Origin.MANUAL,id,details);
        log.info("Limpieza de revisiones finalizada: trabajo={}, origen={}, acción={}, resultado={}, eliminadas={}, bloqueadas={}, fallidas={}, sinConfirmar={}",id,j.source(),j.action(),outcome,j.succeeded(),j.blocked(),j.failed(),j.uncertain());
    }
    private static String reason(RuntimeException ex) {return ex instanceof UserInputException || ex instanceof org.springframework.security.access.AccessDeniedException || ex instanceof com.rlibanez.eplsync.exception.TorrentOperationException?Objects.toString(ex.getMessage(),"No se pudo verificar la operación"):"No se pudo comprobar el cliente o completar la operación; revisa la conexión";}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000) public void retention() {
        if(!lifecycle.tryLock()) return;
        try {jdbc.update("DELETE FROM revision_cleanup_jobs WHERE id IN (SELECT id FROM revision_cleanup_jobs WHERE state='PREVIEW' AND expires_at<? AND NOT EXISTS(SELECT 1 FROM calibre_cleanup_preparations p WHERE p.job_id=revision_cleanup_jobs.id AND p.state IN ('QUEUED','RUNNING')) OR state IN ('COMPLETED','CANCELLED') AND updated_at<? AND NOT EXISTS(SELECT 1 FROM revision_cleanup_items i WHERE i.job_id=revision_cleanup_jobs.id AND i.state='UNCERTAIN') ORDER BY created_at LIMIT 100)",Instant.now().toEpochMilli(),Instant.now().minus(Duration.ofDays(90)).toEpochMilli());}finally {lifecycle.unlock();}
    }
    @jakarta.annotation.PreDestroy void close() {enabled=false;worker.shutdown();try {if(!worker.awaitTermination(65,TimeUnit.SECONDS)) worker.shutdownNow();}catch(InterruptedException ex) {worker.shutdownNow();Thread.currentThread().interrupt();}}
}
