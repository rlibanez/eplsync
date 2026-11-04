package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.exception.TorrentBusyException;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TorrentCoordinationTests {
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final jakarta.persistence.EntityManager database=mock(jakarta.persistence.EntityManager.class);
    final TorrentProperties properties=new TorrentProperties();
    final DownloadTrackingService tracking=new DownloadTrackingService(mock(DownloadRepository.class),mock(CatalogBookRepository.class),properties,new MagnetLinkBuilder(properties),transactions,database);

    @Test void queuedSyncAllowsNestedCleanupCallsAndKeepsTheClientGuardUntilItFinishes() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> pending=tracking.exclusive(()->{
                var future=executor.submit(()->tracking.awaitExclusive(()->{
                    entered.countDown();await(release);return "synchronized";
                }));
                assertThatThrownBy(()->future.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                // Existing cleanup operations may re-enter their guard while a sync is queued.
                assertThat(tracking.exclusive(()->"nested cleanup")).isEqualTo("nested cleanup");
                return future;
            });
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->tracking.exclusive(()->"unsafe overlap")).isInstanceOf(TorrentBusyException.class);
            } finally {release.countDown();}
            assertThat(pending.get(2,TimeUnit.SECONDS)).isEqualTo("synchronized");
            assertThat(tracking.exclusive(()->"next operation")).isEqualTo("next operation");
        }
        verifyNoInteractions(transactions,database);
    }
    @Test void interruptedWaitNeverExecutesTheOperationAndPreservesTheInterruptFlag() throws Exception {
        var started=new CountDownLatch(1);var done=new CountDownLatch(1);
        var called=new AtomicBoolean();var interrupted=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        var waiter=Thread.ofPlatform().unstarted(()->{
            started.countDown();
            try {tracking.awaitExclusive(()->{called.set(true);return null;});}
            catch(Throwable ex) {failure.set(ex);interrupted.set(Thread.currentThread().isInterrupted());}
            finally {done.countDown();}
        });
        tracking.exclusive(()->{
            waiter.start();await(started);waiter.interrupt();await(done);return null;
        });
        waiter.join(2000);
        assertThat(failure.get()).isInstanceOf(TorrentBusyException.class).hasMessageContaining("interrumpido");
        assertThat(called).isFalse();assertThat(interrupted).isTrue();
        assertThat(tracking.awaitExclusive(()->"available")).isEqualTo("available");
        verifyNoInteractions(transactions,database);
    }
    @Test @org.junit.jupiter.api.Timeout(40)
    void boundedWaitDoesNotBorrowAConnectionOrDispatchAfterTimingOut() {
        var called=new AtomicBoolean();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tracking.exclusive(()->{
                var waiter=executor.submit(()->tracking.awaitExclusive(()->{called.set(true);return null;}));
                assertThatThrownBy(()->waiter.get(35,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TorrentBusyException.class).hasRootCauseMessage("El cliente torrent sigue ocupado tras 30 segundos; no se ha iniciado la sincronización. Vuelve a intentarlo cuando termine la operación actual");
                return null;
            });
        }
        assertThat(called).isFalse();verifyNoInteractions(transactions,database);
        assertThat(tracking.exclusive(()->"available")).isEqualTo("available");
    }
    @Test void failedSynchronizationReleasesItsGuardWithoutReplayingTheAction() {
        var calls=new AtomicInteger();var failure=new IllegalStateException("remote failure");
        assertThatThrownBy(()->tracking.awaitExclusive(()->{calls.incrementAndGet();throw failure;})).isSameAs(failure);
        assertThat(calls).hasValue(1);assertThat(tracking.exclusive(()->"available")).isEqualTo("available");
        verifyNoInteractions(transactions,database);
    }
    private static void await(CountDownLatch latch) {
        try {assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    }
}
