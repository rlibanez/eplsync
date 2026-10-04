package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.TorrentConnectionException;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.service.TorrentClientService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** Un coordinador por instancia, con red concurrente y escrituras de estado serializadas. */
@Component
@ConditionalOnProperty(prefix = "eplsync.torrent.bulk", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BulkWorker {
    private static final Logger log = LoggerFactory.getLogger(BulkWorker.class);
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.rlibanez.eplsync.settings.ServerSettings settings;
    private com.rlibanez.eplsync.settings.ServerSettings.Snapshot activeSettings;
    private final BulkStore store;
    private final TorrentClientService client;
    private final TorrentProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, PendingSend> inFlight = new HashMap<>();
    private final Deque<String> buffer = new ArrayDeque<>();
    private String activeId;
    private com.rlibanez.eplsync.torrent.TorrentSubmissionContext submissionContext;
    private long activeSelectedItems;
    private long activeProcessedItems;
    private int lastProgressCheckpoint;
    private long nextDispatch;
    private boolean ready;
    private boolean closing;
    private Thread coordinator;
    private static final long IDLE_WAIT = TimeUnit.MILLISECONDS.toNanos(250);

    private record PendingSend(BulkItem item, Future<Outcome> future) {}

    record Outcome(BulkItem.State state, String message, boolean pause, boolean retry) {}

    public BulkWorker(BulkStore store, TorrentClientService client, TorrentProperties properties) {
        this.store = store; this.client = client; this.properties = properties;
    }

    public void recover() {
        synchronized (store) { store.recover(); ready = true; }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        synchronized (store) {
            if (closing || coordinator != null) return;
            recover();
            coordinator = Thread.ofVirtual().name("torrent-bulk-coordinator").start(this::coordinate);
        }
    }

    private void coordinate() {
        synchronized (store) {
            while (!closing) {
                long delay = advance();
                try {
                    // Libera el mismo monitor utilizado por las notificaciones: no se pierden
                    // finalizaciones entre la comprobación de futuros y el inicio de la espera.
                    TimeUnit.NANOSECONDS.timedWait(store, Math.max(1, delay));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void tick() {
        synchronized (store) { advance(); }
    }

    /** Called under the store monitor by maintenance; includes completed futures not yet persisted. */
    public boolean hasInFlightSends() {
        synchronized (store) { return !inFlight.isEmpty(); }
    }

    /** Clear cached selection only when no asynchronous send can write later. */
    public void clearIdleState() {
        synchronized (store) {
            if (!inFlight.isEmpty()) throw new IllegalStateException("Hay envíos en curso");
            buffer.clear(); activeId = null; submissionContext = null;
            activeSelectedItems = 0; activeProcessedItems = 0; lastProgressCheckpoint = 0;
            nextDispatch = 0;
            store.notifyAll();
        }
    }

    private long advance() {
        if (!ready || closing) return IDLE_WAIT;
        try { return dispatch(); }
        catch (RuntimeException ex) {
            // No registrar snapshots, credenciales ni respuestas HTTP remotas.
            log.error("No se pudo actualizar la cola torrent: jobId={}, tipo={}", activeId, ex.getClass().getSimpleName());
            return TimeUnit.SECONDS.toNanos(1);
        }
    }

    private long dispatch() {
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().future().isDone()) continue;
            Outcome outcome;
            try { outcome = entry.getValue().future().get(); }
            catch (Exception ex) { outcome = new Outcome(BulkItem.State.FAILED, "Ejecución interrumpida; revisar antes de reanudar", true, false); }
            store.finish(entry.getKey(), outcome.state(), outcome.message(), outcome.pause(), outcome.retry());
            recordProgress(outcome);
            log.trace("Resultado bulk: jobId={}, itemId={}, estado={}, pausa={}, reintento={}",
                    activeId, entry.getKey(), outcome.state(), outcome.pause(), outcome.retry());
            if (outcome.state() == BulkItem.State.FAILED || outcome.pause() || outcome.retry()) {
                var item = entry.getValue().item();
                log.warn("Fallo de envío bulk: jobId={}, itemId={}, eplId={}, hash={}, intento={}, motivo={}",
                        activeId, item.getId(), item.getEplId(), item.getHash(), item.getAttempts(),
                        safeLog(outcome.message()));
            }
            if (outcome.pause() || outcome.retry()) log.warn("Trabajo bulk interrumpido: jobId={}, reintento={}, motivo={}",
                    activeId, outcome.retry(), safeLog(outcome.message()));
            iterator.remove();
        }
        if (!properties.isEnabled() && activeId == null) return IDLE_WAIT;
        BulkJob job = activeId == null ? null : store.job(activeId);
        if (job != null && job.getState() != BulkJob.State.RUNNING) {
            buffer.clear();
            if (!inFlight.isEmpty()) return IDLE_WAIT;
            var summary = store.view(activeId);
            log.info("Trabajo bulk sin envíos en curso: jobId={}, estado={}, accepted={}, alreadyExists={}, skipped={}, failed={}, pending={}, cancelled={}",
                    activeId, job.getState(), summary.accepted(), summary.alreadyExists(), summary.skipped(),
                    summary.failed(), summary.pending(), summary.cancelled());
            activeId = null; job = null; submissionContext = null;
        }
        if (job == null) {
            if (!properties.isEnabled()) return IDLE_WAIT;
            job = store.next();
            if (job == null) return IDLE_WAIT;
            activeId = job.getId(); buffer.clear();
            activeSettings = settings == null ? null : settings.snapshot();
            submissionContext = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
            var progress = store.view(activeId);
            activeSelectedItems = progress.selectedItems();
            activeProcessedItems = progress.processedItems();
            int progressPercent = progressPercent();
            lastProgressCheckpoint = progressPercent / 10 * 10;
            log.info("Procesando trabajo bulk: jobId={}, concurrency={}, interval={}ms", activeId, job.getConcurrency(), job.getIntervalMillis());
            logProgress(progress, progressPercent);
        }
        if (inFlight.size() >= job.getConcurrency()) return IDLE_WAIT;
        long remaining = nextDispatch - System.nanoTime();
        if (remaining > 0) return Math.min(remaining, IDLE_WAIT);
        if (buffer.isEmpty()) buffer.addAll(store.pending(job.getId(), job.getBatchSize()));
        if (buffer.isEmpty()) { store.completeIfEmpty(job.getId()); return IDLE_WAIT; }
        while (!buffer.isEmpty() && inFlight.size() < job.getConcurrency() && System.nanoTime() >= nextDispatch) {
            var item = store.claim(job.getId(), buffer.removeFirst());
            if (item == null) { buffer.clear(); return IDLE_WAIT; }
            log.trace("Envío bulk: jobId={}, itemId={}, eplId={}, hash={}, intento={}",
                    job.getId(), item.getId(), item.getEplId(), item.getHash(), item.getAttempts());
            var context = submissionContext;
            var future = new CompletableFuture<Outcome>();
            inFlight.put(item.getId(), new PendingSend(item, future));
            var runSettings = activeSettings;
            executor.execute(() -> {
                try (var scope = settings == null ? null : settings.pin(runSettings)) { future.complete(send(item, context)); }
                catch (Throwable ex) { future.completeExceptionally(ex); }
                finally {
                    synchronized (store) { store.notifyAll(); }
                }
            });
            nextDispatch = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(job.getIntervalMillis());
        }
        return inFlight.size() >= job.getConcurrency() ? IDLE_WAIT
                : Math.min(IDLE_WAIT, Math.max(1, nextDispatch - System.nanoTime()));
    }

    private void recordProgress(Outcome outcome) {
        if (outcome.state() == null || outcome.pause() || outcome.retry()) return;
        activeProcessedItems++;
        if (activeSelectedItems == 0) return;
        int progressPercent = progressPercent();
        int checkpointPercent = progressPercent / 10 * 10;
        if (checkpointPercent <= lastProgressCheckpoint) return;
        lastProgressCheckpoint = checkpointPercent;
        logProgress(store.view(activeId), progressPercent);
    }

    private int progressPercent() {
        return activeSelectedItems == 0 ? 100
                : (int) (activeProcessedItems * 100.0 / activeSelectedItems);
    }

    private void logProgress(BulkStore.View progress, int progressPercent) {
        log.info("Progreso bulk: jobId={}, progreso={}%, processedItems={}/{}, accepted={}, alreadyExists={}, skipped={}, failed={}, pending={}, inFlight={}",
                activeId, progressPercent, progress.processedItems(), progress.selectedItems(), progress.accepted(),
                progress.alreadyExists(), progress.skipped(), progress.failed(), progress.pending(), progress.inFlight());
    }

    private Outcome send(BulkItem item, com.rlibanez.eplsync.torrent.TorrentSubmissionContext context) {
        try {
            // addTorrent consulta el hash antes de escribir, también al recuperar un envío incierto.
            var result = client.addTorrent(store.command(item), context);
            return new Outcome(result == TorrentDownloadResult.Status.ACCEPTED
                    ? BulkItem.State.ACCEPTED : BulkItem.State.ALREADY_EXISTS, null, false, false);
        } catch (TorrentConnectionException ex) {
            boolean retry = ex.getReason() == TorrentConnectionException.Reason.UPSTREAM
                    || ex.getReason() == TorrentConnectionException.Reason.TIMEOUT;
            return new Outcome(null, ex.getMessage(), !retry, retry);
        } catch (TorrentOperationException ex) {
            boolean pause = ex.getStatus().value() == 503;
            return new Outcome(BulkItem.State.FAILED, BulkStore.safeMessage(ex), pause, false);
        } catch (IllegalArgumentException ex) {
            return new Outcome(BulkItem.State.FAILED, "Opciones del torrent inválidas", false, false);
        } catch (RuntimeException ex) {
            return new Outcome(null, "Error inesperado; revisar antes de reanudar", true, false);
        }
    }

    private static String safeLog(String message) {
        if (message == null) return "Sin detalle";
        String clean = message.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        return clean.substring(0, Math.min(500, clean.length()));
    }

    @PreDestroy
    public void close() {
        synchronized (store) { closing = true; store.notifyAll(); }
        executor.shutdownNow();
        // Los IN_FLIGHT persistidos se reconcilian por hash en el siguiente arranque.
    }
}
