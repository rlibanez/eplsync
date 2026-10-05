package com.rlibanez.eplsync.events;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Groups retained events without changing or duplicating the journal. */
@Service
public class EventOperations {
    public record Operation(EventJournal.Entry latest, Instant startedAt, Instant finishedAt,
                            Instant firstRecordedAt, Long durationMs, List<EventJournal.Entry> events) {}
    public record Page(List<Operation> items, long total, int page, int size, long cursor) {}
    private final EventJournal journal;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public EventOperations(EventJournal journal, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.journal = journal; this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
    }
    public Page search(EventJournal.Filter filter, int page, int size, Long snapshot) {
        return search(filter, page, size, snapshot, null);
    }
    public Page search(EventJournal.Filter filter, int page, int size, Long snapshot, String operationId) {
        if (page < 0 || size < 1 || size > 200 || (snapshot != null && snapshot < 0))
            throw new com.rlibanez.eplsync.exception.UserInputException("page >= 0, size entre 1 y 200 y snapshot >= 0");
        return transactions.execute(tx -> {
            long current = journal.cursor();
            long cursor = snapshot == null || snapshot > current ? current : snapshot;
            String cte = """
                WITH grouped AS (
                  SELECT operation_id, MIN(id) first_id, MAX(id) last_id,
                    COALESCE(MIN(CASE WHEN outcome='STARTED' THEN created_at END), MIN(created_at)) sort_at
                  FROM app_events WHERE id<=? GROUP BY operation_id
                ), operations AS (
                  SELECT e.*, g.first_id, g.sort_at FROM grouped g JOIN app_events e ON e.id=g.last_id
                )
                """;
            var args = new ArrayList<Object>(); args.add(cursor);
            String where = " WHERE 1=1" + EventJournal.visibilitySql();
            if (operationId != null) { where += " AND operation_id=?"; args.add(operationId); }
            if (filter.action() != null) { where += " AND action=?"; args.add(filter.action()); }
            if (filter.category() != null) { where += " AND category=?"; args.add(filter.category().name()); }
            if (filter.origin() != null) { where += " AND origin=?"; args.add(filter.origin().name()); }
            if (filter.outcome() == EventJournal.Outcome.STARTED)
                where += " AND outcome IN ('STARTED','RESUMED','RECOVERED')";
            else if (filter.outcome() != null) { where += " AND outcome=?"; args.add(filter.outcome().name()); }
            if (filter.from() != null) { where += " AND sort_at>=?"; args.add(filter.from().toEpochMilli()); }
            if (filter.before() != null) { where += " AND sort_at<?"; args.add(filter.before().toEpochMilli()); }
            long total = jdbc.queryForObject(cte + "SELECT COUNT(*) FROM operations" + where, Long.class, args.toArray());
            args.add(size); args.add((long) page * size);
            var ids = jdbc.queryForList(cte + "SELECT operation_id FROM operations" + where
                + " ORDER BY sort_at DESC, first_id DESC LIMIT ? OFFSET ?", String.class, args.toArray());
            if (ids.isEmpty()) return new Page(List.of(), total, page, size, cursor);
            var eventArgs = new ArrayList<Object>(ids); eventArgs.add(cursor);
            var entries = jdbc.query("SELECT * FROM app_events WHERE operation_id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") AND id<=? ORDER BY id", journal::row, eventArgs.toArray());
            var byId = new HashMap<String, List<EventJournal.Entry>>();
            for (var event : entries) byId.computeIfAbsent(event.operationId(), key -> new ArrayList<>()).add(event);
            var operations = ids.stream().map(id -> {
                var events = byId.get(id);
                var latest = events.getLast();
                var start = events.stream().filter(e -> e.outcome() == EventJournal.Outcome.STARTED)
                    .map(value -> Objects.requireNonNull(value).createdAt()).min(Comparator.naturalOrder()).orElse(null);
                var end = switch (latest.outcome()) {
                    case SUCCEEDED, PARTIAL, FAILED, CANCELLED -> latest.createdAt();
                    default -> null;
                };
                // Retention may have removed the beginning: never invent a start or duration.
                Long duration = start == null || end == null ? null : Math.max(0, Duration.between(start, end).toMillis());
                return new Operation(latest, start, end, events.getFirst().createdAt(), duration, events);
            }).toList();
            return new Page(operations, total, page, size, cursor);
        });
    }
}
