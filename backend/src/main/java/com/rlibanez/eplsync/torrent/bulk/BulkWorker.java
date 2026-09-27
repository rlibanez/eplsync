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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** Un coordinador por instancia, con red concurrente y escrituras de estado serializadas. */
@Component
@ConditionalOnProperty(prefix = "eplsync.torrent.bulk", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BulkWorker {
    private static final Logger log = LoggerFactory.getLogger(BulkWorker.class);
    private final BulkStore store;
    private final TorrentClientService client;
    private final TorrentProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Future<Outcome>> inFlight = new HashMap<>();
    private final Deque<String> buffer = new ArrayDeque<>();
    private String activeId;
    private com.rlibanez.eplsync.torrent.TorrentSubmissionContext submissionContext;
    private long nextDispatch;
    private boolean ready;
    private boolean closing;

    record Outcome(BulkItem.State state, String message, boolean pause, boolean retry) {}

    public BulkWorker(BulkStore store, TorrentClientService client, TorrentProperties properties) {
        this.store = store; this.client = client; this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        synchronized (store) { store.recover(); ready = true; }
    }

    @Scheduled(fixedDelay = 100)
    public void tick() {
        synchronized (store) {
            if (!ready || closing) return;
            try { dispatch(); }
            catch (RuntimeException ex) {
                // No registrar snapshots, credenciales ni respuestas HTTP remotas.
                log.error("No se pudo actualizar la cola torrent ({})", ex.getClass().getSimpleName());
            }
        }
    }

    private void dispatch() {
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().isDone()) continue;
            Outcome outcome;
            try { outcome = entry.getValue().get(); }
            catch (Exception ex) { outcome = new Outcome(BulkItem.State.FAILED, "Ejecución interrumpida; revisar antes de reanudar", true, false); }
            store.finish(entry.getKey(), outcome.state(), outcome.message(), outcome.pause(), outcome.retry());
            log.debug("Resultado bulk: jobId={}, itemId={}, estado={}, pausa={}, reintento={}",
                    activeId, entry.getKey(), outcome.state(), outcome.pause(), outcome.retry());
            if (outcome.pause() || outcome.retry()) log.warn("Trabajo bulk interrumpido: jobId={}, reintento={}, motivo={}",
                    activeId, outcome.retry(), outcome.message());
            iterator.remove();
        }
        if (!properties.isEnabled()) return;
        BulkJob job = activeId == null ? null : store.job(activeId);
        if (job != null && job.getState() != BulkJob.State.RUNNING) {
            buffer.clear();
            if (!inFlight.isEmpty()) return;
            log.info("Trabajo bulk sin envíos en curso: jobId={}, estado={}", activeId, job.getState());
            activeId = null; job = null; submissionContext = null;
        }
        if (job == null) {
            job = store.next();
            if (job == null) return;
            activeId = job.getId(); buffer.clear();
            submissionContext = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
            log.info("Procesando trabajo bulk: jobId={}, concurrency={}, interval={}ms", activeId, job.getConcurrency(), job.getIntervalMillis());
        }
        if (inFlight.size() >= job.getConcurrency() || System.nanoTime() < nextDispatch) return;
        if (buffer.isEmpty()) buffer.addAll(store.pending(job.getId(), job.getBatchSize()));
        if (buffer.isEmpty()) { store.completeIfEmpty(job.getId()); return; }
        while (!buffer.isEmpty() && inFlight.size() < job.getConcurrency() && System.nanoTime() >= nextDispatch) {
            var item = store.claim(job.getId(), buffer.removeFirst());
            if (item == null) { buffer.clear(); return; }
            log.debug("Envío bulk: jobId={}, itemId={}, eplId={}, hash={}, intento={}",
                    job.getId(), item.getId(), item.getEplId(), item.getHash(), item.getAttempts());
            var context = submissionContext;
            inFlight.put(item.getId(), executor.submit(() -> send(item, context)));
            nextDispatch = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(job.getIntervalMillis());
        }
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

    @PreDestroy
    public void close() {
        synchronized (store) { closing = true; }
        executor.shutdownNow();
        // Los IN_FLIGHT persistidos se reconcilian por hash en el siguiente arranque.
    }
}
