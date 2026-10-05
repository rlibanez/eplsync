package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.exception.CatalogDownloadException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One deadline across DNS, redirects and streaming, independent of socket inactivity. */
final class DownloadBudget {
    private static final ScheduledExecutorService TIMER = createTimer();
    private static ScheduledExecutorService createTimer() {
        var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "catalog-download-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
    private final long deadline;
    DownloadBudget(Duration duration) { deadline = System.nanoTime() + duration.toNanos(); }
    long remaining() throws CatalogDownloadException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new CatalogDownloadException("La descarga del catálogo supera el tiempo máximo permitido");
        return left;
    }
    ScheduledFuture<?> cancelAtDeadline(Runnable cancel) throws CatalogDownloadException {
        return TIMER.schedule(cancel, remaining(), TimeUnit.NANOSECONDS);
    }
    <T> T resolve(Callable<T> task) throws java.io.IOException, InterruptedException {
        var future = new FutureTask<T>(task);
        Thread.ofVirtual().name("catalog-dns").start(future);
        try { return future.get(remaining(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException ex) {
            throw new CatalogDownloadException("La descarga del catálogo supera el tiempo máximo permitido");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof java.io.IOException io) throw io;
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new java.io.IOException("No se pudo resolver el destino del catálogo");
        } finally { future.cancel(true); }
    }
}
