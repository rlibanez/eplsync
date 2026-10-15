package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** The timer only dispatches; one bounded worker performs network and cleanup work. */
@Component
@ConditionalOnProperty(prefix="eplsync.torrent.bulk",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class AutomaticUpdateCleanup {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AutomaticUpdateCleanup.class);
    private final CleanupQueue queue;
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("automatic-update-cleanup").factory());
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.tasks.InternalTaskSettings internalSettings;
    private volatile long lastCycle=System.nanoTime();
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.cleanup.enabled:true}") private boolean enabled=true;
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.cleanup.batch-size:100}") private int batchSize=100;
    @org.springframework.beans.factory.annotation.Value("${eplsync.torrent.cleanup.interval:30s}") private String interval="30s";
    public AutomaticUpdateCleanup(CleanupQueue queue,BulkStore bulk) {
        this.queue=queue;
    }
    @jakarta.annotation.PostConstruct
    void validate() {
        var duration=org.springframework.boot.convert.DurationStyle.detectAndParse(interval);
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("cleanup.interval debe ser positivo");
        if (batchSize<1 || batchSize>1000) throw new IllegalArgumentException("cleanup.batch-size debe estar entre 1 y 1000");
    }
    @Scheduled(fixedDelay=1000)
    public void schedule() {
        if(!enabled || closed) return;
        long now=System.nanoTime();
        if(now-lastCycle<internalSettings.interval(com.rlibanez.eplsync.tasks.InternalTaskSettings.CLEANUP).toNanos()) return;
        if(running.compareAndSet(false,true)) {
            try {executor.execute(() -> {try {tick();} finally {lastCycle=System.nanoTime();running.set(false);}});}
            catch(java.util.concurrent.RejectedExecutionException ex) {running.set(false);if(!closed) throw ex;}
        }
    }
    public void tick() {
        if (!enabled) return;
        try {
            CleanupBudget.run(() -> {
                com.rlibanez.eplsync.events.EventContext.withOrigin(com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED,() -> {
                    queue.runAutomatic(batchSize); return null;
                });
                return null;
            });
        } catch(RuntimeException ex) { log.warn("Limpieza automática pendiente: tipo={}",ex.getClass().getSimpleName()); }
    }
    @jakarta.annotation.PreDestroy public void close() {closed=true;executor.shutdownNow();}
}
