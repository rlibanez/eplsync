package com.rlibanez.eplsync.events;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventJournal {
    public enum Category { CATALOG, JOB, COVERS, TORRENT }
    public enum Outcome { STARTED, SUCCEEDED, PARTIAL, FAILED, PAUSED, RETRY_WAIT, RESUMED, CANCELLED, RECOVERED }
    public record Entry(long id, Instant createdAt, Category category, String action, Outcome outcome,
                        EventContext.Origin origin, String operationId, Map<String, Object> details) {}
    public record Filter(Category category, Outcome outcome, EventContext.Origin origin, Instant from, Instant before) {
        public Filter(Category category, Outcome outcome, Instant from, Instant before) {
            this(category, outcome, null, from, before);
        }
        public Filter {
            if (from != null && before != null && !from.isBefore(before))
                throw new IllegalArgumentException("from debe ser anterior a before (límite exclusivo)");
        }
    }
    public record Page(List<Entry> items, long total, int page, int size, long cursor) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventSettings settings;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static class Run {
        final Category category; final String action, id; final Map<String, ?> context; boolean recorded;
        Run(Category category, String action, String id, Map<String, ?> context) { this.category = category; this.action = action; this.id = id; this.context = context; }
    }
    private final ThreadLocal<Run> capturing = new ThreadLocal<>();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("event-retention").factory());
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventJournal.class);

    public EventJournal(JdbcTemplate jdbc, PlatformTransactionManager manager, EventSettings settings) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.settings = settings;
    }

    @jakarta.annotation.PostConstruct
    void initialize() {
        // AUTOINCREMENT keeps SSE cursors monotonic even when all events have been deleted.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS app_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              created_at INTEGER NOT NULL, category TEXT NOT NULL, action TEXT NOT NULL,
              outcome TEXT NOT NULL, origin TEXT NOT NULL, operation_id TEXT NOT NULL, details TEXT NOT NULL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_date ON app_events(created_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_category_id ON app_events(category, id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_operation ON app_events(operation_id)");
        maintenance.scheduleWithFixedDelay(() -> {
            try { prune(); } catch (Exception ex) { log.warn("No se pudo limpiar el registro de eventos", ex); }
        }, 1, 60, TimeUnit.SECONDS);
    }

    public EventSettings.Retention retention() { return settings.getRetention(); }
    public AutoCloseable listen(Runnable listener) {
        listeners.add(listener); return () -> listeners.remove(listener);
    }
    private final java.util.concurrent.atomic.AtomicLong resetVersion = new java.util.concurrent.atomic.AtomicLong();
    public long resetVersion() { return resetVersion.get(); }
    public int clearForReset() {
        return transactions.execute(tx -> {
            int count = jdbc.update("DELETE FROM app_events");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { resetVersion.incrementAndGet(); signal(); }
            });
            return count;
        });
    }
    private void signal() {
        for (var listener : listeners) try { listener.run(); } catch (RuntimeException ex) { log.debug("Suscriptor de eventos desconectado", ex); }
    }

    public Entry record(Category category, String action, Outcome outcome, EventContext.Origin origin,
                        String operationId, Map<String, ?> details) {
        return recordAt(category, action, outcome, origin, operationId, details, Instant.now());
    }

    private Entry recordAt(Category category, String action, Outcome outcome, EventContext.Origin origin,
                           String operationId, Map<String, ?> details, Instant timestamp) {
        return transactions.execute(tx -> {
            long now = timestamp.toEpochMilli();
            var json = mapper.writeValueAsString(details);
            if (json.length() > 16384) throw new IllegalArgumentException("Resumen del evento demasiado grande");
            long id = jdbc.queryForObject("""
                INSERT INTO app_events(created_at, category, action, outcome, origin, operation_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, now, category.name(), action, outcome.name(), origin.name(), operationId, json);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { signal(); }
            });
            return new Entry(id, Instant.ofEpochMilli(now), category, action, outcome, origin, operationId, new LinkedHashMap<>(details));
        });
    }

    private String failureReason(RuntimeException ex) {
        // Only expose messages from exceptions explicitly designed for API consumers.
        return ex instanceof com.rlibanez.eplsync.exception.TorrentOperationException
                ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    public void rejected(Category category, String action, Map<String, ?> context, Instant startedAt, RuntimeException ex) {
        String operationId = UUID.randomUUID().toString();
        var details = new LinkedHashMap<String, Object>(context);
        details.put("reason", failureReason(ex));
        // Persist both timestamps atomically; successful submissions are recorded by their job.
        transactions.executeWithoutResult(tx -> {
            recordAt(category, action, Outcome.STARTED, EventContext.origin(), operationId, context, startedAt);
            record(category, action, Outcome.FAILED, EventContext.origin(), operationId, details);
        });
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes request && request.getResponse() != null)
            request.getResponse().setHeader("X-EPLSync-Operation-Id", operationId);
    }

    /** Wrap high-level operations, suppressing nested imports during a full reset. */
    public <T> T run(Category category, String action, Supplier<T> work, Function<T, Map<String, ?>> summary) {
        return run(category, action, Map.of(), work, summary);
    }
    public <T> T run(Category category, String action, Map<String, ?> context, Supplier<T> work, Function<T, Map<String, ?>> summary) {
        if (capturing.get() != null) return work.get();
        String operationId = UUID.randomUUID().toString();
        record(category, action, Outcome.STARTED, EventContext.origin(), operationId, context);
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes request && request.getResponse() != null)
            request.getResponse().setHeader("X-EPLSync-Operation-Id", operationId);
        capturing.set(new Run(category, action, operationId, context));
        try {
            T result = work.get();
            var details = new LinkedHashMap<String, Object>(context);
            details.putAll(summary.apply(result));
            var outcome = details.get("errors") instanceof Number n && n.longValue() > 0 ? Outcome.PARTIAL : Outcome.SUCCEEDED;
            if (!capturing.get().recorded) record(category, action, outcome, EventContext.origin(), operationId, details);
            return result;
        } catch (RuntimeException ex) {
            try {
                var failure = new LinkedHashMap<String, Object>(context);
                failure.put("reason", failureReason(ex));
                record(category, action, Outcome.FAILED, EventContext.origin(), operationId, failure);
            }
            catch (RuntimeException recording) { log.error("No se pudo registrar el fallo de {}", operationId, recording); }
            throw ex;
        } finally { capturing.remove(); }
    }

    /** Called inside the business transaction when an atomic result/event commit is possible. */
    public void completed(Category category, String action, Map<String, ?> summary) {
        var run = capturing.get();
        if (run == null || run.category != category || !run.action.equals(action) || run.recorded) return;
        var details = new LinkedHashMap<String, Object>(run.context);
        details.putAll(summary);
        record(category, action, summary.get("errors") instanceof Number n && n.longValue() > 0 ? Outcome.PARTIAL : Outcome.SUCCEEDED,
                EventContext.origin(), run.id, details);
        run.recorded = true;
    }

    Entry row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        @SuppressWarnings("unchecked") Map<String, Object> details = mapper.readValue(rs.getString("details"), Map.class);
        return new Entry(rs.getLong("id"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Category.valueOf(rs.getString("category")), rs.getString("action"), Outcome.valueOf(rs.getString("outcome")),
                EventContext.Origin.valueOf(rs.getString("origin")), rs.getString("operation_id"), details);
    }
    public long cursor() {
        var value = jdbc.queryForObject("SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name='app_events'),0)", Long.class);
        return value == null ? 0 : value;
    }
    public record Unread(long count, long cursor) {}
    public Unread unread(long afterId) {
        if (afterId < 0) throw new IllegalArgumentException("afterId debe ser >= 0");
        return transactions.execute(tx -> {
            long latest = cursor();
            long effective = afterId > latest ? 0 : afterId;
            long count = jdbc.queryForObject("SELECT COUNT(DISTINCT operation_id) FROM app_events WHERE id > ?", Long.class, effective);
            return new Unread(count, latest);
        });
    }
    public List<Entry> after(long cursor, int limit) {
        return jdbc.query("SELECT * FROM app_events WHERE id > ? ORDER BY id LIMIT ?", this::row, cursor, limit);
    }
    private String where(Filter filter, List<Object> args) {
        String sql = " WHERE 1=1";
        if (filter.category() != null) { sql += " AND category=?"; args.add(filter.category().name()); }
        if (filter.origin() != null) { sql += " AND origin=?"; args.add(filter.origin().name()); }
        if (filter.outcome() != null) { sql += " AND outcome=?"; args.add(filter.outcome().name()); }
        if (filter.from() != null) { sql += " AND created_at>=?"; args.add(filter.from().toEpochMilli()); }
        if (filter.before() != null) { sql += " AND created_at<?"; args.add(filter.before().toEpochMilli()); }
        return sql;
    }
    public Page search(Filter filter, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("page >= 0 y size entre 1 y 200");
        return transactions.execute(tx -> {
            var args = new ArrayList<Object>(); var where = where(filter, args);
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_events" + where, Long.class, args.toArray());
            args.add(size); args.add((long) page * size);
            var rows = jdbc.query("SELECT * FROM app_events" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", this::row, args.toArray());
            return new Page(rows, total, page, size, cursor());
        });
    }
    public long delete(Filter filter) {
        // Freeze the upper ID: events created while maintenance runs are never inadvertently removed.
        long until = cursor(), deleted = 0;
        int batch;
        do {
            batch = transactions.execute(tx -> {
                var args = new ArrayList<Object>(); var where = where(filter, args);
                args.add(until);
                return jdbc.update("DELETE FROM app_events WHERE id IN (SELECT id FROM app_events" + where
                    + " AND id<=? ORDER BY id LIMIT 500)", args.toArray());
            });
            deleted += batch;
        } while (batch == 500);
        signal();
        return deleted;
    }
    public void prune() {
        long cutoff = Instant.now().minus(java.time.Duration.ofDays(settings.getRetention().getMaxAgeDays())).toEpochMilli();
        int batch;
        long removed = 0;
        do {
            batch = transactions.execute(tx -> {
                return jdbc.update("""
                    DELETE FROM app_events WHERE id IN (
                      SELECT id FROM app_events WHERE created_at < ? OR id < COALESCE(
                        (SELECT id FROM app_events ORDER BY id DESC LIMIT 1 OFFSET ?), 0)
                      ORDER BY id LIMIT 500)
                    """, cutoff, settings.getRetention().getMaxCount() - 1);
            });
            removed += batch;
        } while (batch == 500);
        if (removed > 0) signal();
    }
    @jakarta.annotation.PreDestroy void close() { maintenance.shutdownNow(); }
}
