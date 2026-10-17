package com.rlibanez.eplsync.config;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class WorkerActivityTests {
    @Test void committedWritesWakeEmptyQueuesButRollbacksDoNot() {
        var probe=new WorkerActivity.Probe();
        assertThat(probe.ready()).isTrue();
        probe.empty(probe.snapshot());assertThat(probe.ready()).isFalse();
        TransactionSynchronizationManager.initSynchronization();
        try {
            WorkerActivity.changed();assertThat(probe.ready()).isFalse();
            // A rollback never invokes afterCommit.
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        assertThat(probe.ready()).isFalse();
        TransactionSynchronizationManager.initSynchronization();
        try {
            WorkerActivity.changed();assertThat(probe.ready()).isFalse();
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        assertThat(probe.ready()).isTrue();
    }
    @Test void changesDuringEmptyProbeCannotBeLost() {
        var probe=new WorkerActivity.Probe();long before=probe.snapshot();
        WorkerActivity.changed();probe.empty(before);
        assertThat(probe.ready()).isTrue();
    }
    @Test void fallbackDetectsOutOfBandChangesWithoutContinuousPolling() {
        var nano=new AtomicLong();var epoch=new AtomicLong(1000);
        var probe=new WorkerActivity.Probe(nano::get,epoch::get);
        probe.empty(probe.snapshot());
        nano.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(29));assertThat(probe.ready()).isFalse();
        nano.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));assertThat(probe.ready()).isTrue();
        probe.emptyUntil(probe.snapshot(),2000);
        nano.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(999));assertThat(probe.ready()).isFalse();
        nano.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));assertThat(probe.ready()).isTrue();
    }
    @Test void dueRetriesDoNotWaitForIdleFallback() {
        var probe=new WorkerActivity.Probe();
        probe.emptyUntil(probe.snapshot(),System.currentTimeMillis());
        assertThat(probe.ready()).isTrue();
        probe.emptyUntil(probe.snapshot(),System.currentTimeMillis()+60000);
        assertThat(probe.ready()).isFalse();
    }
}
