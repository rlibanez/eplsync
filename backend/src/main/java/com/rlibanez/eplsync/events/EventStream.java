package com.rlibanez.eplsync.events;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class EventStream {
    private final EventJournal journal;
    private final java.util.Set<SseEmitter> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(32);
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    public EventStream(EventJournal journal) { this.journal = journal; }
    public SseEmitter connect(Long after) {
        if (after != null && after < 0) throw new IllegalArgumentException("Last-Event-ID debe ser positivo");
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demasiadas conexiones de eventos");
        var emitter = new SseEmitter(30 * 60 * 1000L);
        connections.add(emitter);
        var signals = new ArrayBlockingQueue<Boolean>(1);
        var closed = new AtomicBoolean();
        emitter.onCompletion(() -> { closed.set(true); signals.offer(true); });
        emitter.onTimeout(() -> { closed.set(true); signals.offer(true); emitter.complete(); });
        emitter.onError(ex -> { closed.set(true); signals.offer(true); });
        // Subscribe before taking the snapshot, so events cannot slip between replay and live delivery.
        long initialResetVersion = journal.resetVersion();
        var listener = journal.listen(() -> signals.offer(true));
        try { writers.submit(() -> {
            try (listener) {
                long resetVersion = initialResetVersion;
                long latest = journal.cursor();
                long cursor = after == null ? latest : Math.min(after, latest);
                emitter.send(SseEmitter.event().name(after != null && after > latest ? "reset" : "ready")
                        .id(Long.toString(cursor)).data(Map.of("cursor", cursor)));
                while (!closed.get()) {
                    if (resetVersion != journal.resetVersion()) {
                        resetVersion = journal.resetVersion();
                        emitter.send(SseEmitter.event().name("database-reset").data(Map.of("cursor", journal.cursor())));
                    }
                    var rows = journal.after(cursor, 100);
                    for (var row : rows) {
                        emitter.send(SseEmitter.event().name("event").id(Long.toString(row.id())).data(row));
                        cursor = row.id();
                    }
                    if (rows.size() == 100) continue;
                    // Heartbeats keep proxies alive; no database polling when there are no new events.
                    while (!closed.get() && signals.poll(15, TimeUnit.SECONDS) == null)
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    if (!closed.get()) emitter.send(SseEmitter.event().name("refresh").data(Map.of("cursor", cursor)));
                }
            } catch (java.io.IOException disconnected) {
                // The servlet container completes failed writes; do not write/flush again.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); emitter.complete();
            } catch (Exception ex) { emitter.completeWithError(ex); }
            finally { connections.remove(emitter); slots.release(); }
        }); } catch (RejectedExecutionException ex) {
            try { listener.close(); } catch (Exception ignored) { }
            connections.remove(emitter); slots.release(); emitter.complete();
        }
        return emitter;
    }
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void onContextClosed() { close(); }
    @jakarta.annotation.PreDestroy void close() { connections.forEach(SseEmitter::complete); writers.shutdownNow(); }
}
