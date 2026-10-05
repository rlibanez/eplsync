package com.rlibanez.eplsync.events;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('EVENTS_MANAGE')")
@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventJournal journal;
    private final EventStream stream;
    private final EventOperations operations;
    public EventController(EventJournal journal, EventStream stream, EventOperations operations) {
        this.journal = journal; this.stream = stream; this.operations = operations;
    }
    @GetMapping("/operations")
    public EventOperations.Page operations(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(required=false) Long snapshot,
            @RequestParam(required=false) String action,
            @RequestParam(required=false) EventJournal.Category category,
            @RequestParam(required=false) EventJournal.Outcome outcome,
            @RequestParam(required=false) EventContext.Origin origin,
            @RequestParam(required=false) String operationId,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant before) {
        return operations.search(new EventJournal.Filter(category, outcome, origin, from, before, action), page, size, snapshot, operationId);
    }
    @GetMapping
    public EventJournal.Page search(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String action,
            @RequestParam(required=false) EventJournal.Category category, @RequestParam(required=false) EventJournal.Outcome outcome,
            @RequestParam(required=false) EventContext.Origin origin,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant before) {
        return journal.search(new EventJournal.Filter(category, outcome, origin, from, before, action), page, size);
    }
    public record UnreadRequest(long afterId, java.util.List<Long> readIds) {}
    /** Read-only query with browser-local read markers; no user state is stored on the server. */
    @PostMapping("/unread")
    public EventJournal.Unread unread(@RequestBody UnreadRequest input) {
        return journal.unread(input.afterId(), input.readIds());
    }
    @GetMapping("/unread")
    public EventJournal.Unread unread(@RequestParam(defaultValue="0") long afterId) { return journal.unread(afterId); }
    @GetMapping("/retention") public EventSettings.Retention retention() { return journal.retention(); }
    public record DeleteRequest(Boolean confirm, Instant from, Instant before) {}
    @PostMapping("/delete")
    public Map<String, Long> delete(@RequestBody DeleteRequest request) {
        if (!Boolean.TRUE.equals(request.confirm())) throw new IllegalArgumentException("confirm=true es obligatorio");
        return Map.of("deleted", journal.delete(new EventJournal.Filter(null, null, request.from(), request.before())));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<?> capacity(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(
            Map.of("status", ex.getStatusCode().value(), "message", "Canal de eventos no disponible",
                "details", ex.getReason() == null ? "" : ex.getReason()));
    }
    @GetMapping(value="/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value="Last-Event-ID", required=false) Long cursor,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return stream.connect(cursor);
    }
}
