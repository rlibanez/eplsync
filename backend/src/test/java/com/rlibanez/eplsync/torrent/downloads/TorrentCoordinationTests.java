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
    @Test void inventoryDoesNotQueueAnExclusiveLockAndYieldsToBulkPriority() throws Exception {
        var called=new AtomicBoolean();
        var priority=tracking.prioritizeSubmissions();
        assertThat(tracking.inventory(()->called.set(true))).isFalse();assertThat(called).isFalse();
        priority.close();priority.close();assertThat(tracking.inventoryAllowed()).isTrue();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tracking.exclusive(()->{
                try {assertThat(executor.submit(()->tracking.inventory(()->called.set(true))).get(200,TimeUnit.MILLISECONDS)).isFalse();}
                catch(Exception ex) {throw new AssertionError(ex);}return null;
            });
        }
        assertThat(called).isFalse();verifyNoInteractions(transactions,database);
    }
    @Test void inProgressInventoryAllowsSharedReadsButKeepsDeletionExcluded() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var inventory=executor.submit(()->tracking.inventory(()->{entered.countDown();await(release);}));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(tracking.inventory(()->{})).isTrue();
                assertThatThrownBy(()->tracking.exclusive(()->"delete")).isInstanceOf(TorrentBusyException.class);
            } finally {release.countDown();}
            assertThat(inventory.get(2,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(tracking.exclusive(()->"delete")).isEqualTo("delete");verifyNoInteractions(transactions,database);
    }
    private com.rlibanez.eplsync.torrent.TorrentDownload command(String hash) {
        return new com.rlibanez.eplsync.torrent.TorrentDownload(hash,"magnet",true,null,null,null,com.rlibanez.eplsync.model.CatalogBook.builder().eplId(1L).revision(1.0).title("Book").build());
    }
    @Test void observationRunsDuringSubmissionButStillExcludesDeletion() throws Exception {
        var sending=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var send=executor.submit(()->tracking.submit(command("hash"),()->{sending.countDown();await(release);return com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED;}));
            try {
                assertThat(sending.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(tracking.observation(()->"read during send")).isEqualTo("read during send");
                assertThatThrownBy(()->tracking.exclusive(()->"delete")).isInstanceOf(TorrentBusyException.class);
            } finally {release.countDown();}
            send.get(2,TimeUnit.SECONDS);
        }
        assertThat(tracking.exclusive(()->"delete after observation")).isEqualTo("delete after observation");
    }
    @Test void snapshotSkipsBothAlreadyActiveAndNewSubmissionsButAppliesOtherHashes() throws Exception {
        var service=spy(tracking);
        doNothing().when(service).observeSelected(anyMap(),anySet());
        var sending=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var send=executor.submit(()->service.submit(command("active"),()->{sending.countDown();await(release);return com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED;}));
            try {
                assertThat(sending.await(2,TimeUnit.SECONDS)).isTrue();
                service.observation(()->{
                    service.observeConcurrent(java.util.Set.of("active","new","unchanged"),()->{
                        service.submit(command("new"),()->com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED);
                        return java.util.Map.of(); // Old snapshot: missing is safe only for unchanged.
                    });return null;
                });
                verify(service).observeSelected(java.util.Map.of(),java.util.Set.of("unchanged"));
            } finally {release.countDown();}
            send.get(2,TimeUnit.SECONDS);
        }
        service.observation(()->{service.observeConcurrent(java.util.Set.of("active","new"),java.util.Map::of);return null;});
        verify(service).observeSelected(java.util.Map.of(),java.util.Set.of("active","new"));
    }
    @Test void failedObservationReleasesGuardAndDoesNotApplyPartialResults() {
        var service=spy(tracking);doNothing().when(service).observeSelected(anyMap(),anySet());
        assertThatThrownBy(()->service.observation(()->{
            service.observeConcurrent(java.util.Set.of("hash"),()->{throw new IllegalStateException("network");});return null;
        })).hasMessage("network");
        verify(service,never()).observeSelected(anyMap(),anySet());
        assertThat(service.exclusive(()->"cleanup")).isEqualTo("cleanup");
    }
    @Test void observationYieldsToAnExclusiveOperationWithoutBorrowingSqlConnections() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tracking.exclusive(()->{
                try {assertThatThrownBy(()->executor.submit(()->tracking.observation(()->"unsafe")).get(1,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TorrentBusyException.class);}
                catch(Exception ex) {throw new AssertionError(ex);}return null;
            });
        }
        verifyNoInteractions(transactions,database);
    }
    @Test void ordinaryCleanupQueuesBehindReadersAndGetsATurnBeforeNewObservations() throws Exception {
        var readEntered=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);
        var cleanupEntered=new CountDownLatch(1);var releaseCleanup=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var reader=executor.submit(()->tracking.observation(()->{readEntered.countDown();await(releaseRead);return null;}));
            try {
                assertThat(readEntered.await(2,TimeUnit.SECONDS)).isTrue();
                var cleanup=executor.submit(()->tracking.cleanupTurn(()->tracking.exclusive(()->{
                    cleanupEntered.countDown();await(releaseCleanup);return tracking.exclusive(()->"nested cleanup");
                })));
                try {
                    boolean queued=false;
                    for(int i=0;i<100 && !queued;i++) {
                        try {tracking.observation(()->null);Thread.sleep(5);}
                        catch(TorrentBusyException expected) {queued=true;}
                    }
                    assertThat(queued).isTrue();
                    assertThat(tracking.inventory(()->{})).isFalse();
                    releaseRead.countDown();
                    assertThat(cleanupEntered.await(2,TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(()->tracking.observation(()->"new read")).isInstanceOf(TorrentBusyException.class);
                } finally {releaseRead.countDown();releaseCleanup.countDown();}
                assertThat(cleanup.get(2,TimeUnit.SECONDS)).isEqualTo("nested cleanup");
            } finally {releaseRead.countDown();releaseCleanup.countDown();}
            reader.get(2,TimeUnit.SECONDS);
        }
        assertThat(tracking.observation(()->"next read")).isEqualTo("next read");
        verifyNoInteractions(transactions,database);
    }
    @Test void cleanupTurnFailureRestoresOrdinaryNonBlockingAccess() throws Exception {
        assertThatThrownBy(()->tracking.cleanupTurn(()->{throw new IllegalStateException("failed cleanup");})).hasMessage("failed cleanup");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tracking.observation(()->{
                try {assertThatThrownBy(()->executor.submit(()->tracking.exclusive(()->"unsafe")).get(200,TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TorrentBusyException.class);}
                catch(Exception ex) {throw new AssertionError(ex);}return null;
            });
        }
    }
    private static void await(CountDownLatch latch) {
        try {assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    }
}
