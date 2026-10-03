package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.TorrentConnectionException;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentDownload;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class BulkTests {
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired BulkStore store;
    @Autowired BulkJobRepository jobs;
    @Autowired BulkItemRepository items;
    @Autowired CatalogBookRepository books;
    @Autowired TorrentProperties properties;
    @MockitoBean TorrentClientService client;
    BulkWorker worker;

    @BeforeEach
    void setup() {
        items.deleteAll(); jobs.deleteAll(); books.deleteAll();
        properties.setEnabled(true);
        properties.getBulk().setMultipleHashes(MultipleHashes.SKIP);
        properties.setBaseUrl("http://localhost:8080");
        properties.getBulk().setBatchSize(2); properties.getBulk().setConcurrency(1);
        properties.getBulk().setInterval(Duration.ZERO);
        properties.getRename().setPattern("EPL_{eplId}_{title}");
        when(client.addTorrent(any(), any())).thenAnswer(invocation -> client.addTorrent(invocation.getArgument(0)));
        when(client.withDefaults(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        for (long id = 1; id <= 5; id++) books.save(CatalogBook.builder().eplId(id).revision(1.0)
                .title("Book " + id).author("Author").language(Language.INGLES)
                .links(String.format("%040X", id)).build());
    }
    @AfterEach void close() { if (worker != null) worker.close(); properties.setEnabled(false); }
    @Test void dryRunPreparesSameItemsWithoutPersistingOrSending() {
        var before = events.cursor();
        var preview = store.preview(new CatalogBookFilter(),PageRequest.of(0,20),false,true,null,true);
        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.applied()).isFalse();
        assertThat(preview.selectedBooks()).isEqualTo(5);
        assertThat(preview.items()).hasSize(5);
        assertThat(jobs.count()).isZero(); assertThat(items.count()).isZero();
        assertThat(events.cursor()).isEqualTo(before);
        verify(client,never()).addTorrent(any());
        var applied = create(null);
        assertThat(applied.selectedBooks()).isEqualTo(preview.selectedBooks());
        assertThat(applied.selectedItems()).isEqualTo(preview.selectedItems());
        assertThat(applied.skipped()).isEqualTo(preview.skipped());
    }
    @Test void emptySelectionDoesNotLeaveJobItemsOrEvents() {
        var filter = new CatalogBookFilter(); filter.setEplId(999L);
        var cursor = events.cursor();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.create(filter,PageRequest.of(0,20),false,false,null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No hay libros");
        assertThat(jobs.count()).isZero(); assertThat(items.count()).isZero();
        assertThat(events.cursor()).isEqualTo(cursor);
        assertThat(store.preview(filter,PageRequest.of(0,20),false,false,null,true).selectedBooks()).isZero();
    }
    @Test void oneMatchingBookCreatesAnExecutableJob() throws Exception {
        var filter = new CatalogBookFilter(); filter.setEplId(1L);
        var job = store.create(filter,PageRequest.of(0,20),false,false,null);
        assertThat(job.selectedBooks()).isEqualTo(1);
        start(); until(() -> store.view(job.jobId()).processedItems() == 1);
        verify(client,times(1)).addTorrent(any(),any());
    }
    private BulkStore.View create(BulkRequest request) {
        return store.create(new CatalogBookFilter(), PageRequest.of(0, 20), false, true, request);
    }
    private void start() { worker = new BulkWorker(store, client, properties); worker.recover(); }
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { worker.tick(); Thread.sleep(10); }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void awaitAutomatic(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (store) { if (condition.getAsBoolean()) return; }
            Thread.sleep(5);
        }
        synchronized (store) { assertThat(condition.getAsBoolean()).isTrue(); }
    }

    @Test void automaticCoordinatorHonorsGlobalIntervalAndCompletesWithoutTicks() throws Exception {
        var starts = new CopyOnWriteArrayList<Long>();
        when(client.addTorrent(any())).thenAnswer(invocation -> {
            starts.add(System.nanoTime());
            return TorrentDownloadResult.Status.ACCEPTED;
        });
        long cursor = events.cursor();
        var job = com.rlibanez.eplsync.events.EventContext.withOrigin(com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED,
            () -> create(new BulkRequest(null, 2, 4, "100ms")));
        worker = new BulkWorker(store, client, properties);
        worker.start();
        worker.start(); // El evento repetido no crea otro coordinador ni recupera envíos activos.
        awaitAutomatic(() -> store.view(job.jobId()).status() == BulkJob.State.COMPLETED);
        assertThat(starts).hasSize(5);
        var rows = events.after(cursor, 20);
        assertThat(rows).extracting(com.rlibanez.eplsync.events.EventJournal.Entry::outcome)
                .containsExactly(com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED,
                    com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        assertThat(rows).allSatisfy(row -> assertThat(row.operationId()).isEqualTo(job.jobId()));
        assertThat(rows.get(1).details()).containsEntry("accepted", 5);
        assertThat(rows).allSatisfy(row -> assertThat(row.origin()).isEqualTo(
            com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED));
        for (int i = 1; i < starts.size(); i++)
            assertThat(starts.get(i) - starts.get(i - 1)).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(90));
    }

    @Test void automaticCoordinatorOverlapsRequestsAndDrainsBeforeResume() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(client.addTorrent(any())).thenAnswer(invocation -> {
            calls.incrementAndGet(); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout de prueba");
            return TorrentDownloadResult.Status.ACCEPTED;
        });
        var job = create(new BulkRequest(null, 2, 2, "100ms"));
        worker = new BulkWorker(store, client, properties);
        worker.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            synchronized (store) { store.control(job.jobId(), "pause"); }
            release.countDown();
            awaitAutomatic(() -> store.view(job.jobId()).inFlight() == 0);
            assertThat(calls.get()).isEqualTo(2);
            synchronized (store) { store.control(job.jobId(), "resume"); }
            awaitAutomatic(() -> store.view(job.jobId()).status() == BulkJob.State.COMPLETED);
            assertThat(calls.get()).isEqualTo(5);
            worker.close();
            synchronized (store) { create(null); }
            Thread.sleep(300);
            assertThat(calls.get()).isEqualTo(5);
        } finally { release.countDown(); }
    }

    @Test void logsPerItemAtTraceAndFailuresWithContextAtWarn() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BulkWorker.class);
        var previous = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            when(client.addTorrent(any())).thenThrow(new TorrentOperationException(
                    org.springframework.http.HttpStatus.CONFLICT, "La categoría configurada no existe en qBittorrent"));
            var job = create(null); start();
            until(() -> store.view(job.jobId()).status() == BulkJob.State.COMPLETED);
            worker.tick();
            var warnings = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(e -> e.getFormattedMessage()).toList();
            assertThat(warnings).hasSize(5).allMatch(message -> message.contains("jobId=" + job.jobId())
                    && message.contains("eplId=") && message.contains("hash=")
                    && message.contains("La categoría configurada no existe en qBittorrent"));
            assertThat(appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("Envío bulk:")
                    || e.getFormattedMessage().startsWith("Resultado bulk:")))
                    .hasSize(10).allMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.TRACE);
                var progress = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                        && e.getFormattedMessage().startsWith("Progreso bulk:"))
                    .map(e -> e.getFormattedMessage()).toList();
                assertThat(progress).hasSize(6);
                assertThat(progress.getFirst()).contains("progreso=0%", "processedItems=0/5");
                assertThat(progress.getLast()).contains("progreso=100%", "processedItems=5/5", "failed=5", "pending=0");
            assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage().contains("failed=5"));
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }

    @Test void snapshotsSelectionAndOptionsWithoutRetainingEntities() {
        var request = new BulkRequest(new TorrentDownloadRequest(null, false, "/test",
                new TorrentDownloadRequest.Rename(true, "{title}"), null), 3, 2, "250ms");
        var job = create(request);
        books.deleteAll(); properties.getRename().setPattern("changed");
        assertThat(job.selectedBooks()).isEqualTo(5);
        assertThat(job.batchSize()).isEqualTo(3); assertThat(job.concurrency()).isEqualTo(2);
        assertThat(job.interval()).isEqualTo("250ms");
        var queued = items.findByJobIdAndStateOrderByPosition(job.jobId(), BulkItem.State.PENDING, PageRequest.of(0, 10));
        assertThat(queued).hasSize(5);
        var command = store.command(queued.getFirst());
        assertThat(command.name()).isEqualTo("Book 1"); assertThat(command.start()).isFalse();
        assertThat(command.savePath()).isEqualTo("/test");
        assertThat(command.book().getLanguage()).isEqualTo(Language.INGLES);
    }

    @Test void paginationFilteringAndPerFieldDefaults() {
        var filter = new CatalogBookFilter(); filter.setLanguage(Language.INGLES);
        var job = store.create(filter, PageRequest.of(1, 2, Sort.by("eplId")), true, false,
                new BulkRequest(null, null, 2, null));
        assertThat(job.selectedBooks()).isEqualTo(2); assertThat(job.batchSize()).isEqualTo(2);
        assertThat(job.interval()).isEqualTo("0ms");
        assertThat(store.details(job.jobId(), 0, 50).items()).extracting(BulkStore.ItemView::eplId).containsExactly(3L, 4L);
    }

    @Test void rejectsUnsafeSelectionAndInvalidExecutionParameters() {
        assertThatThrownBy(() -> store.create(new CatalogBookFilter(), PageRequest.of(0, 20), false, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (var request : List.of(new BulkRequest(null, 0, null, null), new BulkRequest(null, null, 17, null),
                new BulkRequest(null, null, null, "-1s"), new BulkRequest(null, null, null, "nonsense")))
            assertThatThrownBy(() -> create(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.count()).isZero();
    }

    @Test void deduplicatesHashesAndSkipsMissingOrAmbiguousLinks() {
        var book = books.findById(2L).orElseThrow(); book.setLinks(String.format("%040X", 1)); books.save(book);
        book = books.findById(3L).orElseThrow(); book.setLinks(""); books.save(book);
        book = books.findById(4L).orElseThrow(); book.setLinks(String.format("%040X,%040X", 4, 5)); books.save(book);
        var job = create(null);
        assertThat(job.skipped()).isEqualTo(3); assertThat(job.pending()).isEqualTo(2);
    }

    @Test void workerProcessesAllItemsWithBoundedConcurrency() throws Exception {
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(client.addTorrent(any())).thenAnswer(invocation -> {
            int n = active.incrementAndGet(); maximum.accumulateAndGet(n, Math::max); entered.countDown();
            release.await(3, TimeUnit.SECONDS); active.decrementAndGet(); return TorrentDownloadResult.Status.ACCEPTED;
        });
        var job = create(new BulkRequest(null, 2, 2, "0ms")); start(); worker.tick();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(maximum.get()).isEqualTo(2);
        release.countDown();
        until(() -> store.view(job.jobId()).status() == BulkJob.State.COMPLETED);
        assertThat(store.view(job.jobId()).accepted()).isEqualTo(5); assertThat(maximum.get()).isEqualTo(2);
        verify(client, times(5)).addTorrent(any());
    }

    @Test void pauseResumeAndCancelPreventNewSubmissions() throws Exception {
        var job = create(null); store.control(job.jobId(), "pause"); start(); worker.tick();
        verify(client, never()).addTorrent(any());
        store.control(job.jobId(), "resume");
        until(() -> store.view(job.jobId()).accepted() >= 1);
        store.control(job.jobId(), "cancel");
        until(() -> store.view(job.jobId()).inFlight() == 0);
        assertThat(store.view(job.jobId()).status()).isEqualTo(BulkJob.State.CANCELLED);
        assertThat(store.view(job.jobId()).cancelled()).isGreaterThan(0);
        assertThatThrownBy(() -> store.control(job.jobId(), "resume")).isInstanceOf(TorrentOperationException.class);
    }

    @Test void recoveryRequeuesUncertainItemsAndPreservesPausedJobs() {
        var job = create(null); store.next();
        var id = store.pending(job.jobId(), 1).getFirst(); store.claim(job.jobId(), id);
        store.recover();
        assertThat(store.view(job.jobId()).status()).isEqualTo(BulkJob.State.QUEUED);
        assertThat(store.view(job.jobId()).pending()).isEqualTo(5);
        store.control(job.jobId(), "pause"); store.recover();
        assertThat(store.view(job.jobId()).status()).isEqualTo(BulkJob.State.PAUSED);
    }

    @Test void authenticationPausesWithoutLosingPendingWork() throws Exception {
        when(client.addTorrent(any())).thenThrow(new TorrentConnectionException(TorrentConnectionException.Reason.AUTHENTICATION));
        var job = create(null); start();
        until(() -> store.view(job.jobId()).status() == BulkJob.State.PAUSED);
        assertThat(store.view(job.jobId()).pending()).isEqualTo(5);
        verify(client, times(1)).addTorrent(any());
    }

    @Test void timeoutSchedulesBoundedRetryAndChecksExistingHashOnNextAttempt() throws Exception {
        when(client.addTorrent(any())).thenThrow(new TorrentConnectionException(TorrentConnectionException.Reason.TIMEOUT))
                .thenReturn(TorrentDownloadResult.Status.ALREADY_EXISTS);
        var job = create(null); start();
        until(() -> store.view(job.jobId()).status() == BulkJob.State.RETRY_WAIT);
        assertThat(store.view(job.jobId()).retryAt()).isAfter(Instant.now());
        var persisted = jobs.findById(job.jobId()).orElseThrow(); persisted.setRetryAt(Instant.now().minusSeconds(1)); jobs.save(persisted);
        until(() -> store.view(job.jobId()).status() == BulkJob.State.COMPLETED);
        assertThat(store.view(job.jobId()).alreadyExists()).isEqualTo(5);
        assertThat(store.view(job.jobId()).retryAt()).isNull();
    }

    @Test void changedDestinationRequiresRestoringOriginalClient() {
        var job = create(null); properties.setBaseUrl("http://localhost:9090");
        assertThat(store.next()).isNull();
        assertThat(store.view(job.jobId()).status()).isEqualTo(BulkJob.State.PAUSED);
        assertThatThrownBy(() -> store.control(job.jobId(), "resume")).isInstanceOf(TorrentOperationException.class);
    }

    @Test void listsCurrentAndPastJobsWithPaginationAndStatusFilters() throws Exception {
        var allStates = BulkJob.State.values();
        String newestId = null;
        for (int index = 0; index < allStates.length; index++) {
            var created = create(null);
            var job = jobs.findById(created.jobId()).orElseThrow();
            job.setState(allStates[index]);
            job.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index));
            jobs.save(job); newestId = job.getId();
        }
        var first = store.list(0, 2, null);
        assertThat(first.meta().totalItems()).isEqualTo(allStates.length);
        assertThat(first.meta().hasNext()).isTrue();
        assertThat(first.items().getFirst()).isEqualTo(store.view(newestId));
        var second = store.list(1, 2, null);
        assertThat(second.items()).extracting(BulkStore.View::jobId)
                .doesNotContainAnyElementsOf(first.items().stream().map(BulkStore.View::jobId).toList());
        assertThat(store.list(0, 20, List.of(BulkJob.State.RUNNING, BulkJob.State.RETRY_WAIT)).items())
                .extracting(BulkStore.View::status).containsExactlyInAnyOrder(BulkJob.State.RUNNING, BulkJob.State.RETRY_WAIT);
        assertThat(store.list(100, 20, null).items()).isEmpty();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BulkController(store), new com.rlibanez.eplsync.torrent.updates.SelectionController(null,store))
                .setControllerAdvice(new com.rlibanez.eplsync.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(allStates.length))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].jobId").value(newestId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].targetFingerprint").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs")
                .param("status", "RUNNING,RETRY_WAIT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(2));
        for (var param : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"status", "BOGUS"}, new String[]{"status", "RUNNING,"}, new String[]{"state", "RUNNING"})) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs")
                    .param(param[0], param[1]))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
    }

    @Test void listingEmptyHistoryReturnsEmptyPage() {
        var result = store.list(0, 20, null);
        assertThat(result.items()).isEmpty();
        assertThat(result.meta().totalItems()).isZero();
        assertThat(result.meta().hasNext()).isFalse();
    }

    @Test void filtersItemsBeforePaginationAndPreservesSkipReasons() throws Exception {
        var job = create(null);
        var rows = items.findByJobIdOrderByPosition(job.jobId(), PageRequest.of(0, 20)).getContent();
        rows.get(1).setState(BulkItem.State.SKIPPED); rows.get(1).setMessage("Hash duplicado en la selección");
        rows.get(3).setState(BulkItem.State.SKIPPED); rows.get(3).setMessage("El libro no tiene hashes torrent válidos");
        rows.get(4).setState(BulkItem.State.FAILED); rows.get(4).setMessage("Envío rechazado");
        items.saveAll(rows);
        create(null); // Otro job no debe contaminar el filtro ni sus contadores.
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BulkController(store), new com.rlibanez.eplsync.torrent.updates.SelectionController(null,store))
                .setControllerAdvice(new com.rlibanez.eplsync.exception.GlobalExceptionHandler()).build();
        String path = "/api/torrent/jobs/" + job.jobId() + "/items";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .param("status", "SKIPPED").param("size", "1").param("page", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalPages").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].eplId").value(rows.get(3).getEplId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].message").value("El libro no tiene hashes torrent válidos"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).param("status", "SKIPPED,FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(3));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(5));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).param("status", "ACCEPTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items").isEmpty());
        for (String value : List.of("", "RUNNING", "SKIPPED,", "bogus")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).param("status", value))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs/missing/items").param("status", "SKIPPED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test void endpointsAcceptOverridesAndExposeProgressWithoutSnapshots() throws Exception {
        var conversion = new org.springframework.format.support.DefaultFormattingConversionService();
        new com.rlibanez.eplsync.config.LanguageWebConfiguration().addFormatters(conversion);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BulkController(store), new com.rlibanez.eplsync.torrent.updates.SelectionController(null,store))
                .setConversionService(conversion)
                .setCustomArgumentResolvers(new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new com.rlibanez.eplsync.exception.GlobalExceptionHandler()).build();
        var response = mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false).field("language", "en").field("size", "2").field("page", "1")
                .contentType("application/json").content("""
                {"batchSize":1,"concurrency":2,"interval":"1s","options":{"start":false}}
                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.selectedBooks").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.batchSize").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.interval").value("1000ms"))
                .andReturn().getResponse();
        String location = response.getHeader("Location");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location + "/items"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].eplId").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].commandJson").doesNotExist());
        for (String action : List.of("pause", "resume", "cancel"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(location + "/" + action))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false)
                .field("language", "en").field("pages", "27"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verify(client, never()).addTorrent(any());
    }

    @Test void intervalAppliesGloballyAcrossConcurrentDispatches() throws Exception {
        var times = new CopyOnWriteArrayList<Long>();
        when(client.addTorrent(any())).thenAnswer(invocation -> {
            times.add(System.nanoTime()); return TorrentDownloadResult.Status.ACCEPTED;
        });
        var job = create(new BulkRequest(null, 5, 3, "100ms")); start();
        until(() -> store.view(job.jobId()).status() == BulkJob.State.COMPLETED);
        assertThat(times).hasSize(5);
        for (int n = 1; n < times.size(); n++)
            assertThat(TimeUnit.NANOSECONDS.toMillis(times.get(n) - times.get(n - 1))).isGreaterThanOrEqualTo(90);
    }

    @Test void jobsDoNotOverlapAndCancellationWaitsForInflightRequests() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.addTorrent(any())).thenAnswer(invocation -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return TorrentDownloadResult.Status.ACCEPTED;
        });
        var first = create(null); var second = create(null); start(); worker.tick();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        store.control(first.jobId(), "cancel"); worker.tick();
        assertThat(store.view(second.jobId()).status()).isEqualTo(BulkJob.State.QUEUED);
        verify(client, times(1)).addTorrent(any());
        release.countDown();
        until(() -> store.view(second.jobId()).status() == BulkJob.State.COMPLETED);
        assertThat(store.view(first.jobId()).accepted()).isEqualTo(1);
        assertThat(store.view(first.jobId()).cancelled()).isEqualTo(4);
        var contexts = org.mockito.ArgumentCaptor.forClass(com.rlibanez.eplsync.torrent.TorrentSubmissionContext.class);
        verify(client, times(6)).addTorrent(any(), contexts.capture());
        var captured = contexts.getAllValues();
        assertThat(captured.get(0)).isNotSameAs(captured.get(1));
        for (int i = 2; i < captured.size(); i++) assertThat(captured.get(i)).isSameAs(captured.get(1));
    }

    @Test void retriesStopAfterThreeAutomaticRetries() {
        var job = create(null);
        var id = store.pending(job.jobId(), 1).getFirst();
        for (int attempt = 1; attempt <= 4; attempt++) {
            store.next(); store.claim(job.jobId(), id);
            store.finish(id, null, "Timeout", false, true);
            if (attempt <= 3) {
                assertThat(store.view(job.jobId()).status()).isEqualTo(BulkJob.State.RETRY_WAIT);
                var persisted = jobs.findById(job.jobId()).orElseThrow();
                persisted.setRetryAt(Instant.now().minusSeconds(1)); jobs.save(persisted);
            }
        }
        assertThat(store.view(job.jobId()).status()).isEqualTo(BulkJob.State.PAUSED);
        assertThat(items.findById(id).orElseThrow().getAttempts()).isEqualTo(4);
    }

    @Test void allExpandsEachBookIntoOrderedUniqueHashesAndCountsBooksSeparately() {
        var book = books.findById(1L).orElseThrow();
        book.setLinks(String.format("%040X,%040X,%040X", 99, 1, 99)); books.save(book);
        var job = create(new BulkRequest(null, null, null, null, MultipleHashes.ALL));
        assertThat(job.multipleHashes()).isEqualTo(MultipleHashes.ALL);
        assertThat(job.selectedBooks()).isEqualTo(5); assertThat(job.selectedTorrents()).isEqualTo(6);
        assertThat(job.selectedItems()).isEqualTo(6); assertThat(job.pending()).isEqualTo(6);
        var details = store.details(job.jobId(), 0, 20).items();
        assertThat(details).extracting(BulkStore.ItemView::eplId).containsExactly(1L, 1L, 2L, 3L, 4L, 5L);
        assertThat(details.getFirst().hash()).isEqualTo(String.format("%040X", 99));
        store.next();
        var first = store.claim(job.jobId(), details.get(0).id());
        store.finish(first.getId(), BulkItem.State.ACCEPTED, null, false, false);
        assertThat(store.view(job.jobId()).processedBooks()).isZero();
        assertThat(store.view(job.jobId()).processedTorrents()).isEqualTo(1);
        var second = store.claim(job.jobId(), details.get(1).id());
        store.finish(second.getId(), BulkItem.State.FAILED, "Test failure", false, false);
        assertThat(store.view(job.jobId()).processedBooks()).isEqualTo(1);
        assertThat(store.view(job.jobId()).processedTorrents()).isEqualTo(2);
        assertThat(store.view(job.jobId()).processedItems()).isEqualTo(2);
    }

    @Test void firstUsesOriginalListOrderAndPolicyDefaultsCanBeOverridden() {
        var book = books.findById(1L).orElseThrow();
        book.setLinks("invalid; " + String.format("%040X,%040X", 99, 1)); books.save(book);
        properties.getBulk().setMultipleHashes(MultipleHashes.FIRST);
        var first = create(null);
        assertThat(first.multipleHashes()).isEqualTo(MultipleHashes.FIRST);
        assertThat(first.selectedTorrents()).isEqualTo(5);
        assertThat(store.details(first.jobId(), 0, 20).items().getFirst().hash()).isEqualTo(String.format("%040X", 99));
        var skip = create(new BulkRequest(null, null, null, null, MultipleHashes.SKIP));
        assertThat(skip.skipped()).isEqualTo(1); assertThat(skip.selectedTorrents()).isEqualTo(4);
        properties.getBulk().setMultipleHashes(MultipleHashes.ALL);
        store.recover();
        assertThat(store.view(first.jobId()).multipleHashes()).isEqualTo(MultipleHashes.FIRST);
        assertThat(store.view(first.jobId()).selectedTorrents()).isEqualTo(5);
    }

    @Test void allDeduplicatesAcrossBooksWithoutCountingPendingHashesAsProcessed() {
        var book = books.findById(2L).orElseThrow();
        book.setLinks(String.format("%040X,%040X", 1, 2)); books.save(book);
        var job = create(new BulkRequest(null, null, null, null, MultipleHashes.ALL));
        assertThat(job.selectedBooks()).isEqualTo(5); assertThat(job.selectedTorrents()).isEqualTo(5);
        assertThat(job.selectedItems()).isEqualTo(6); assertThat(job.skipped()).isEqualTo(1);
        assertThat(job.pending()).isEqualTo(5); assertThat(job.processedTorrents()).isZero();
        assertThat(job.processedBooks()).isZero();
    }

    @Test void legacyJobsWithoutPolicyRemainSkipAndAreReadable() {
        var job = create(null);
        var legacy = jobs.findById(job.jobId()).orElseThrow(); legacy.setMultipleHashes(null); jobs.save(legacy);
        assertThat(store.view(job.jobId()).multipleHashes()).isEqualTo(MultipleHashes.SKIP);
        assertThat(store.view(job.jobId()).selectedBooks()).isEqualTo(5);
    }

    @Test void requestJsonAcceptsPoliciesAndRejectsUnknownValues() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String policy : List.of("all", "skip", "first")) {
            var request = mapper.readValue("{\"multipleHashes\":\"" + policy + "\"}", BulkRequest.class);
            assertThat(request.multipleHashes().value()).isEqualTo(policy);
            assertThat(mapper.writeValueAsString(request)).contains("\"multipleHashes\":\"" + policy + "\"");
        }
        assertThatThrownBy(() -> mapper.readValue("{\"multipleHashes\":\"last\"}", BulkRequest.class))
                .isInstanceOf(RuntimeException.class);
    }
}
