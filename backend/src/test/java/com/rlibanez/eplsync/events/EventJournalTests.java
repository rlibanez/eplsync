package com.rlibanez.eplsync.events;

import java.util.Objects;

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

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=validate", "eplsync.torrent.enabled=false",
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
    @Test void recordsAuthenticatedIdentityAndKeepsTheHistoricalUsername() {
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var account = new com.rlibanez.eplsync.security.Account("user-id", "alice", "private@example.org", "USER", "ACTIVE", false, null, 1, java.util.Set.of(), Instant.now());
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account, null, java.util.List.of()));
            var event = record();
            assertThat(event.actor()).isEqualTo(new EventContext.Actor("user-id", "alice", "USER"));
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            var historical = journal.search(all, 0, 20).items().getFirst();
            assertThat(historical.actor()).isEqualTo(event.actor());
            assertThat(historical.actor().toString()).doesNotContain("private@example.org");
        } finally { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous); }
    }

    @Test void preservesIdentityInBackgroundAndRestoresThreadContextAfterFailure() throws Exception {
        var actor = new EventContext.Actor("creator", "original-name", "USER");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var event = executor.submit(() -> EventContext.withActor(actor, this::record)).get();
            assertThat(event.actor()).isEqualTo(actor);
            executor.submit(() -> {
                assertThatThrownBy(() -> EventContext.withActor(actor, () -> { throw new IllegalStateException(); }))
                    .isInstanceOf(IllegalStateException.class);
                assertThat(EventContext.actor()).isEqualTo(EventContext.Actor.system());
            }).get();
        }
    }

    @Test void filtersHistoricalUsernamesAndDatesPointInTimeAudits() {
        var actor = new EventContext.Actor("id", "Robert", "USER");
        var event = journal.recordAs(actor, Category.SECURITY, "USER_UPDATE", Outcome.SUCCEEDED,
            EventContext.Origin.MANUAL, "audit", Map.of());
        var filter = new Filter(null, null, null, null, null, null, " ROB ");
        assertThat(journal.search(filter, 0, 20).items()).hasSize(1);
        var operation = operations.search(filter, 0, 20, null).items().getFirst();
        assertThat(operation.startedAt()).isEqualTo(event.createdAt());
        assertThat(operation.finishedAt()).isEqualTo(event.createdAt());
        assertThat(operation.durationMs()).isNull();
        assertThat(operations.search(new Filter(null, null, null, null, null, null, "other"), 0, 20, null).total()).isZero();
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reader", null,
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("EVENTS_MANAGE"))));
            assertThat(journal.search(filter, 0, 20).total()).isZero();
            assertThat(operations.search(filter, 0, 20, null).total()).isZero();
        } finally { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous); }
    }

    @Test void keepsInitiatorWhenOperationClearsAuthentication() {
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var actor = EventContext.actor();
        try {
            journal.run(Category.CATALOG, "RESET", () -> {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                return true;
            }, ignored -> Map.of());
            assertThat(journal.search(all, 0, 20).items()).hasSize(2)
                .allSatisfy(event -> assertThat(event.actor()).isEqualTo(actor));
        } finally { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous); }
    }

    @Test void marksSystemOperationsAndLegacyEventsWithoutInventingAnOwner() {
        var system = journal.record(Category.CATALOG, "UPDATE", Outcome.SUCCEEDED, EventContext.Origin.SYSTEM,
            "system-operation", Map.of());
        assertThat(system.actor()).isEqualTo(EventContext.Actor.system());
        jdbc.update("UPDATE app_events SET actor_id=NULL,actor_username=NULL,actor_kind=NULL WHERE id=?", system.id());
        assertThat(journal.search(all, 0, 20).items().getFirst().actor()).isEqualTo(EventContext.Actor.unknown());
    }

    @Test void groupedOperationsApplyMultipleCriteriaBeforePagination() {
        at("short",Outcome.STARTED,"16:00:00");at("short",Outcome.SUCCEEDED,"16:01:00");
        at("long",Outcome.STARTED,"16:02:00");at("long",Outcome.SUCCEEDED,"16:05:00");
        var page=operations.search(all,0,1,null,null,"outcome,asc;duration,desc");
        assertThat(page.items().getFirst().latest().operationId()).isEqualTo("long");
        assertThat(operations.search(all,1,1,page.cursor(),null,"outcome,asc;duration,desc").items().getFirst().latest().operationId()).isEqualTo("short");
        assertThatThrownBy(() -> operations.search(all,0,20,null,null,"duration,asc;duration,desc"))
            .isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
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
        assertThat(current.items()).extracting(value -> Objects.requireNonNull(value).durationMs()).containsExactly(120000L, 960000L, 1140000L, 75000L);
        assertThat(current.items().get(0).events()).extracting(value -> Objects.requireNonNull(value).outcome()).containsExactly(Outcome.STARTED, Outcome.SUCCEEDED);
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
        var subscription = journal.listen(signals::incrementAndGet);
        try (subscription) {
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
        assertThat(rows).extracting(value -> Objects.requireNonNull(value).outcome()).containsExactly(Outcome.FAILED, Outcome.STARTED);
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
        assertThat(page.items()).extracting(value -> Objects.requireNonNull(value).id()).containsExactly(second.id());
        assertThat(journal.after(first.id(), 10)).hasSize(2);
        assertThatThrownBy(() -> journal.search(all, 0, 10001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retentionAppliesBothLimitsAndDeletionUsesExclusiveEnd() {
        var old = record(); var middle = record(); var recent = record();
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", Instant.now().minusSeconds(366L * 86400).toEpochMilli(), old.id());
        settings.getRetention().setMaxCount(2);
        journal.prune();
        assertThat(journal.after(0, 10)).extracting(value -> Objects.requireNonNull(value).id()).containsExactly(middle.id(), recent.id());
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", 1000, middle.id());
        jdbc.update("UPDATE app_events SET created_at=? WHERE id=?", 2000, recent.id());
        assertThat(journal.delete(new Filter(null, null, Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000)))).isEqualTo(1);
        assertThat(journal.after(0, 10)).extracting(value -> Objects.requireNonNull(value).id()).containsExactly(recent.id());
        journal.prune();
        assertThat(journal.search(all, 0, 20).total()).isZero();
    }

    @Test void catalogRejectionsExposeSafeReasonsButNeverArbitraryExceptionMessages() {
        var safe = new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera el máximo de 128 MiB");
        assertThatThrownBy(() -> journal.run(Category.CATALOG,"UPDATE", () -> { throw safe; }, result -> Map.of()))
            .isSameAs(safe);
        assertThat(journal.search(all,0,20).items().getFirst().details().get("reason")).isEqualTo(safe.getMessage());
        var internal = new IllegalArgumentException("SQL token=private-secret");
        assertThatThrownBy(() -> journal.run(Category.CATALOG,"UPDATE", () -> { throw internal; }, result -> Map.of()))
            .isSameAs(internal);
        assertThat(journal.search(all,0,20).items().getFirst().details().get("reason"))
            .isEqualTo("Ha ocurrido un error inesperado");
    }
}
