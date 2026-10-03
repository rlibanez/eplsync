package com.rlibanez.eplsync.events;

import com.rlibanez.eplsync.model.CatalogBook;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.rlibanez.eplsync.events.EventJournal.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=create-drop", "eplsync.torrent.enabled=false",
    "eplsync.torrent.bulk.worker-enabled=false"})
class EventJournalTests {
    @Autowired EventJournal journal;
    @Autowired EventOperations operations;
    @Autowired EventSettings settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    final Filter all = new Filter(null, null, null, null);
    Entry record() { return journal.record(Category.CATALOG, "UPDATE", Outcome.SUCCEEDED,
        EventContext.origin(), java.util.UUID.randomUUID().toString(), Map.of("created", 2)); }
    @BeforeEach void clear() {
        settings.getRetention().setMaxCount(10000);
        settings.getRetention().setMaxAgeDays(365);
        journal.delete(all);
    }
    @AfterEach void restore() {
        settings.getRetention().setMaxCount(10000);
        settings.getRetention().setMaxAgeDays(365);
    }
    Entry at(String operation, Outcome outcome, String time) {
        var event = journal.record(Category.CATALOG, "UPDATE", outcome, EventContext.Origin.MANUAL, operation, Map.of());
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", Instant.parse("2026-10-02T" + time + "Z").toEpochMilli(), event.id());
        return event;
    }
    @Test void groupedOperationsSortByStartAndFreezeBothResultsAndPagination() {
        at("catalog1", Outcome.STARTED, "16:11:00");
        at("catalog1", Outcome.SUCCEEDED, "16:12:15");
        at("covers", Outcome.STARTED, "16:13:00");
        at("sync", Outcome.STARTED, "16:14:00");
        at("catalog2", Outcome.STARTED, "16:15:00");
        var snapshot = journal.cursor();
        at("catalog2", Outcome.SUCCEEDED, "16:17:00");
        at("sync", Outcome.SUCCEEDED, "16:30:00");
        at("covers", Outcome.PARTIAL, "16:32:00");
        var frozen = operations.search(all, 0, 2, snapshot);
        assertThat(frozen.total()).isEqualTo(4);
        assertThat(frozen.items()).extracting(op -> op.latest().operationId()).containsExactly("catalog2", "sync");
        assertThat(frozen.items()).allSatisfy(op -> {
            assertThat(op.latest().outcome()).isEqualTo(Outcome.STARTED);
            assertThat(op.finishedAt()).isNull();
            assertThat(op.durationMs()).isNull();
        });
        assertThat(operations.search(all, 1, 2, snapshot).items())
            .extracting(op -> op.latest().operationId()).containsExactly("covers", "catalog1");
        var current = operations.search(all, 0, 20, null);
        assertThat(current.items()).extracting(op -> op.latest().operationId()).containsExactly("catalog2", "sync", "covers", "catalog1");
        assertThat(current.items()).extracting(EventOperations.Operation::durationMs).containsExactly(120000L, 960000L, 1140000L, 75000L);
        assertThat(current.items().get(0).events()).extracting(Entry::outcome).containsExactly(Outcome.STARTED, Outcome.SUCCEEDED);
        assertThat(operations.search(new Filter(null, Outcome.STARTED, null, null), 0, 20, null).total()).isZero();
        assertThat(operations.search(new Filter(null, Outcome.PARTIAL, null, null), 0, 20, null).total()).isEqualTo(1);
        assertThat(operations.search(new Filter(null, null, Instant.parse("2026-10-02T16:15:00Z"), Instant.parse("2026-10-02T16:16:00Z")), 0, 20, null).total()).isEqualTo(1);
    }
    @Test void missingStartAndIntermediateStatesDoNotInventCompletion() {
        var start = at("job", Outcome.STARTED, "16:11:00");
        at("job", Outcome.PAUSED, "16:12:00");
        assertThat(operations.search(all, 0, 20, null).items().getFirst().finishedAt()).isNull();
        at("job", Outcome.RESUMED, "16:13:00");
        assertThat(operations.search(new Filter(null, Outcome.STARTED, null, null), 0, 20, null).total()).isEqualTo(1);
        at("job", Outcome.CANCELLED, "16:14:00");
        jdbc.update("DELETE FROM app_events WHERE id=?", start.id());
        var partial = operations.search(all, 0, 20, null).items().getFirst();
        assertThat(partial.startedAt()).isNull();
        assertThat(partial.finishedAt()).isEqualTo(Instant.parse("2026-10-02T16:14:00Z"));
        assertThat(partial.durationMs()).isNull();
        assertThat(partial.events()).hasSize(3);
        assertThat(operations.search(all, 0, 20, journal.cursor() + 100).cursor()).isEqualTo(journal.cursor());
        assertThatThrownBy(() -> operations.search(all, 0, 20, -1L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void jdbcEventsJoinJpaTransactionAndSignalOnlyAfterCommit() throws Exception {
        var signals = new AtomicInteger();
        try (var subscription = journal.listen(signals::incrementAndGet)) {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                em.persist(CatalogBook.builder().eplId(991L).title("Atomic").revision(1.0).build());
                record();
                assertThat(signals.get()).isZero();
                tx.setRollbackOnly();
            });
            assertThat(journal.search(all, 0, 20).total()).isZero();
            assertThat(em.find(CatalogBook.class, 991L)).isNull();
            assertThat(signals.get()).isZero();
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                record(); assertThat(signals.get()).isZero();
            });
            assertThat(signals.get()).isEqualTo(1);
        }
    }
    @Test void failureKeepsStartAndFailureButNotRolledBackSuccess() {
        assertThatThrownBy(() -> journal.run(Category.CATALOG, "UPDATE", () -> {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                journal.completed(Category.CATALOG, "UPDATE", Map.of("created", 1));
                throw new IllegalStateException("rollback");
            });
            return 1;
        }, result -> Map.of())).isInstanceOf(IllegalStateException.class);
        var rows = journal.search(all, 0, 20).items();
        assertThat(rows).extracting(Entry::outcome).containsExactly(Outcome.FAILED, Outcome.STARTED);
        assertThat(rows.get(0).operationId()).isEqualTo(rows.get(1).operationId());
    }
    @Test void nestedResetAndImportProduceOneOperationWithNoDuplicatedCompletion() {
        EventContext.withOrigin(EventContext.Origin.SCHEDULED, () -> journal.run(Category.CATALOG, "RESET", () -> {
            journal.run(Category.CATALOG, "UPDATE", () -> 1, value -> Map.of());
            new TransactionTemplate(manager).executeWithoutResult(tx ->
                journal.completed(Category.CATALOG, "RESET", Map.of("imported", 1)));
            return 1;
        }, value -> Map.of()));
        var rows = journal.search(all, 0, 20).items();
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.action()).isEqualTo("RESET");
            assertThat(row.origin()).isEqualTo(EventContext.Origin.SCHEDULED);
        });
        assertThat(EventContext.origin()).isEqualTo(EventContext.Origin.MANUAL);
    }
    @Test void unreadCountsOperationsRatherThanLifecycleEvents() {
        long before = journal.cursor();
        var start = at("same-operation", Outcome.STARTED, "16:11:00");
        assertThat(journal.unread(before).count()).isEqualTo(1);
        var end = at("same-operation", Outcome.SUCCEEDED, "16:12:00");
        assertThat(journal.unread(before).count()).isEqualTo(1);
        assertThat(journal.unread(start.id()).count()).isEqualTo(1);
        assertThat(journal.unread(end.id()).count()).isZero();
        at("another-operation", Outcome.STARTED, "16:13:00");
        assertThat(journal.unread(before).count()).isEqualTo(2);
        jdbc.update("DELETE FROM app_events WHERE id=?", start.id());
        assertThat(journal.unread(before).count()).isEqualTo(2);
    }
    @Test void individuallyReadOperationsDoNotHideOtherOperationsOrLaterUpdates() {
        long before = journal.cursor();
        var first = at("first", Outcome.STARTED, "16:11:00");
        var second = at("second", Outcome.STARTED, "16:12:00");
        assertThat(journal.unread(before, java.util.List.of(second.id())).count()).isEqualTo(1);
        assertThat(journal.unread(before, java.util.List.of(first.id(), second.id())).count()).isZero();
        at("second", Outcome.SUCCEEDED, "16:13:00");
        assertThat(journal.unread(before, java.util.List.of(first.id(), second.id())).count()).isEqualTo(1);
    }
    @Test void unreadCountsRowsRatherThanIdGapsAndHandlesRestoredDatabase() {
        var first = record();
        var second = record();
        record();
        jdbc.update("DELETE FROM app_events WHERE id=?", second.id());
        assertThat(journal.unread(first.id()).count()).isEqualTo(1);
        assertThat(journal.unread(journal.cursor()).count()).isZero();
        assertThat(journal.unread(journal.cursor() + 100).count()).isEqualTo(2);
        journal.delete(all);
        assertThat(journal.unread(0).count()).isZero();
        assertThatThrownBy(() -> journal.unread(-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void deletionDoesNotReuseSseIdsAndPagingFiltersAreConsistent() {
        var first = record();
        journal.delete(all);
        assertThat(journal.cursor()).isEqualTo(first.id());
        var second = record();
        assertThat(second.id()).isGreaterThan(first.id());
        record();
        var page = journal.search(new Filter(Category.CATALOG, Outcome.SUCCEEDED, null, null), 1, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(Entry::id).containsExactly(second.id());
        assertThat(journal.after(first.id(), 10)).hasSize(2);
        assertThatThrownBy(() -> journal.search(all, 0, 10001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retentionAppliesBothLimitsAndDeletionUsesExclusiveEnd() {
        var old = record(); var middle = record(); var recent = record();
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", Instant.now().minusSeconds(366L * 86400).toEpochMilli(), old.id());
        settings.getRetention().setMaxCount(2);
        journal.prune();
        assertThat(journal.after(0, 10)).extracting(Entry::id).containsExactly(middle.id(), recent.id());
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", 1000, middle.id());
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", 2000, recent.id());
        assertThat(journal.delete(new Filter(null, null, Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000)))).isEqualTo(1);
        assertThat(journal.after(0, 10)).extracting(Entry::id).containsExactly(recent.id());
        journal.prune();
        assertThat(journal.search(all, 0, 20).total()).isZero();
    }
}
