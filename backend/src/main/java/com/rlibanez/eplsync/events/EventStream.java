package com.rlibanez.eplsync.events;

import java.util.Objects;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class EventStream {
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.security.SessionAccess access;
    private final EventJournal journal;
    private final java.util.Set<SseEmitter> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(32);
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    public EventStream(EventJournal journal) { this.journal = journal; }
    public SseEmitter connect(Long after) {
        if (after != null && after < 0) throw new com.rlibanez.eplsync.exception.UserInputException("Last-Event-ID debe ser positivo");
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demasiadas conexiones de eventos");
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var session = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet ? servlet.getRequest().getSession(false) : null;
        var account = authentication != null && authentication.getPrincipal() instanceof com.rlibanez.eplsync.security.Account a ? a : null;
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
                    if (account != null && !streamValid(account, session)) { emitter.complete(); break; }
                    if (resetVersion != journal.resetVersion()) {
                        resetVersion = journal.resetVersion();
                        emitter.send(SseEmitter.event().name("database-reset").data(Map.of("cursor", journal.cursor())));
                    }
                    var rows = journal.after(cursor, 100);
                    for (var row : rows) {
                        if (row.category()!=EventJournal.Category.SECURITY || (account!=null && account.role().equals("ADMIN")))
                            emitter.send(SseEmitter.event().name("event").id(Long.toString(row.id())).data(row));
                        cursor = row.id();
                    }
                    if (rows.size() == 100) continue;
                    // Heartbeats keep proxies alive; no database polling when there are no new events.
                    while (!closed.get() && signals.poll(15, TimeUnit.SECONDS) == null) {
                        if (account != null && !streamValid(account, session)) { closed.set(true); emitter.complete(); break; }
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    }
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
    private boolean streamValid(com.rlibanez.eplsync.security.Account account, jakarta.servlet.http.HttpSession session) {
        try { return session != null && session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null && access.valid(account); }
        catch (IllegalStateException expired) { return false; }
    }
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void onContextClosed() { close(); }
    @jakarta.annotation.PreDestroy void close() { connections.forEach(value -> Objects.requireNonNull(value).complete()); writers.shutdownNow(); }
}
