package com.rlibanez.eplsync.qbittorrent;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import com.rlibanez.eplsync.exception.TorrentConnectionException;

/** Bounds actual bytes, including chunked bodies, and the entire body reception time. */
final class BoundedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "torrent-response-timeout"); thread.setDaemon(true); return thread;
    });
    private final HttpResponse.BodySubscriber<T> target;
    private final long maximum;
    private ScheduledFuture<?> timeout;
    private final java.time.Duration duration;
    private Flow.Subscription subscription;
    private long received;
    private boolean finished;
    BoundedBodySubscriber(HttpResponse.BodySubscriber<T> target, long maximum, java.time.Duration duration) {
        this.target = target; this.maximum = maximum;
        this.duration = duration;
    }
    public CompletionStage<T> getBody() { return target.getBody(); }
    public synchronized void onSubscribe(Flow.Subscription value) {
        subscription = value;
        timeout = timer.schedule(() -> fail(new TorrentConnectionException(TorrentConnectionException.Reason.TIMEOUT)),
                duration.toNanos(), TimeUnit.NANOSECONDS);
        target.onSubscribe(value);
        if (finished) value.cancel();
    }
    public synchronized void onNext(List<ByteBuffer> buffers) {
        if (finished) return;
        for (var buffer : buffers) {
            received += buffer.remaining();
            if (received > maximum) {
                fail(new TorrentConnectionException(TorrentConnectionException.Reason.RESPONSE_TOO_LARGE)); return;
            }
        }
        target.onNext(buffers);
    }
    public synchronized void onError(Throwable error) { fail(error); }
    private synchronized void fail(Throwable error) {
        if (finished) return;
        finished = true; if (timeout != null) timeout.cancel(false);
        if (subscription != null) subscription.cancel();
        target.onError(error);
    }
    public synchronized void onComplete() {
        if (finished) return;
        finished = true; if (timeout != null) timeout.cancel(false); target.onComplete();
    }
}
