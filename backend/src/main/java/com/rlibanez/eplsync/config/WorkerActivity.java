package com.rlibanez.eplsync.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Empty queues need another database probe only after committed changes. The
 * fallback also detects changes made outside the application's write paths. */
public final class WorkerActivity {
    private static final AtomicLong revision=new AtomicLong();
    private WorkerActivity() {}
    static void committed() {revision.incrementAndGet();}
    static void changed() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            if(TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(CommittedChange.class::isInstance))
                TransactionSynchronizationManager.registerSynchronization(new CommittedChange());
        } else committed();
    }
    private static final class CommittedChange implements TransactionSynchronization {
        @Override public void afterCommit() {committed();}
    }
    public static final class Probe {
        private long observed=-1,until;
        private final java.util.function.LongSupplier nanoClock,epochClock;
        public Probe() {this(System::nanoTime,System::currentTimeMillis);}
        Probe(java.util.function.LongSupplier nanoClock,java.util.function.LongSupplier epochClock) {
            this.nanoClock=nanoClock;this.epochClock=epochClock;
        }
        public synchronized long snapshot() {return revision.get();}
        public synchronized boolean ready() {return observed!=revision.get() || nanoClock.getAsLong()-until>=0;}
        public synchronized void empty(long beforeQuery) {
            emptyUntil(beforeQuery,Long.MAX_VALUE);
        }
        public synchronized void emptyUntil(long beforeQuery,long dueEpochMillis) {
            long delay=Math.min(Duration.ofSeconds(30).toMillis(),Math.max(0,dueEpochMillis-epochClock.getAsLong()));
            observed=beforeQuery;until=nanoClock.getAsLong()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay);
        }
    }
}
