package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.config.WorkerActivity;
import com.rlibanez.eplsync.jobs.RetryAuthorization;
import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only remote checks, separated from submission and never performed under the store monitor. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="eplsync.torrent.bulk",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class BulkDownloadMonitor {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BulkDownloadMonitor.class);
    private final JdbcTemplate jdbc;
    private final BulkStore store;
    private final TorrentClientService client;
    private final DownloadTrackingService tracking;
    private final MaintenanceGate maintenance;
    private final RetryAuthorization authorization;
    private final WorkerActivity.Probe probe=new WorkerActivity.Probe();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("torrent-download-monitor").factory());
    private final AtomicBoolean running=new AtomicBoolean();
    private static final String WAITING="track_downloads=1 AND state IN ('QUEUED','RUNNING','DOWNLOAD_WAIT','CLEANUP_WAIT') AND EXISTS(SELECT 1 FROM torrent_bulk_items i WHERE i.job_id=torrent_bulk_jobs.id AND i.state IN ('ACCEPTED','ALREADY_EXISTS') AND coalesce(i.download_state,'') NOT IN ('DOWNLOADED','IGNORED'))";
    public BulkDownloadMonitor(JdbcTemplate jdbc,BulkStore store,TorrentClientService client,DownloadTrackingService tracking,MaintenanceGate maintenance,RetryAuthorization authorization) {
        this.jdbc=jdbc;this.store=store;this.client=client;this.tracking=tracking;this.maintenance=maintenance;this.authorization=authorization;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void schedule() {
        if(probe.ready() && running.compareAndSet(false,true)) try {executor.execute(()->{try {tick();}catch(RuntimeException ex) {log.debug("Comprobación de descargas aplazada: tipo={}",ex.getClass().getSimpleName());}finally {running.set(false);}});}
        catch(RejectedExecutionException ex) {running.set(false);}
    }
    public void tick() {
        if(!maintenance.enter(false)) return;
        try {
            long snapshot=probe.snapshot(),now=Instant.now().toEpochMilli();
            var jobs=jdbc.queryForList("SELECT id,target_fingerprint FROM torrent_bulk_jobs WHERE "+WAITING+" AND next_download_check<=? ORDER BY next_download_check,created_at,id LIMIT 1",now);
            if(jobs.isEmpty()) {
                Long due=jdbc.queryForObject("SELECT min(next_download_check) FROM torrent_bulk_jobs WHERE "+WAITING,Long.class);
                probe.emptyUntil(snapshot,due==null?Long.MAX_VALUE:due);return;
            }
            String id=(String)jobs.getFirst().get("id"),instance=(String)jobs.getFirst().get("target_fingerprint");
            if(!authorization.torrent(id,false,false)) {synchronized(store) {store.suspendDownloads(id,"El usuario del trabajo ya no puede consultar sus descargas; revisa los permisos");}return;}
            if(!Objects.equals(instance,tracking.instanceId())) {synchronized(store) {store.suspendDownloads(id,"El cliente torrent cambió; restaura el destino original antes de reanudar");}return;}
            Set<String> hashes=new HashSet<>(jdbc.queryForList("SELECT DISTINCT hash FROM torrent_bulk_items WHERE job_id=? AND state IN ('ACCEPTED','ALREADY_EXISTS') AND coalesce(download_state,'') NOT IN ('DOWNLOADED','IGNORED') AND hash IS NOT NULL",String.class,id));
            try {
                if(!hashes.isEmpty()) client.exclusiveClient(adapter->{
                    if(!Objects.equals(instance,tracking.instanceId())) throw new IllegalStateException("El destino cambió durante la comprobación");
                    var remote=new HashMap<String,RemoteTorrent>();
                    for(var row:hashes.size()>1000?adapter.listTorrents():adapter.listTorrents(hashes)) for(String alias:row.aliases()) remote.put(alias,row);
                    tracking.observeSelected(remote,hashes);return null;
                });
                synchronized(store) {store.refreshDownloads(id,instance);}
            } catch(RuntimeException ex) {
                if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) {log.debug("Seguimiento de descargas aplazado por SQLite: trabajo={}",id);return;}
                synchronized(store) {store.deferDownloadCheck(id);}
                log.debug("No se pudo observar la descarga: trabajo={}, tipo={}",id,ex.getClass().getSimpleName());
            }
        } finally {maintenance.leave(false);}
    }
    @jakarta.annotation.PreDestroy public void close() {executor.shutdownNow();}
}
