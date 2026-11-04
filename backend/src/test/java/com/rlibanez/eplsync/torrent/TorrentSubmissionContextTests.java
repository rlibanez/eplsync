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
}
