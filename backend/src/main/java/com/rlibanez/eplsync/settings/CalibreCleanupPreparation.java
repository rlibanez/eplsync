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
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import tools.jackson.databind.json.JsonMapper;

/** Durable, read-only remote preparation. A batch never sends deletes or uploads. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class CalibreCleanupPreparation {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreCleanupPreparation.class);
    public record Status(RevisionCleanup.Job job,String state,long processed,long total,long version,long reviewRequired,String message) {}
    public record Selection(long version,boolean all,List<String> ids,boolean selected) {}
    public record Retained(long version,String groupKey,String copyId) {}
    public record Confirmation(long version,boolean confirm,boolean confirmDestructive) {}
    public record Group(String key,long eplId,String revision,String retainedId,String title,List<String> formats,boolean requiresChoice) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RevisionCleanup cleanup;
    private final CalibreLibraryCleanup planner;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final AccountStore accounts;
    private final SessionAccess sessions;
    private final MaintenanceGate maintenance;
    private final JsonMapper json=JsonMapper.builder().build();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("calibre-cleanup-preview").factory());
    private final AtomicBoolean running=new AtomicBoolean();
    private final com.rlibanez.eplsync.config.WorkerActivity.Probe idle=new com.rlibanez.eplsync.config.WorkerActivity.Probe();
    @org.springframework.beans.factory.annotation.Value("${eplsync.revision-cleanup.worker-enabled:true}") private boolean enabled=true;
    private volatile boolean closed;
    public CalibreCleanupPreparation(JdbcTemplate jdbc,PlatformTransactionManager manager,RevisionCleanup cleanup,CalibreLibraryCleanup planner,CalibreSettings settings,CalibreClient client,AccountStore accounts,SessionAccess sessions,MaintenanceGate maintenance) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.cleanup=cleanup;this.planner=planner;this.settings=settings;this.client=client;this.accounts=accounts;this.sessions=sessions;this.maintenance=maintenance;
    }
    @jakarta.annotation.PostConstruct void recover() {
        tx.executeWithoutResult(s->{jdbc.update("UPDATE calibre_cleanup_preparations SET state='QUEUED' WHERE state='RUNNING'");
            jdbc.update("UPDATE revision_cleanup_jobs SET expires_at=? WHERE id IN (SELECT job_id FROM calibre_cleanup_preparations WHERE state='QUEUED') AND state='PREVIEW'",Instant.now().plus(Duration.ofMinutes(30)).toEpochMilli());});
    }
    public Status begin(CalibreLibraryCleanup.Input input) {
        if(input==null || !input.oldRevisions() && !input.duplicates()) throw new UserInputException("Selecciona al menos un tipo de limpieza");
        var ids=input.ids()==null?List.<String>of():input.ids();
        if(ids.stream().anyMatch(id->id==null || !id.matches("[1-9][0-9]{0,18}")) || new HashSet<>(ids).size()!=ids.size()) throw new UserInputException("Selecciona entradas de Calibre válidas sin duplicados");
        String id=tx.execute(s->{
            if(input.previewId()!=null) {cleanup.requireLibraryDraft(input.previewId());jdbc.update("UPDATE calibre_cleanup_preparations SET state='CANCELLED' WHERE job_id=?",input.previewId());}
            if(jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_preparations p JOIN revision_cleanup_jobs j ON j.id=p.job_id WHERE j.owner_id=? AND p.state IN ('QUEUED','RUNNING','READY') AND j.state='PREVIEW'",Long.class,sessions.current().id())>=5) throw new UserInputException("Hay cinco previsualizaciones pendientes; cierra alguna antes de preparar otra");
            var job=cleanup.createLibraryDraft();
            jdbc.update("INSERT INTO calibre_cleanup_preparations(job_id,input,state) VALUES(?,?,'QUEUED')",job.id(),json.writeValueAsString(new CalibreLibraryCleanup.Input(input.oldRevisions(),input.duplicates(),ids,Map.of(),null)));
            return job.id();
        });return status(id);
    }
    public Status status(String id) {
        var job=cleanup.get(id);var row=row(id);
        return new Status(job,(String)row.get("state"),number(row,"processed"),number(row,"total"),number(row,"version"),jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_groups WHERE job_id=? AND requires_choice=1",Long.class,id),(String)row.get("message"));
    }
    private Map<String,Object> row(String id) {return jdbc.queryForList("SELECT * FROM calibre_cleanup_preparations WHERE job_id=?",id).stream().findFirst().orElseThrow(()->new UserInputException("La preparación ya no existe"));}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private void editable(String id,long version) {
        cleanup.requireLibraryDraft(id);var prep=row(id);
        if(number(prep,"version")!=version || !"READY".equals(prep.get("state"))) throw new UserInputException("La previsualización cambió o sigue preparándose; actualiza la vista");
    }
    public Status cancel(String id) {
        var job=cleanup.get(id);
        if(job.state().equals("PREVIEW")) jdbc.update("UPDATE calibre_cleanup_preparations SET state='CANCELLED',message='Preparación cancelada; no se ha enviado ningún borrado' WHERE job_id=? AND state<>'CANCELLED'",id);
        return status(id);
    }
    public Status select(String id,Selection input) {
        if(input==null || !input.all() && (input.ids()==null || input.ids().isEmpty() || input.ids().size()>1000)) throw new UserInputException("Indica los elementos de la página que quieres seleccionar");
        tx.executeWithoutResult(s->{editable(id,input.version());
            if(input.all()) jdbc.update("UPDATE revision_cleanup_items SET preview_selected=? WHERE job_id=? AND state='READY'",input.selected(),id);
            else {
                String ids=json.writeValueAsString(input.ids());
                if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items WHERE job_id=? AND state='READY' AND id IN (SELECT value FROM json_each(?))",Long.class,id,ids)!=new HashSet<>(input.ids()).size()) throw new UserInputException("La selección contiene entradas que ya no están disponibles");
                jdbc.update("UPDATE revision_cleanup_items SET preview_selected=? WHERE job_id=? AND id IN (SELECT value FROM json_each(?))",input.selected(),id,ids);
            }
        });return status(id);
    }
    public Status retain(String id,Retained input) {
        if(input==null) throw new UserInputException("Elige la copia que se conservará");
        tx.executeWithoutResult(s->{editable(id,input.version());
            var choices=jdbc.queryForList("SELECT g.epl_id FROM calibre_cleanup_groups g JOIN calibre_cleanup_copies c ON c.job_id=g.job_id AND c.group_key=g.group_key WHERE g.job_id=? AND g.group_key=? AND c.calibre_id=?",id,input.groupKey(),input.copyId());
            if(choices.isEmpty()) throw new UserInputException("La copia no pertenece a ese grupo");
            var prep=row(id);var old=json.readValue((String)prep.get("input"),CalibreLibraryCleanup.Input.class);
            var retained=new HashMap<String,String>(old.retained()==null?Map.of():old.retained());retained.put(input.groupKey(),input.copyId());
            jdbc.update("UPDATE calibre_cleanup_preparations SET input=?,state='QUEUED',version=version+1,rebuild_epl=?,message=NULL WHERE job_id=?",json.writeValueAsString(new CalibreLibraryCleanup.Input(old.oldRevisions(),old.duplicates(),old.ids(),retained,null)),choices.getFirst().get("epl_id"),id);
        });return status(id);
    }
    public RevisionCleanup.Job confirm(String id,Confirmation input) {
        if(input==null) throw new UserInputException("Confirma la eliminación");
        return cleanup.confirmLibrary(id,input.version(),input.confirm(),input.confirmDestructive());
    }
    public PageResponse<RevisionCleanup.Item> items(String id,int page,int size,String sort,long version) {
        editable(id,version);var params=new org.springframework.util.LinkedMultiValueMap<String,String>();params.add("sort",sort);
        return cleanup.items(id,page,size,params);
    }
    public PageResponse<Group> groups(String id,int page,int size,long version) {
        editable(id,version);com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        long total=jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_groups WHERE job_id=?",Long.class,id);
        var items=jdbc.query("SELECT g.*,c.title,c.formats FROM calibre_cleanup_groups g JOIN calibre_cleanup_copies c ON c.job_id=g.job_id AND c.group_key=g.group_key AND c.calibre_id=g.retained_id WHERE g.job_id=? ORDER BY g.epl_id,CAST(g.revision AS NUMERIC) DESC LIMIT ? OFFSET ?",(r,n)->new Group(r.getString("group_key"),r.getLong("epl_id"),r.getString("revision"),r.getString("retained_id"),r.getString("title"),formats(r.getString("formats")),r.getBoolean("requires_choice")),id,size,(long)page*size);
        return new PageResponse<>(items,meta(page,size,total));
    }
    public PageResponse<CalibreLibraryCleanup.Copy> copies(String id,String group,int page,int size,String search,long version) {
        editable(id,version);com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if(search==null || search.length()>4096) throw new UserInputException("Filtro de copia inválido");
        String where=" WHERE job_id=? AND group_key=? AND (instr(lower(title),lower(?))>0 OR instr(calibre_id,?)>0)";
        long total=jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_copies"+where,Long.class,id,group,search,search);
        var copies=jdbc.query("SELECT * FROM calibre_cleanup_copies"+where+" ORDER BY CAST(calibre_id AS INTEGER) LIMIT ? OFFSET ?",(r,n)->new CalibreLibraryCleanup.Copy(r.getString("calibre_id"),r.getString("title"),formats(r.getString("formats"))),id,group,search,search,size,(long)page*size);
        return new PageResponse<>(copies,meta(page,size,total));
    }
    private List<String> formats(String value) {return json.readValue(value,new tools.jackson.core.type.TypeReference<List<String>>(){});}
    private static PageResponse.PageMeta meta(int page,int size,long total) {int pages=(int)((total+size-1)/size);return new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page+1<pages,page>0);}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000) public void tick() {
        if(enabled && !closed && idle.ready() && running.compareAndSet(false,true)) executor.execute(()->{try {cycle();}finally {running.set(false);}});
    }
    public void cycle() {
        if(closed || !maintenance.enter(false)) return;
        long probe=idle.snapshot();var previous=SecurityContextHolder.getContext();String id=null;
        try {
            var queued=jdbc.queryForList("SELECT p.job_id,j.owner_id FROM calibre_cleanup_preparations p JOIN revision_cleanup_jobs j ON j.id=p.job_id WHERE p.state IN ('QUEUED','RUNNING') AND j.state='PREVIEW' ORDER BY j.updated_at,j.id LIMIT 1");
            if(queued.isEmpty()) {idle.empty(probe);return;}
            id=(String)queued.getFirst().get("job_id");String selectedId=id;
            var owner=accounts.find((String)queued.getFirst().get("owner_id"));
            if(owner==null) throw new UserInputException("El propietario ya no existe");
            var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,owner.permissions().stream().map(p->new SimpleGrantedAuthority(p.name())).toList()));SecurityContextHolder.setContext(security);
            var prep=row(id);long version=number(prep,"version");Runnable check=()->checkpoint(selectedId,version);
            check.run();cleanup.requireLibraryDraft(id);jdbc.update("UPDATE calibre_cleanup_preparations SET state='RUNNING',message=NULL WHERE job_id=? AND version=? AND state IN ('QUEUED','RUNNING')",id,version);
            var input=json.readValue((String)prep.get("input"),CalibreLibraryCleanup.Input.class);
            if(!Boolean.TRUE.equals(prep.get("initialized")) && !(prep.get("initialized") instanceof Number initialized && initialized.intValue()!=0)) initialize(id,input,version);
            prep=row(id);
            var uncheckedCopies=jdbc.queryForList("SELECT group_key,calibre_id FROM calibre_cleanup_copies WHERE job_id=? AND formats_checked=0 ORDER BY group_key,CAST(calibre_id AS INTEGER) LIMIT 20",id);
            long copyDeadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            for(var copy:uncheckedCopies) {
                if(System.nanoTime()>copyDeadline) break;
                check.run();var formats=List.copyOf(new TreeSet<>(client.formats(settings.connection(),Long.parseLong((String)copy.get("calibre_id")))));
                tx.executeWithoutResult(s->{check.run();jdbc.update("UPDATE calibre_cleanup_copies SET formats=?,formats_checked=1 WHERE job_id=? AND group_key=? AND calibre_id=?",json.writeValueAsString(formats),selectedId,copy.get("group_key"),copy.get("calibre_id"));});
            }
            if(!uncheckedCopies.isEmpty()) return;
            tx.executeWithoutResult(s->{check.run();jdbc.update("UPDATE calibre_cleanup_groups SET requires_choice=CASE WHEN ?=1 AND group_key NOT IN (SELECT key FROM json_each(?)) AND (SELECT count(DISTINCT formats) FROM calibre_cleanup_copies c WHERE c.job_id=calibre_cleanup_groups.job_id AND c.group_key=calibre_cleanup_groups.group_key)>1 THEN 1 ELSE 0 END,formats_checked=1 WHERE job_id=? AND formats_checked=0",input.duplicates(),json.writeValueAsString(input.retained()==null?Map.of():input.retained()),selectedId);});
            var unchecked=jdbc.queryForList("SELECT id FROM revision_cleanup_items WHERE job_id=? AND preview_checked=0 ORDER BY position LIMIT 20",String.class,id);
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            for(String item:unchecked) {
                if(System.nanoTime()>deadline) break;
                check.run();var result=cleanup.checkLibraryCandidate(id,item,check);
                tx.executeWithoutResult(s->{check.run();jdbc.update("UPDATE revision_cleanup_items SET formats=?,state=?,message=?,calibre_identity=?,retained_identity=?,retained_formats=?,preview_checked=1,preview_selected=? WHERE id=? AND job_id=? AND preview_checked=0",json.writeValueAsString(result.formats()),result.state(),result.message(),result.oldIdentity(),result.retainedIdentity(),result.retainedFormats(),result.state().equals("READY"),item,selectedId);});
            }
            if(!unchecked.isEmpty()) return;
            for(int books=0;books<20 && System.nanoTime()<deadline;books++) {
                prep=row(id);
                Long next=prep.get("rebuild_epl")==null?jdbc.queryForObject("SELECT min(epl_id) FROM calibre_cleanup_sources WHERE job_id=? AND epl_id>?",Long.class,id,number(prep,"last_epl")):((Number)prep.get("rebuild_epl")).longValue();
                if(next==null) {tx.executeWithoutResult(s->{check.run();jdbc.update("UPDATE calibre_cleanup_preparations SET state='READY',message=NULL WHERE job_id=? AND version=?",selectedId,version);});return;}
                var plan=planner.snapshot(id,next,input,settings.connection(),check);
                stage(id,next,version,plan,prep.get("rebuild_epl")!=null,check);
                if(!plan.candidates().isEmpty() || !plan.groups().isEmpty()) return;
            }
        } catch(RuntimeException ex) {
            if(id!=null) {
                boolean retry=ex instanceof CalibreTransfers.OperationBusy || ex instanceof CalibreClient.TransientFailure || com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex);
                if(!(ex instanceof CancellationException)) log.atLevel(retry?org.slf4j.event.Level.DEBUG:org.slf4j.event.Level.WARN).log("Preparación de limpieza de Calibre: trabajo={}, reintento={}, tipo={}",id,retry,ex.getClass().getSimpleName());
                if(!(ex instanceof CancellationException)) jdbc.update("UPDATE calibre_cleanup_preparations SET state=?,message=? WHERE job_id=? AND state IN ('QUEUED','RUNNING')",retry?"QUEUED":"FAILED",retry?"Comprobación aplazada; se reintentará automáticamente":ex instanceof UserInputException || ex instanceof org.springframework.security.access.AccessDeniedException?ex.getMessage():"No se pudo preparar la limpieza; revisa la conexión y vuelve a previsualizar",id);
            }
        } finally {
            try {if(id!=null) jdbc.update("UPDATE revision_cleanup_jobs SET updated_at=?,expires_at=? WHERE id=? AND state='PREVIEW'",Instant.now().toEpochMilli(),Instant.now().plus(Duration.ofMinutes(30)).toEpochMilli(),id);}
            finally {SecurityContextHolder.setContext(previous);maintenance.leave(false);}
        }
    }
    private void checkpoint(String id,long version) {
        if(closed || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if(jdbc.queryForObject("SELECT count(*) FROM calibre_cleanup_preparations p JOIN revision_cleanup_jobs j ON j.id=p.job_id WHERE p.job_id=? AND p.version=? AND p.state IN ('QUEUED','RUNNING') AND j.state='PREVIEW'",Long.class,id,version)!=1) throw new CancellationException();
    }
    private void initialize(String id,CalibreLibraryCleanup.Input input,long version) {
        tx.executeWithoutResult(s->{checkpoint(id,version);
            String ids=json.writeValueAsString(input.ids()==null?List.of():input.ids());boolean all=input.ids()==null || input.ids().isEmpty();
            jdbc.update("INSERT INTO calibre_cleanup_sources(job_id,calibre_id,epl_id,revision,title,chosen) SELECT ?,calibre_id,epl_id,cast(revision AS TEXT),title,"+(all?"1":"CASE WHEN cast(calibre_id AS TEXT) IN (SELECT value FROM json_each(?)) THEN 1 ELSE 0 END")+" FROM calibre_books WHERE active=1 AND epl_id>0 AND revision>0"+(all?"":" AND epl_id IN (SELECT epl_id FROM calibre_books WHERE active=1 AND cast(calibre_id AS TEXT) IN (SELECT value FROM json_each(?)))"),all?new Object[]{id}:new Object[]{id,ids,ids});
            jdbc.update("UPDATE calibre_cleanup_preparations SET initialized=1,total=(SELECT count(DISTINCT epl_id) FROM calibre_cleanup_sources WHERE job_id=?) WHERE job_id=?",id,id);
        });
    }
    private void stage(String id,long epl,long version,CalibreLibraryCleanup.Plan plan,boolean rebuild,Runnable check) {
        tx.executeWithoutResult(s->{check.run();jdbc.update("DELETE FROM revision_cleanup_items WHERE job_id=? AND epl_id=?",id,epl);jdbc.update("DELETE FROM calibre_cleanup_groups WHERE job_id=? AND epl_id=?",id,epl);});
        for(var group:plan.groups()) {
            tx.executeWithoutResult(s->{check.run();jdbc.update("INSERT INTO calibre_cleanup_groups(job_id,group_key,epl_id,revision,retained_id,requires_choice) VALUES(?,?,?,?,?,?)",id,group.key(),group.eplId(),group.revision(),group.retainedId(),group.requiresChoice());});
            var batch=new ArrayList<Object[]>();for(var copy:group.copies()) batch.add(new Object[]{id,group.key(),copy.id(),copy.title(),json.writeValueAsString(copy.formats())});
            for(int offset=0;offset<batch.size();offset+=500) {var values=batch.subList(offset,Math.min(offset+500,batch.size()));tx.executeWithoutResult(s->{check.run();jdbc.batchUpdate("INSERT INTO calibre_cleanup_copies(job_id,group_key,calibre_id,title,formats) VALUES(?,?,?,?,?)",values);});}
        }
        long position=jdbc.queryForObject("SELECT coalesce(max(position),-1)+1 FROM revision_cleanup_items WHERE job_id=?",Long.class,id);
        var items=new ArrayList<Object[]>();for(var item:plan.candidates()) items.add(new Object[]{UUID.randomUUID().toString(),id,position++,item.oldId(),item.retainedId(),item.eplId(),item.title(),item.revision(),item.retainedRevision(),item.reason()});
        for(int offset=0;offset<items.size();offset+=500) {var batch=items.subList(offset,Math.min(offset+500,items.size()));tx.executeWithoutResult(s->{check.run();jdbc.batchUpdate("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,cleanup_reason,state,message,preview_checked) VALUES(?,?,?,?,?,?,?,?,?,?,'EXCLUDED','Preparando comprobaciones',0)",batch);});}
        tx.executeWithoutResult(s->{check.run();if(rebuild) jdbc.update("UPDATE calibre_cleanup_preparations SET rebuild_epl=NULL WHERE job_id=? AND version=?",id,version);else jdbc.update("UPDATE calibre_cleanup_preparations SET last_epl=?,processed=processed+1 WHERE job_id=? AND version=?",epl,id,version);});
    }
    @jakarta.annotation.PreDestroy void close() {closed=true;executor.shutdownNow();}
}
