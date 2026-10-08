package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.Duration;

/** Installation-level retention, independent of torrent integration and user accounts. */
@Component
public class BulkRetention {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkRetention.class);
    private final BulkRetentionStore store;
    private final MaintenanceGate maintenance;
    @Value("${eplsync.torrent.bulk.retention.enabled:true}") private boolean enabled = true;
    @Value("${eplsync.torrent.bulk.retention.days:90}") private int days = 90;
    @Value("${eplsync.torrent.bulk.retention.interval:1h}") private String interval = "1h";
    public BulkRetention(BulkRetentionStore store, MaintenanceGate maintenance) {
        this.store=store; this.maintenance=maintenance;
    }
    @jakarta.annotation.PostConstruct
    void validate() {
        if (days < 1 || days > 36500) throw new IllegalArgumentException("bulk.retention.days debe estar entre 1 y 36500");
        var duration = DurationStyle.detectAndParse(interval);
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("bulk.retention.interval debe ser positivo");
    }
    @Scheduled(fixedDelayString="${eplsync.torrent.bulk.retention.interval:1h}",
            initialDelayString="${eplsync.torrent.bulk.retention.interval:1h}")
    public void tick() {
        if (!enabled) return;
        var cutoff = Instant.now().minus(Duration.ofDays(days));
        long deadline = System.nanoTime()+Duration.ofSeconds(10).toNanos();
        long jobs=0, items=0, cleanup=0;
        try {
            for (int batch=0; batch<20 && System.nanoTime()<deadline; batch++) {
                if (Thread.currentThread().isInterrupted() || !maintenance.enter(false)) break;
                BulkRetentionStore.Result result;
                try { result=store.purgeOne(cutoff); }
                finally { maintenance.leave(false); }
                jobs+=result.jobs(); items+=result.items(); cleanup+=result.cleanup();
                if (result.jobs()==0) break;
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo completar la retención de trabajos; se reintentará en el siguiente ciclo",ex);
        } finally {
            if (jobs>0) log.info("Retención de trabajos: trabajos={}, elementos={}, limpiezasFinalizadas={}",jobs,items,cleanup);
        }
    }
}
