package com.rlibanez.eplsync.torrent.updates;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Coalesces accepted submissions; all durable state remains in the cleanup queue. */
@Component
@ConditionalOnProperty(prefix="eplsync.torrent.bulk",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class ImmediateUpdateCleanup {
    public record SubmissionAccepted() {}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImmediateUpdateCleanup.class);
    private final CleanupQueue queue;
    private final BulkStore bulk;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("immediate-update-cleanup").factory());
    private final AtomicBoolean pending=new AtomicBoolean(),running=new AtomicBoolean();
    private volatile boolean closed;
    public ImmediateUpdateCleanup(CleanupQueue queue,BulkStore bulk) {this.queue=queue;this.bulk=bulk;}
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void accepted(SubmissionAccepted event) {wake();}
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recover() {wake();}
    private void wake() {
        if(closed) return;
        pending.set(true);
        if(running.compareAndSet(false,true)) {
            try {executor.execute(this::drain);}
            catch(RejectedExecutionException ex) {running.set(false);if(!closed) throw ex;}
        }
    }
    private void drain() {
        try {
            while(!closed && pending.getAndSet(false)) {
                var cycle=Instant.ofEpochMilli(System.currentTimeMillis());
                try {
                    com.rlibanez.eplsync.events.EventContext.withOrigin(com.rlibanez.eplsync.events.EventContext.Origin.MANUAL,() -> {
                        int checked;
                        do {synchronized(bulk) {checked=queue.runImmediate(100,cycle);}} while(!closed && checked==100);
                        return null;
                    });
                } catch(RuntimeException ex) {log.warn("Limpieza inmediata pendiente: tipo={}",ex.getClass().getSimpleName());}
            }
        } finally {running.set(false);if(!closed && pending.get()) wake();}
    }
    @jakarta.annotation.PreDestroy public void close() {closed=true;executor.shutdownNow();}
}
