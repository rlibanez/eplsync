package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Temporary timer adapter; a future task scheduler can invoke the same record queue. */
@Component
@ConditionalOnProperty(prefix="eplsync.torrent.bulk",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class AutomaticUpdateCleanup {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AutomaticUpdateCleanup.class);
    private final CleanupQueue queue;
    private final BulkStore bulk;
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.cleanup.enabled:true}") private boolean enabled=true;
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.cleanup.batch-size:100}") private int batchSize=100;
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.cleanup.interval:30s}") private String interval="30s";
    public AutomaticUpdateCleanup(CleanupQueue queue,BulkStore bulk) {
        this.queue=queue;this.bulk=bulk;
    }
    @jakarta.annotation.PostConstruct
    void validate() {
        var duration=org.springframework.boot.convert.DurationStyle.detectAndParse(interval);
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("cleanup.interval debe ser positivo");
        if (batchSize<1 || batchSize>1000) throw new IllegalArgumentException("cleanup.batch-size debe estar entre 1 y 1000");
    }
    @Scheduled(fixedDelayString="${eplsync.torrent.cleanup.interval:30s}",initialDelayString="${eplsync.torrent.cleanup.interval:30s}")
    public void tick() {
        if (!enabled) return;
        try {
            synchronized(bulk) {
                com.rlibanez.eplsync.events.EventContext.withOrigin(com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED,() -> {
                    queue.runAutomatic(batchSize); return null;
                });
            }
        } catch(RuntimeException ex) { log.warn("Limpieza automática pendiente: tipo={}",ex.getClass().getSimpleName()); }
    }
}
