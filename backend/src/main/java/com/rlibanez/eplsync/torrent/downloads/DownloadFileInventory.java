package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Local inventory only. Availability is always checked live before reading or sending. */
@Service
public class DownloadFileInventory {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final TorrentClientService client;private final DownloadTrackingService tracking;
    private final com.rlibanez.eplsync.maintenance.MaintenanceGate maintenance;
    private final com.rlibanez.eplsync.config.TorrentProperties properties;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DownloadFileInventory.class);
    private final AtomicBoolean running=new AtomicBoolean();
    private final com.rlibanez.eplsync.config.WorkerActivity.Probe probe=new com.rlibanez.eplsync.config.WorkerActivity.Probe();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("torrent-file-inventory").factory());
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.inventory.worker-enabled:true}") private boolean enabled;
    public DownloadFileInventory(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,TorrentClientService client,DownloadTrackingService tracking,com.rlibanez.eplsync.maintenance.MaintenanceGate maintenance,com.rlibanez.eplsync.config.TorrentProperties properties) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.client=client;this.tracking=tracking;this.maintenance=maintenance;this.properties=properties;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void schedule() {
        if(enabled && properties.isEnabled() && probe.ready() && running.compareAndSet(false,true))
            try {executor.execute(()->{try {tick();}catch(RuntimeException ex) {log.debug("Inventario de archivos aplazado: causa={}",com.rlibanez.eplsync.exception.OperationDiagnostics.technical(ex));}finally {running.set(false);}});}catch(RejectedExecutionException ex) {running.set(false);}
    }
    public void tick() {
        if(!maintenance.enter(false)) return;
        try {
            long before=probe.snapshot();String instance=tracking.instanceId();
            var rows=jdbc.queryForList("SELECT id,hash FROM torrent_downloads WHERE client_instance_id=? AND inventory_state<>'CHECKED' AND status<>'NOT_FOUND' AND inventory_next_check<=? ORDER BY inventory_next_check,id LIMIT 5",instance,System.currentTimeMillis());
            if(rows.isEmpty()) {probe.empty(before);return;}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            for(var row:rows) {
                if(System.nanoTime()>deadline) break;
                String id=(String)row.get("id"),hash=(String)row.get("hash");
                try {client.synchronizeClient(adapter->{
                    if(!instance.equals(tracking.instanceId())) return null;
                    record(id,instance,hash,adapter.listFiles(hash));return null;
                });} catch(RuntimeException ex) {
                    if(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(ex)) return;
                    jdbc.update("UPDATE torrent_downloads SET inventory_state='ERROR',inventory_error=?,inventory_next_check=? WHERE id=? AND client_instance_id=? AND hash=?",
                        "No se pudo comprobar el contenido del torrent; se reintentará automáticamente",System.currentTimeMillis()+60_000,id,instance,hash);
                }
            }
        } finally {maintenance.leave(false);}
    }
    public void record(String id,String instance,String hash,List<TorrentFile> files) {
        if(files.size()>100_000) throw new com.rlibanez.eplsync.exception.UserInputException("El listado de archivos del torrent es demasiado grande");
        var seen=new HashSet<Integer>();
        for(var file:files) {
            String path=file.name().replace('\\','/');
            if(file.index()<0 || file.size()<0 || !seen.add(file.index()) || path.length()>8192 || path.isBlank() || path.startsWith("/") || path.contains(":") || path.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(path.split("/",-1)).anyMatch(p->p.equals("..") || p.equals(".")))
                throw new com.rlibanez.eplsync.exception.UserInputException("El cliente devolvió un listado de archivos inválido");
        }
        tx.executeWithoutResult(status->{
            if(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE id=? AND client_instance_id=? AND hash=?",Long.class,id,instance,hash)==0) return;
            if(files.isEmpty()) {
                // Metadata may still be arriving: retain any previous complete inventory.
                jdbc.update("UPDATE torrent_downloads SET inventory_state=CASE WHEN inventory_checked_at IS NULL THEN 'PENDING' ELSE inventory_state END,inventory_next_check=? WHERE id=?",System.currentTimeMillis()+300_000,id);return;
            }
            var previous=jdbc.queryForList("SELECT file_index,relative_path,size FROM torrent_download_files WHERE download_id=? ORDER BY file_index",id);
            var sorted=files.stream().sorted(Comparator.comparingInt(TorrentFile::index)).toList();
            boolean same=previous.size()==sorted.size();
            if(same) for(int n=0;n<sorted.size();n++) {
                var a=previous.get(n);var b=sorted.get(n);
                if(((Number)a.get("file_index")).intValue()!=b.index() || !a.get("relative_path").equals(b.name().replace('\\','/')) || ((Number)a.get("size")).longValue()!=b.size()) {same=false;break;}
            }
            if(!same) {
                jdbc.update("DELETE FROM torrent_download_files WHERE download_id=?",id);
                var batch=sorted.stream().map(f->new Object[]{id+":"+f.index(),id,f.index(),f.name().replace('\\','/'),f.size(),BookFormats.extension(f.name())}).toList();
                for(int n=0;n<batch.size();n+=500) jdbc.batchUpdate("INSERT INTO torrent_download_files(id,download_id,file_index,relative_path,size,extension) VALUES(?,?,?,?,?,?)",batch.subList(n,Math.min(n+500,batch.size())));
            }
            jdbc.update("UPDATE torrent_downloads SET inventory_state='CHECKED',inventory_checked_at=?,inventory_error=NULL,inventory_next_check=0 WHERE id=?",Instant.now().toString(),id);
        });
    }
    @jakarta.annotation.PreDestroy void close() {enabled=false;executor.shutdownNow();}
}
