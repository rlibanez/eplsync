package com.rlibanez.eplsync.torrent.updates;

import java.time.Duration;
import java.util.concurrent.*;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import com.rlibanez.eplsync.tasks.InternalTaskSettings;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CleanupExecutionTests {
    @Test void timerReturnsWhileNetworkWaitsAndDoesNotQueueAnotherCycle() throws Exception {
        var queue=mock(CleanupQueue.class);var bulk=mock(BulkStore.class);
        var settings=mock(InternalTaskSettings.class);
        when(settings.interval(InternalTaskSettings.CLEANUP)).thenReturn(Duration.ZERO);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();release.await(5,TimeUnit.SECONDS);return null;}).when(queue).runAutomatic(100);
        var cleanup=new AutomaticUpdateCleanup(queue,bulk);
        ReflectionTestUtils.setField(cleanup,"internalSettings",settings);
        try(var requests=Executors.newSingleThreadExecutor()) {
            requests.submit(cleanup::schedule).get(2,TimeUnit.SECONDS);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(requests.submit(() -> {synchronized(bulk) {return true;}}).get(2,TimeUnit.SECONDS)).isTrue();
            cleanup.schedule();verify(queue,times(1)).runAutomatic(100);
        } finally {release.countDown();cleanup.close();}
    }
    @Test void cycleDeadlineInterruptsWaitAndDoesNotPoisonTheWorker() {
        CleanupBudget.run(Duration.ofMillis(50),() -> {
            try {new CountDownLatch(1).await(2,TimeUnit.SECONDS);fail("Deadline did not interrupt the wait");}
            catch(InterruptedException expected) {Thread.currentThread().interrupt();}
            assertThat(CleanupBudget.available()).isFalse();return null;
        });
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(CleanupBudget.available()).isTrue();
    }
    @Test void preexistingInterruptionIsPreserved() {
        Thread.currentThread().interrupt();
        try {CleanupBudget.run(() -> null);assertThat(Thread.currentThread().isInterrupted()).isTrue();}
        finally {Thread.interrupted();}
    }
}
