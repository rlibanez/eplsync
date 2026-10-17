package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local history only. Checks and removal share the writer transaction and executor locks. */
@Service
public class WorkDeletion {
    public record Ref(String id,boolean cleanup) {}
    public record Selection(List<Ref> jobs) {}
    public record Item(String id,boolean cleanup,boolean allowed,boolean deleted,String reason,String relatedSource,String relatedId,boolean relatedCleanup) {}
    public record Result(List<Item> items) {}
    private final JdbcTemplate jdbc;
    private final CalibreTransfers calibre;
    private final RevisionCleanup cleanup;
    private final SessionAccess sessions;
    private final TransactionTemplate tx;
    public WorkDeletion(JdbcTemplate jdbc,CalibreTransfers calibre,RevisionCleanup cleanup,SessionAccess sessions,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.calibre=calibre;this.cleanup=cleanup;this.sessions=sessions;tx=new TransactionTemplate(manager);tx.setTimeout(30);
    }
    public Result apply(String source,Selection selection,boolean preview) {
        if(source==null || !Set.of("downloads","calibre").contains(source)) throw new UserInputException("Origen de trabajos inválido");
        if(selection==null || selection.jobs()==null || selection.jobs().isEmpty() || selection.jobs().size()>1000
            || selection.jobs().stream().anyMatch(r->r==null || r.id()==null || r.id().isBlank() || r.id().length()>64)
            || new HashSet<>(selection.jobs()).size()!=selection.jobs().size()) throw new UserInputException("Selecciona entre 1 y 1000 trabajos distintos");
        // Match cleanup execution: cleanup -> Calibre -> SQLite writer. Bulk state is
        // rechecked in that transaction; never wait for a writer under the bulk monitor.
        return cleanup.exclusive(()->calibre.exclusive(()->tx.execute(status->process(source,selection.jobs(),preview))));
    }
    private Result process(String source,List<Ref> refs,boolean preview) {
        var removed=new HashSet<Ref>();var results=new LinkedHashMap<Ref,Item>();
        // Delete leaves first, including legacy retry chains selected in arbitrary order.
        boolean progress;
        do {
            progress=false;
            for(var ref:refs) if(!removed.contains(ref)) {
                var item=check(source,ref,removed);results.put(ref,item);
                if(item.allowed()) {
                    if(!preview) erase(source,ref);
                    removed.add(ref);results.put(ref,new Item(ref.id(),ref.cleanup(),true,!preview,null,null,null,false));progress=true;
                }
            }
        } while(progress && removed.size()<refs.size());
        return new Result(refs.stream().map(results::get).toList());
    }
    private Item blocked(Ref ref,String reason) {return new Item(ref.id(),ref.cleanup(),false,false,reason,null,null,false);}
    private Item related(Ref ref,String reason,String source,String id) {
        boolean visible=source.equals("downloads")?Permission.has(Permission.TORRENT_JOBS_MANAGE):Permission.has(Permission.CALIBRE_SEND)||Permission.has(Permission.CALIBRE_JOBS_MANAGE);
        return new Item(ref.id(),ref.cleanup(),false,false,reason,visible?source:null,visible?id:null,false);
    }
    private Item check(String source,Ref ref,Set<Ref> removed) {
        String id=ref.id();boolean downloads=source.equals("downloads");
        if(ref.cleanup()) {
            if(!Permission.has(downloads?Permission.TORRENT_SYNC:Permission.CALIBRE_READ)
                || !Permission.has(downloads?Permission.DOWNLOADS_DELETE:Permission.CALIBRE_DELETE)) return blocked(ref,"No tienes permiso para eliminar este trabajo de limpieza");
            var rows=jdbc.queryForList("SELECT state,owner_id,source FROM revision_cleanup_jobs WHERE id=?",id);
            if(rows.isEmpty()) return blocked(ref,"El trabajo ya no existe");
            var row=rows.getFirst();
            if(!source.equals(row.get("source")) || !Objects.equals(row.get("owner_id"),sessions.current().id())) return blocked(ref,"El trabajo de limpieza no pertenece a tu cuenta o a esta vista");
            if(!Set.of("COMPLETED","CANCELLED").contains(row.get("state"))) return blocked(ref,"Solo se pueden eliminar trabajos terminados o cancelados; cancela primero los pausados");
            if(exists("SELECT 1 FROM revision_cleanup_items WHERE job_id=? AND (state IN ('PENDING','RUNNING','VERIFYING','UNCERTAIN') OR dispatched=1 AND state NOT IN ('SUCCEEDED','SKIPPED'))",id)) return blocked(ref,"Hay borrados pendientes o enviados cuyo resultado debe confirmarse");
            if(pendingCleanup("revision:"+id)) return blocked(ref,"Hay solicitudes de borrado pendientes de resolver");
        } else {
            if(!Permission.has(downloads?Permission.TORRENT_JOBS_MANAGE:Permission.CALIBRE_JOBS_MANAGE)) return blocked(ref,"No tienes permiso de gestión para eliminar este trabajo");
            String table=downloads?"torrent_bulk_jobs":"calibre_jobs";
            var states=jdbc.queryForList("SELECT state FROM "+table+" WHERE id=?",String.class,id);
            if(states.isEmpty()) return blocked(ref,"El trabajo ya no existe");
            if(!Set.of("COMPLETED","CANCELLED").contains(states.getFirst())) return blocked(ref,"Solo se pueden eliminar trabajos terminados o cancelados; cancela primero los pausados");
            if(exists("SELECT 1 FROM "+(downloads?"torrent_bulk_items":"calibre_job_items")+" WHERE job_id=? AND state IN ("+(downloads?"'PENDING','IN_FLIGHT'":"'PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','SENDING','UNCERTAIN','CLEANUP_PENDING'")+")",id)) return blocked(ref,"Hay envíos, descargas o resultados sin confirmar pendientes de resolver");
            if(downloads && pendingCleanup(id)) return blocked(ref,"Hay limpiezas de revisiones anteriores pendientes de resolver");
            String ancestry="WITH RECURSIVE ancestors(id) AS (SELECT ? UNION SELECT j.parent_id FROM job_retry_jobs j JOIN ancestors a ON j.child_id=a.id WHERE j.kind=? UNION SELECT j.root_id FROM job_retry_jobs j JOIN ancestors a ON j.child_id=a.id WHERE j.kind=?) ";
            if(downloads) {
                var dependents=jdbc.queryForList(ancestry+"SELECT DISTINCT i.job_id FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE i.download_job_id IN (SELECT id FROM ancestors) ORDER BY i.job_id",String.class,id,source,source);
                // Cross-view dependents must be removed explicitly from Calibre first, even when completed.
                if(!dependents.isEmpty()) return related(ref,"El trabajo de Calibre enlazado conserva referencias a estas descargas; elimínalo primero", "calibre",dependents.getFirst());
            }
            var children=jdbc.queryForList("SELECT child_id FROM job_retry_jobs WHERE kind=? AND (parent_id=? OR root_id=?) AND child_id<>? AND EXISTS(SELECT 1 FROM "+table+" j WHERE j.id=child_id)",String.class,source,id,id,id);
            for(var child:children) if(!removed.contains(new Ref(child,false))) return related(ref,"Otro trabajo de reintento conserva este trabajo como origen; elimina primero el dependiente",source,child);
            // An active cycle can reach a retry through its original job, not only a direct link.
            String cycle=ancestry+"SELECT 1 FROM scheduled_automation_runs r WHERE r.state<>'COMPLETED' AND (r."+(downloads?"download_job_id":"calibre_job_id")+" IN (SELECT id FROM ancestors) OR EXISTS(SELECT 1 FROM scheduled_automation_jobs a WHERE a.run_id=r.id AND a.kind=? AND a.job_id IN (SELECT id FROM ancestors)))";
            if(exists(cycle,id,source,source,source)) return blocked(ref,"Un ciclo de actualización automática todavía depende de este trabajo");
        }
        return new Item(id,ref.cleanup(),true,false,null,null,null,false);
    }
    private boolean exists(String sql,Object... args) {return !jdbc.queryForList(sql+" LIMIT 1",args).isEmpty();}
    private boolean pendingCleanup(String id) {return exists("SELECT 1 FROM torrent_update_cleanup WHERE job_id=? AND state IN ('WAITING','BLOCKED','REQUESTED')",id);}
    private void erase(String source,Ref ref) {
        String id=ref.id();
        if(ref.cleanup()) {
            jdbc.update("INSERT INTO deleted_work_receipts(source,kind,request_id,deleted_at) VALUES(?,'cleanup',?,?) ON CONFLICT DO NOTHING",source,id,java.time.Instant.now().toEpochMilli());
            jdbc.update("DELETE FROM torrent_update_cleanup WHERE job_id=?","revision:"+id);
            jdbc.update("DELETE FROM revision_cleanup_jobs WHERE id=?",id); // items and explicit retry tokens cascade
        } else {
            long now=java.time.Instant.now().toEpochMilli();
            jdbc.update("INSERT INTO deleted_work_receipts(source,kind,request_id,deleted_at) SELECT kind,'retry',request_id,? FROM job_retry_jobs WHERE kind=? AND child_id=? ON CONFLICT DO NOTHING",now,source,id);
            jdbc.update("INSERT INTO deleted_work_receipts(source,kind,request_id,deleted_at) SELECT kind,'retry',id,? FROM job_retry_attempts WHERE kind=? AND job_id=? ON CONFLICT DO NOTHING",now,source,id);
            jdbc.update("DELETE FROM job_retry_previews WHERE kind=? AND parent_id=?",source,id);
            jdbc.update("DELETE FROM job_retry_attempts WHERE kind=? AND job_id=?",source,id);
            jdbc.update("DELETE FROM job_retry_jobs WHERE kind=? AND child_id=?",source,id); // provenance items cascade
            jdbc.update("DELETE FROM scheduled_automation_jobs WHERE kind=? AND job_id=?",source,id);
            jdbc.update("UPDATE scheduled_automation_runs SET "+(source.equals("downloads")?"download_job_id":"calibre_job_id")+"=NULL WHERE state='COMPLETED' AND "+(source.equals("downloads")?"download_job_id":"calibre_job_id")+"=?",id);
            if(source.equals("downloads")) {
                jdbc.update("DELETE FROM torrent_update_cleanup WHERE job_id=?",id);
                jdbc.update("DELETE FROM torrent_update_plans WHERE job_id=?",id);
                jdbc.update("DELETE FROM torrent_bulk_items WHERE job_id=?",id);
                jdbc.update("DELETE FROM torrent_bulk_jobs WHERE id=?",id);
            } else jdbc.update("DELETE FROM calibre_jobs WHERE id=?",id);
        }
        // Preserve idempotency receipts (calibre_update_requests): deleting history must not resend anything.
    }
}
