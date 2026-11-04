package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.exception.TorrentConnectionException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TorrentSubmissionContextTests {
    @Test void concurrentFailedIndexLoadsAreSharedAndRecoveryRechecksTheIndex() throws Exception {
        var clock=new AtomicLong();var context=new TorrentSubmissionContext(clock::get);var loads=new AtomicInteger();
        var failure=new TorrentConnectionException(TorrentConnectionException.Reason.UPSTREAM);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<10;n++) futures.add(executor.submit(()->assertThatThrownBy(()->context.containsTorrent("A",()->{loads.incrementAndGet();throw failure;})).isSameAs(failure)));
            for(var future:futures) future.get(5,TimeUnit.SECONDS);
        }
        assertThat(loads).hasValue(1);
        // An older POST failing concurrently must not reset the cooldown for the failed shared read.
        context.invalidateTorrents();
        assertThatThrownBy(()->context.containsTorrent("A",()->{loads.incrementAndGet();return Set.of("A");})).isSameAs(failure);
        assertThat(loads).hasValue(1);
        clock.set(TimeUnit.SECONDS.toNanos(5));
        assertThat(context.containsTorrent("a",()->{loads.incrementAndGet();return Set.of("A");})).isTrue();
        assertThat(loads).hasValue(2);
        context.invalidateTorrents();
        assertThat(context.containsTorrent("B",()->{loads.incrementAndGet();return Set.of("A","B");})).isTrue();
        assertThat(loads).hasValue(3);
    }
    @Test void aNewExecutionDoesNotInheritThePreviousConnectionFailure() {
        var first=new TorrentSubmissionContext();var second=new TorrentSubmissionContext();
        assertThatThrownBy(()->first.containsTorrent("A",()->{throw new TorrentConnectionException(TorrentConnectionException.Reason.TIMEOUT);})).isInstanceOf(TorrentConnectionException.class);
        assertThat(second.containsTorrent("A",()->Set.of("A"))).isTrue();
    }

    @Test void acknowledgementsNeedLivePresenceAndConcurrentConfirmationsAreBatched() throws Exception {
        var context=new TorrentSubmissionContext();
        assertThat(context.containsTorrent("A",Set::of)).isFalse();
        var reads=new AtomicInteger();var largest=new AtomicInteger();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var futures=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<10;n++) {
                String hash="HASH"+n;
                futures.add(executor.submit(()->{start.await();context.confirmTorrent(hash, hashes->{reads.incrementAndGet();largest.accumulateAndGet(hashes.size(),Math::max);return hashes;});return true;}));
            }
            start.countDown();for(var future:futures) future.get(5,TimeUnit.SECONDS);
        }
        assertThat(largest.get()).isGreaterThan(1);
        assertThat(reads.get()).isLessThan(10);
    }
    @Test void successfulAcknowledgementWithoutPresenceRemainsUnconfirmed() {
        var context=new TorrentSubmissionContext();
        assertThat(context.containsTorrent("A",Set::of)).isFalse();
        assertThatThrownBy(()->context.confirmTorrent("A",hashes->Set.of(),java.time.Duration.ofMillis(150)))
            .isInstanceOf(TorrentConnectionException.class).hasMessageContaining("confirmado");
        assertThat(context.containsTorrent("A",()->Set.of("A"))).isFalse();
        // A restarted execution must load the live index, rather than repeat the POST.
        assertThat(new TorrentSubmissionContext().containsTorrent("A",()->Set.of("A"))).isTrue();
    }
    @Test void communicationFailureIsNotReportedAsAbsenceOrAcceptance() {
        var context=new TorrentSubmissionContext();
        var failure=new TorrentConnectionException(TorrentConnectionException.Reason.UPSTREAM);
        assertThatThrownBy(()->context.confirmTorrent("A",hashes->{throw failure;})).isSameAs(failure);
        assertThatThrownBy(()->context.confirmTorrent("B",hashes->Set.of("B"))).isSameAs(failure);
    }
}
