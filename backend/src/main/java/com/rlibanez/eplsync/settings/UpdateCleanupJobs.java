package com.rlibanez.eplsync.settings;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.rlibanez.eplsync.config.WorkerActivity;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;

/** Attach the existing durable requests; never create a second deletion request. */
@Service
public class UpdateCleanupJobs {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(UpdateCleanupJobs.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ServerSettings settings;
    private final DownloadTrackingService tracking;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("update-cleanup-links").factory());
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private final WorkerActivity.Probe probe=new WorkerActivity.Probe();
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.bulk.worker-enabled:true}") private boolean enabled;
    public UpdateCleanupJobs(JdbcTemplate jdbc,PlatformTransactionManager manager,ServerSettings settings,DownloadTrackingService tracking) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.settings=settings;this.tracking=tracking;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recover() {schedule();}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void schedule() {
        if(!enabled || closed || !probe.ready() || !running.compareAndSet(false,true)) return;
        try {worker.execute(()->{
            try {attachPending();}
            catch(RuntimeException ex) {
                if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) log.debug("Preparación de limpiezas relacionadas aplazada por contención SQLite");
                else log.warn("No se pudieron preparar las limpiezas relacionadas: causa={}",com.rlibanez.eplsync.exception.OperationDiagnostics.technical(ex));
                probe.empty(probe.snapshot());
            } finally {running.set(false);}
        });} catch(java.util.concurrent.RejectedExecutionException ex) {running.set(false);if(!closed) throw ex;}
    }
    @jakarta.annotation.PreDestroy public void close() {closed=true;worker.shutdownNow();}
    public void attachPending() {
        long revision=probe.snapshot();
        var parents=jdbc.queryForList("""
            SELECT j.id FROM torrent_bulk_jobs j JOIN torrent_update_plans p ON p.job_id=j.id
            WHERE p.client_instance_id=? AND j.event_actor_id IN (SELECT id FROM users)
              AND EXISTS(SELECT 1 FROM torrent_update_cleanup c WHERE c.job_id=j.id AND c.state<>'KEPT')
              AND NOT EXISTS(SELECT 1 FROM torrent_update_cleanup c WHERE c.job_id=j.id AND c.client_instance_id IS NULL)
              AND NOT EXISTS(SELECT 1 FROM revision_cleanup_jobs c WHERE c.update_job_id=j.id)
              AND NOT EXISTS(SELECT 1 FROM deleted_work_receipts r WHERE r.source='downloads' AND r.kind='cleanup' AND r.request_id='update:'||j.id)
            ORDER BY j.created_at,j.id LIMIT 10
            """,String.class,tracking.instanceId());
        for(String parent:parents) {attach(parent);synchronized(bulk) {bulk.cleanupChanged(parent);}}
        if(parents.isEmpty()) probe.empty(revision);
    }
    public String attach(String parent) {
        return tx.execute(status->{
            var existing=jdbc.queryForList("SELECT id FROM revision_cleanup_jobs WHERE update_job_id=?",String.class,parent);
            if(!existing.isEmpty()) return existing.getFirst();
            String id=UUID.nameUUIDFromBytes(("update-cleanup:"+parent).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if(jdbc.queryForObject("SELECT count(*) FROM deleted_work_receipts WHERE source='downloads' AND kind='cleanup' AND request_id=?",Long.class,id)>0) return null;
            var rows=jdbc.queryForList("SELECT j.*,p.previous_versions policy,p.delete_history,p.cleanup_timing,p.client_instance_id FROM torrent_bulk_jobs j JOIN torrent_update_plans p ON p.job_id=j.id WHERE j.id=?",parent);
            if(rows.isEmpty()) return null;var row=rows.getFirst();
            if(!Objects.equals(row.get("client_instance_id"),tracking.instanceId()) || row.get("event_actor_id")==null) return null;
            if(jdbc.queryForObject("SELECT count(*) FROM torrent_update_cleanup WHERE job_id=? AND state<>'KEPT'",Long.class,parent)==0) return null;
            if(jdbc.queryForObject("SELECT count(*) FROM torrent_update_cleanup WHERE job_id=? AND client_instance_id IS NULL",Long.class,parent)>0) return null;
            String action=switch(Objects.toString(row.get("policy"))) {case "REMOVE_TORRENT_AND_FILES"->"FILES";case "REMOVE_TORRENT"->"TORRENT";default->"RECORD";};
            long now=Instant.now().toEpochMilli();boolean cancelled="CANCELLED".equals(row.get("state"));
            jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,confirmation,direct_removal,delete_history,created_at,updated_at,expires_at,update_job_id) VALUES(?,'downloads',?,?,?,?,?,'[]',1,?,?,?,?,?)",id,action,row.get("event_actor_id"),Objects.toString(row.get("event_actor_username"),"Usuario"),settings.updateSearchContext(),cancelled?"CANCELLED":"QUEUED",row.get("delete_history"),now,now,Long.MAX_VALUE,parent);
            jdbc.update("""
                INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,old_hash,state,message,dispatched)
                SELECT c.id,?,row_number() OVER (ORDER BY c.created_at,c.id)-1,c.download_id,'',c.epl_id,
                  coalesce(b.title,'EPL '||c.epl_id),coalesce(rtrim(rtrim(cast(d.revision AS TEXT),'0'),'.'),'0'),'',c.hash,
                  CASE c.state WHEN 'REMOVED' THEN 'SUCCEEDED' WHEN 'REQUESTED' THEN 'UNCERTAIN' WHEN 'BLOCKED' THEN 'BLOCKED' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE 'PENDING' END,
                  c.message,c.state IN ('REMOVED','REQUESTED')
                FROM torrent_update_cleanup c LEFT JOIN torrent_downloads d ON d.id=c.download_id LEFT JOIN catalog_books b ON b.epl_id=c.epl_id
                WHERE c.job_id=? AND c.state<>'KEPT'
                """,id,parent);
            if(cancelled) jdbc.update("UPDATE revision_cleanup_items SET state='CANCELLED' WHERE job_id=? AND state IN ('PENDING','BLOCKED')",id);
            if(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items WHERE job_id=? AND state='PENDING'",Long.class,id)==0 && !cancelled)
                jdbc.update("UPDATE revision_cleanup_jobs SET state='COMPLETED',result=CASE WHEN EXISTS(SELECT 1 FROM revision_cleanup_items WHERE job_id=? AND state IN ('BLOCKED','UNCERTAIN')) THEN 'PARTIAL' ELSE 'SUCCEEDED' END WHERE id=?",id,id);
            // Old requests and their event history remain attached to their original update.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {log.info("Limpieza vinculada a actualización: actualización={}, limpieza={}",parent,id);}
            });
            return id;
        });
    }
    public static class DependencyPending extends RuntimeException {
        public DependencyPending(String message) {super(message);}
    }
}
