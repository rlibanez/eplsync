package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.controller.CatalogBookController;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.*;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.*;
import com.rlibanez.eplsync.torrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","DOWNLOADS_READ","TORRENT_SEND","TORRENT_SYNC","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "eplsync.torrent.client=stub", "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
@Import(DownloadTrackingTests.Config.class)
class DownloadTrackingTests {
    @TestConfiguration static class Config {
        @Bean TorrentClient stubClient() {
            var client = mock(TorrentClient.class); when(client.type()).thenReturn("stub"); return client;
        }
    }
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired DownloadRepository downloads;
    @Autowired CatalogBookRepository books;
    @Autowired DownloadTrackingService tracking;
    @Autowired TorrentClientService service;
    @Autowired TorrentDownloadService individual;
    @Autowired com.rlibanez.eplsync.controller.TorrentDownloadController individualController;
    @Autowired TorrentClient stubClient;
    @Autowired TorrentProperties properties;
    @Autowired DownloadController controller;
    @Autowired CatalogBookController catalog;
    @Autowired DownloadQueryService queries;
    @Autowired CatalogDownloadViewService views;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkItemRepository bulkItems;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkJobRepository bulkJobs;
    MockMvc mvc;
    static final String HASH = "A".repeat(40), OTHER = "B".repeat(40);

    @BeforeEach void setup() {
        bulkItems.deleteAll(); bulkJobs.deleteAll(); downloads.deleteAll(); books.deleteAll(); reset(stubClient);
        properties.effective().setEnabled(true); properties.effective().setBaseUrl("http://localhost:8080");
        when(stubClient.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        when(stubClient.addTorrent(any(), any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        when(stubClient.withDefaults(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stubClient.listTorrents()).thenReturn(List.of());
        mvc = MockMvcBuilders.standaloneSetup(controller, catalog, individualController).setCustomArgumentResolvers(new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { properties.effective().setEnabled(false); }
    CatalogBook book(double revision, String hash) {
        return books.save(CatalogBook.builder().eplId(32L).revision(revision).author("Author").title("Title")
                .language(Language.ESPANOL).links(hash).build());
    }
    TorrentDownload command(double revision, String hash) {
        return new TorrentDownload(hash, "magnet:?xt=urn:btih:" + hash, true, null, null, null, book(revision, hash));
    }
    DownloadRecord only() { return downloads.findAll().getFirst(); }

    @Test void singleSubmissionCreatesJobWithoutSendingAndDryRunDoesNotPersist() throws Exception {
        book(1.0, HASH);
        long cursor = events.cursor();
        mvc.perform(post("/api/torrent/books/32").contentType("application/json").content("{\"dryRun\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.selectedBooks").value(1));
        assertThat(bulkJobs.count()).isZero(); assertThat(events.after(cursor, 10)).isEmpty();
        mvc.perform(post("/api/torrent/books/32").contentType("application/json").content("{\"dryRun\":false}"))
            .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.selectedBooks").value(1));
        assertThat(bulkJobs.count()).isEqualTo(1); assertThat(bulkItems.count()).isEqualTo(1);
        assertThat(events.after(cursor, 10)).hasSize(1);
        verify(stubClient, never()).addTorrent(any()); verify(stubClient, never()).addTorrent(any(),any());
        assertThat(downloads.count()).isZero();
    }
    @Test void singleSubmissionRequiresExplicitDryRun() throws Exception {
        mvc.perform(post("/api/torrent/books/32").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        assertThat(bulkJobs.count()).isZero();
    }
    @Test void appliedSyncPublishesOnePersistedResultAndFailureIsRecorded() throws Exception {
        long cursor = events.cursor();
        mvc.perform(post("/api/torrent/downloads/sync").contentType("application/json")
                .content("{\"dryRun\":true}"))
                .andExpect(status().isOk()).andExpect(header().exists("X-EPLSync-Operation-Id"));
        assertThat(events.after(cursor, 10)).extracting(value -> Objects.requireNonNull(value).action())
                .containsExactly("SYNC_PREVIEW", "SYNC_PREVIEW");
        assertThat(downloads.count()).isZero();
        cursor = events.cursor();
        mvc.perform(post("/api/torrent/downloads/sync").contentType("application/json").content("{\"dryRun\":false}"))
                .andExpect(status().isOk()).andExpect(header().exists("X-EPLSync-Operation-Id"));
        var rows = events.after(cursor, 10);
        assertThat(rows).extracting(value -> Objects.requireNonNull(value).outcome())
                .containsExactly(com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED,
                    com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        assertThat(rows.get(1).details()).containsEntry("checked", 0);
        assertThat(rows.get(0).operationId()).isEqualTo(rows.get(1).operationId());
        cursor = events.cursor();
        properties.effective().setEnabled(false);
        assertThatThrownBy(() -> service.syncDownloads(false, false)).isInstanceOf(RuntimeException.class);
        assertThat(events.after(cursor, 10)).extracting(value -> Objects.requireNonNull(value).outcome())
                .containsExactly(com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED,
                    com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED);
    }

    @Test void catalogFiltersByExactRevisionAndCombinesOtherFilters() throws Exception {
        book(1.1, HASH);
        books.save(CatalogBook.builder().eplId(33L).revision(1.2).title("Other").author("Author").links(OTHER).build());
        mvc.perform(get("/api/catalog/books").param("revision", "1.1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eplId").value(32));
        mvc.perform(get("/api/catalog/books").param("revision", "1.2").param("eplId", "32"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        for (String invalid : List.of("-1", "NaN", "Infinity", "abc"))
            mvc.perform(get("/api/catalog/books").param("revision", invalid)).andExpect(status().isBadRequest());
    }

    @Test void manualLinkPreservesCurrentRevisionAndIsRecognizedBySync() throws Exception {
        book(1.8, HASH);
        var completed = Instant.parse("2026-09-29T10:00:00Z");
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(OTHER, DownloadStatus.DOWNLOADED, completed)));
        var body = "{\"clientInstanceId\":\"" + tracking.instanceId()
                + "\",\"hash\":\"" + OTHER + "\",\"eplId\":32,\"revision\":1.7}";
        mvc.perform(post("/api/torrent/downloads/link").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1.7))
                .andExpect(jsonPath("$.status").value("DOWNLOADED"));
        assertThat(books.findById(32L).orElseThrow().getRevision()).isEqualTo(1.8);
        assertThat(only().getCompletedAt()).isEqualTo(completed);
        mvc.perform(post("/api/torrent/downloads/link").contentType("application/json").content(body))
                .andExpect(status().isOk());
        assertThat(downloads.count()).isEqualTo(1);
        assertThat(service.syncDownloads(true, true).ignoredTorrents()).isEmpty();
        mvc.perform(post("/api/torrent/downloads/link").contentType("application/json").content(body.replace("1.7", "1.6")))
                .andExpect(status().isConflict());
        verify(stubClient, never()).addTorrent(any());
    }

    @Test void manualLinkRejectsStaleMissingAndInvalidInputs() {
        book(1.8, HASH);
        var request = new DownloadTrackingService.LinkRequest(tracking.instanceId(), OTHER, 32L, 1.7);
        assertThatThrownBy(() -> service.linkDownload(request)).isInstanceOf(TorrentOperationException.class);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(OTHER, DownloadStatus.DOWNLOADING, null)));
        assertThatThrownBy(() -> service.linkDownload(new DownloadTrackingService.LinkRequest("old", OTHER, 32L, 1.7)))
                .isInstanceOf(TorrentOperationException.class);
        assertThatThrownBy(() -> service.linkDownload(new DownloadTrackingService.LinkRequest(tracking.instanceId(), OTHER, 99L, 1.7)))
                .isInstanceOf(TorrentOperationException.class);
        assertThatThrownBy(() -> service.linkDownload(new DownloadTrackingService.LinkRequest(tracking.instanceId(), OTHER, 32L, -1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(downloads.count()).isZero();
    }

    @Autowired org.springframework.web.context.WebApplicationContext webContext;

    @Test void largePagesAreNotRejectedOrSilentlyTruncatedBySpring() throws Exception {
        var fixtures = java.util.stream.LongStream.rangeClosed(1, 2001)
                .mapToObj(id -> CatalogBook.builder().eplId(id).revision(1.0).title("Book " + id).author("Author")
                        .links(String.format("%040X", id)).build()).toList();
        books.saveAll(fixtures);
        var realMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        realMvc.perform(get("/api/catalog/books").param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(10000))
                .andExpect(jsonPath("$.items.length()").value(2001));
        realMvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false).field("size", "10000").field("sort", "eplId,asc"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selectedBooks").value(2001));
        realMvc.perform(get("/api/catalog/magnets").param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(10000));
        realMvc.perform(get("/api/torrent/downloads").param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(10000));
        realMvc.perform(get("/api/torrent/jobs").param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(10000));
        var id = bulkJobs.findAll().getFirst().getId();
        realMvc.perform(get("/api/torrent/jobs/" + id + "/items").param("size", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2001));
        for (String endpoint : List.of("/api/catalog/books", "/api/catalog/magnets", "/api/torrent/downloads", "/api/torrent/jobs")) {
            realMvc.perform(get(endpoint).param("size", "0")).andExpect(status().isBadRequest());
            realMvc.perform(get(endpoint).param("page", "-1")).andExpect(status().isBadRequest());
        }
    }

    @Test void eplIdFiltersCatalogMagnetsAndBulkWithValidation() throws Exception {
        book(1.0, HASH);
        books.save(CatalogBook.builder().eplId(33L).revision(1.0).author("Another author")
                .title("Another title").links(OTHER).build());
        var api = MockMvcBuilders.webAppContextSetup(webContext).build();
        api.perform(get("/api/catalog/books").param("eplId", "32"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eplId").value(32));
        api.perform(get("/api/catalog/books").param("eplId", "32").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].eplId").value(32));
        api.perform(get("/api/catalog/books").param("eplId", "32").param("title", "does not match"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        api.perform(get("/api/catalog/books").param("eplId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        api.perform(get("/api/catalog/books/999")).andExpect(status().isNotFound());
        api.perform(get("/api/catalog/magnets").param("eplId", "32"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(org.hamcrest.Matchers.containsString(HASH)));
        api.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false).field("eplId", "32"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selectedBooks").value(1));
        assertThat(bulkItems.findAll()).hasSize(1).allMatch(item -> item.getEplId().equals(32L));
        for (String invalid : List.of("0", "-1", "abc", "9223372036854775808")) {
            api.perform(get("/api/catalog/books").param("eplId", invalid)).andExpect(status().isBadRequest());
            api.perform(get("/api/catalog/magnets").param("eplId", invalid)).andExpect(status().isBadRequest());
            api.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false).field("eplId", invalid)).andExpect(status().isBadRequest());
        }
        assertThat(bulkJobs.count()).isEqualTo(1);
        verify(stubClient, never()).addTorrent(any(), any());
        verify(stubClient, never()).addTorrent(any());
    }

    @Test void summaryAggregatesStoredStatesWithFiltersAndDoesNotContactClient() throws Exception {
        service.addTorrent(command(1.0, HASH));
        service.addTorrent(command(1.1, OTHER));
        var rows = downloads.findAll();
        var old = rows.stream().filter(row -> row.getHash().equals(HASH)).findFirst().orElseThrow();
        old.setStatus(DownloadStatus.NOT_FOUND);
        old.setCompletedAt(Instant.parse("2026-01-01T10:00:00Z"));
        var current = rows.stream().filter(row -> row.getHash().equals(OTHER)).findFirst().orElseThrow();
        current.setStatus(DownloadStatus.DOWNLOADED);
        current.setCompletedAt(Instant.parse("2026-02-01T10:00:00Z"));
        downloads.saveAll(rows);
        clearInvocations(stubClient);
        mvc.perform(get("/api/torrent/downloads/summary"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byStatus.NOT_FOUND").value(1))
                .andExpect(jsonPath("$.byStatus.DOWNLOADED").value(1))
                .andExpect(jsonPath("$.byStatus.DOWNLOADING").value(0))
                .andExpect(jsonPath("$.byStatus.length()").value(DownloadStatus.values().length));
        mvc.perform(get("/api/torrent/downloads/summary").param("eplId", "32")
                .param("clientInstanceId", tracking.instanceId()).param("origin", "EPLSYNC")
                .param("completed", "true").param("completedAtFrom", "2026-02-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byStatus.NOT_FOUND").value(0));
        mvc.perform(get("/api/torrent/downloads/summary").param("status", "DOWNLOADED,NOT_FOUND"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/torrent/downloads/summary").param("hash", HASH.toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.byStatus.NOT_FOUND").value(1));
        mvc.perform(get("/api/torrent/downloads/summary").param("clientInstanceId", "other"))
                .andExpect(jsonPath("$.total").value(0));
        assertThat(downloads.findById(old.getId()).orElseThrow().getLastCheckedAt()).isNull();
        verifyNoInteractions(stubClient);
    }

    @Test void emptySummaryIncludesEveryStateAndRejectsInvalidFilters() throws Exception {
        var summary = queries.summary(new LinkedMultiValueMap<>());
        assertThat(summary.total()).isZero();
        assertThat(summary.byStatus()).hasSize(DownloadStatus.values().length);
        assertThat(summary.byStatus().values()).containsOnly(0L);
        for (var pair : List.of(new String[]{"page", "0"}, new String[]{"size", "20"}, new String[]{"sort", "id"},
                new String[]{"unknown", "1"}, new String[]{"status", "BOGUS"}, new String[]{"completed", "yes"},
                new String[]{"revision", "NaN"}, new String[]{"origin", ""})) {
            mvc.perform(get("/api/torrent/downloads/summary").param(pair[0], pair[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/torrent/downloads/summary").param("eplId", "32", "33")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/torrent/downloads/summary").param("completedAtFrom", "2026-02-01T00:00:00Z")
                .param("completedAtTo", "2026-01-01T00:00:00Z")).andExpect(status().isBadRequest());
        verifyNoInteractions(stubClient);
    }

    @Test void individualAndBulkContextRecordRevisionsAndRetriesWithoutDuplicates() {
        book(1.0, HASH); individual.download(32L, null);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        assertThat(only().getSubmittedAt()).isNotNull();
        service.addTorrent(command(1.1, OTHER), new TorrentSubmissionContext());
        service.addTorrent(command(1.1, OTHER), new TorrentSubmissionContext());
        assertThat(downloads.findAll()).extracting(value -> Objects.requireNonNull(value).getRevision()).containsExactlyInAnyOrder(1.0, 1.1);
        assertThat(downloads.findAll()).allMatch(row -> row.getOrigin() == DownloadRecord.Origin.EPLSYNC);
    }

    @Test void workerPersistsTheRevisionFromItsSnapshot() throws Exception {
        book(1.0, HASH);
        var job = bulk.create(new com.rlibanez.eplsync.filter.CatalogBookFilter(),
                org.springframework.data.domain.PageRequest.of(0, 20), false, true, null);
        book(2.0, OTHER);
        var worker = new com.rlibanez.eplsync.torrent.bulk.BulkWorker(bulk, service, properties);
        try {
            worker.recover();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (bulk.job(job.jobId()).getState() != com.rlibanez.eplsync.torrent.bulk.BulkJob.State.COMPLETED
                    && System.nanoTime() < deadline) { worker.tick(); Thread.sleep(10); }
            assertThat(bulk.job(job.jobId()).getState()).isEqualTo(com.rlibanez.eplsync.torrent.bulk.BulkJob.State.COMPLETED);
            assertThat(only().getRevision()).isEqualTo(1.0);
            assertThat(only().getHash()).isEqualTo(HASH);
            assertThat(only().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        } finally { worker.close(); }
    }

    @Test void hybridTorrentRepairsNotFoundWithoutDuplicatingRecordsOrRemoteCounts() {
        service.addTorrent(command(1.6, HASH));
        var id = only().getId();
        when(stubClient.listTorrents()).thenReturn(List.of());
        service.syncDownloads(false, true);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.NOT_FOUND);
        var completion = Instant.parse("2026-01-01T10:00:00Z");
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent("B".repeat(40),
                DownloadStatus.DOWNLOADED, completion, java.util.Set.of(HASH.toLowerCase(Locale.ROOT), "B".repeat(64)))));
        var result = service.syncDownloads(false, true);
        assertThat(result.remote().total()).isEqualTo(1);
        assertThat(result.records().updated()).isEqualTo(1);
        assertThat(result.outcomes().newlyCompleted()).isEqualTo(1);
        assertThat(result.records().created()).isZero();
        assertThat(result.outcomes().notFound()).isZero();
        assertThat(result.remote().ignored()).isZero();
        assertThat(only().getId()).isEqualTo(id);
        assertThat(only().getHash()).isEqualTo(HASH);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
        assertThat(service.syncDownloads(false, true).outcomes().newlyCompleted()).isZero();
    }

    @Test void discoversHybridByCatalogHashAndIgnoresOneUnrelatedTorrent() {
        book(1.0, HASH);
        when(stubClient.listTorrents()).thenReturn(List.of(
                new RemoteTorrent("B".repeat(40), DownloadStatus.DOWNLOADED, null,
                        java.util.Set.of(HASH, "B".repeat(64))),
                new RemoteTorrent("C".repeat(40), DownloadStatus.DOWNLOADED, null,
                        java.util.Set.of("C".repeat(64)))));
        var result = service.syncDownloads(false, true);
        assertThat(result.remote().total()).isEqualTo(2);
        assertThat(result.records().created()).isEqualTo(1);
        assertThat(result.remote().ignored()).isEqualTo(1);
        assertThat(only().getHash()).isEqualTo(HASH);
        assertThat(only().getOrigin()).isEqualTo(DownloadRecord.Origin.DISCOVERED);
        assertThat(service.syncDownloads(false, true).records().created()).isZero();
    }

    @Test void completionDisappearanceAndReappearancePreserveEvidenceAndIdentity() {
        service.addTorrent(command(1.0, HASH));
        var id = only().getId(); var completion = Instant.parse("2026-01-01T10:00:00Z");
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH.toLowerCase(Locale.ROOT), DownloadStatus.DOWNLOADED, completion)));
        assertThat(service.syncDownloads(false, true).outcomes().newlyCompleted()).isEqualTo(1);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
        when(stubClient.listTorrents()).thenReturn(List.of());
        assertThat(service.syncDownloads(false, true).outcomes().notFound()).isEqualTo(1);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.NOT_FOUND);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH, DownloadStatus.DOWNLOADED, null)));
        service.syncDownloads(false, true);
        assertThat(only().getId()).isEqualTo(id);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
    }

    @Test void discoversOnlyMatchingHashesAndKeepsOriginAndOriginalRevision() {
        book(1.0, HASH + "," + OTHER);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH, DownloadStatus.PAUSED, null),
                new RemoteTorrent(OTHER, DownloadStatus.DOWNLOADED, null), new RemoteTorrent("C".repeat(40), DownloadStatus.DOWNLOADED, null)));
        var result = service.syncDownloads(false, true);
        assertThat(result.records().created()).isEqualTo(2); assertThat(result.remote().ignored()).isEqualTo(1);
        assertThat(downloads.findAll()).allMatch(row -> row.getOrigin() == DownloadRecord.Origin.DISCOVERED
                && row.getDiscoveredAt() != null && row.getSubmittedAt() == null && row.getRequestedAt() == null);
        assertThat(service.syncDownloads(false, true).records().created()).isZero();
        service.addTorrent(command(2.0, HASH));
        assertThat(downloads.findAll()).allMatch(row -> row.getRevision() == 1.0 && row.getOrigin() == DownloadRecord.Origin.DISCOVERED);
    }

    @Test void failuresAreRecordedAndFailedSyncDoesNotChangeDatesOrStates() {
        var command = command(1.0, HASH);
        when(stubClient.addTorrent(any())).thenThrow(new IllegalArgumentException("secret path"));
        assertThatThrownBy(() -> service.addTorrent(command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.ERROR);
        assertThat(only().getLastError()).doesNotContain("secret");
        service.syncDownloads(false, true); service.syncDownloads(false, true);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.ERROR);
        doThrow(new TorrentConnectionException(TorrentConnectionException.Reason.TIMEOUT)).when(stubClient).addTorrent(any());
        assertThatThrownBy(() -> service.addTorrent(command)).isInstanceOf(TorrentConnectionException.class);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.UNKNOWN);
        var checked = only().getLastCheckedAt();
        when(stubClient.listTorrents()).thenThrow(new TorrentConnectionException(TorrentConnectionException.Reason.UPSTREAM));
        assertThatThrownBy(() -> service.syncDownloads(false, true)).isInstanceOf(TorrentConnectionException.class);
        assertThat(only().getLastCheckedAt()).isEqualTo(checked);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.UNKNOWN);
    }

    @Test void existingTorrentIsTrackedAndDisabledClientDoesNotSend() {
        var command = command(1, HASH);
        when(stubClient.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ALREADY_EXISTS);
        service.addTorrent(command);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.ALREADY_EXISTS);
        assertThat(only().getSubmittedAt()).isNull();
        properties.effective().setEnabled(false);
        assertThatThrownBy(() -> service.syncDownloads(false, true)).isInstanceOf(TorrentOperationException.class);
        verify(stubClient, never()).listTorrents();
    }

    @Test void serverChangeAndCatalogDeletionDoNotDestroyHistory() {
        service.addTorrent(command(1.0, HASH));
        var oldInstance = only().getClientInstanceId();
        properties.effective().setBaseUrl("http://localhost:9090");
        service.syncDownloads(false, true);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        service.addTorrent(command(1.0, HASH));
        assertThat(downloads.findAll()).hasSize(2);
        books.deleteAll(); service.syncDownloads(false, true);
        assertThat(downloads.findAll()).filteredOn(row -> row.getClientInstanceId().equals(oldInstance))
                .allMatch(row -> row.getStatus() == DownloadStatus.SUBMITTED);
        assertThat(downloads.count()).isEqualTo(2);
    }

    @Test void rejectsOverlappingSyncAndSyncDuringSubmission() throws Exception {
        var command = command(1, HASH);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(stubClient.listTorrents()).thenAnswer(inv -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return List.of(); });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> service.syncDownloads(false, true));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.syncDownloads(false, true)).isInstanceOf(TorrentOperationException.class);
            release.countDown(); future.get(5, TimeUnit.SECONDS);
        }
        var sending = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(stubClient.addTorrent(any())).thenAnswer(inv -> { sending.countDown(); finish.await(5, TimeUnit.SECONDS); return TorrentDownloadResult.Status.ACCEPTED; });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> service.addTorrent(command));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.syncDownloads(false, true)).isInstanceOf(TorrentOperationException.class);
            finish.countDown(); future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void previewCalculatesSameChangesWithoutWritingAnyFields() {
        service.addTorrent(command(1.0, HASH));
        book(1.0, HASH + "," + OTHER);
        var before = only();
        when(stubClient.listTorrents()).thenReturn(List.of(
                new RemoteTorrent(HASH, DownloadStatus.DOWNLOADED, Instant.parse("2026-01-01T10:00:00Z")),
                new RemoteTorrent(OTHER, DownloadStatus.PAUSED, null),
                new RemoteTorrent("C".repeat(40), DownloadStatus.DOWNLOADING, null, Set.of(), null, "Unrelated")));
        var preview = service.syncDownloads(true, true);
        assertThat(preview.dryRun()).isTrue(); assertThat(preview.applied()).isFalse();
        assertThat(preview.records()).isEqualTo(new DownloadTrackingService.RecordCounts(2,1,1,0));
        assertThat(preview.remote()).isEqualTo(new DownloadTrackingService.RemoteCounts(3,2,1));
        assertThat(preview.ignoredTorrents()).singleElement().satisfies(item -> assertThat(item.name()).isEqualTo("Unrelated"));
        assertThat(preview.items()).filteredOn(item -> item.action().equals("CREATE")).singleElement()
                .satisfies(item -> assertThat(item.downloadId()).isNull());
        assertThat(downloads.count()).isEqualTo(1);
        assertThat(only()).usingRecursiveComparison().isEqualTo(before);
        var applied = service.syncDownloads(false, true);
        assertThat(applied.records()).isEqualTo(preview.records());
        assertThat(applied.outcomes()).isEqualTo(preview.outcomes());
        assertThat(applied.remote()).isEqualTo(preview.remote());
        assertThat(applied.applied()).isTrue();
        assertThat(downloads.count()).isEqualTo(2);
        var repeated = service.syncDownloads(true, false);
        assertThat(repeated.records().unchanged()).isEqualTo(2);
        assertThat(repeated.items()).isNull(); assertThat(repeated.ignoredTorrents()).isNull();
    }

    @Test void missingCountsDistinguishNewAndRepeatedAbsences() {
        service.addTorrent(command(1.0, HASH));
        var preview = service.syncDownloads(true, true);
        assertThat(preview.outcomes().newlyNotFound()).isEqualTo(1);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        service.syncDownloads(false, false);
        var repeated = service.syncDownloads(true, true);
        assertThat(repeated.outcomes().notFound()).isEqualTo(1);
        assertThat(repeated.outcomes().newlyNotFound()).isZero();
        assertThat(repeated.records().unchanged()).isEqualTo(1);
    }

    @Test void syncRequiresStrictJsonOptionsAndDoesNotAcceptLegacyRequests() throws Exception {
        for (String body : List.of("{}", "null", "{\"dryRun\":null}", "{\"dryRun\":\"true\"}",
                "{\"dryRun\":1}", "{\"dryRun\":true,\"includeDetails\":null}", "{\"dryRun\":true,\"unknown\":true}"))
            mvc.perform(post("/api/torrent/downloads/sync").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(post("/api/torrent/downloads/sync")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/torrent/downloads/sync")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/torrent/downloads/sync?dryRun=true").contentType("application/json").content("{\"dryRun\":true}"))
                .andExpect(status().isBadRequest());
        verify(stubClient, never()).listTorrents();
        mvc.perform(post("/api/torrent/downloads/sync").contentType("application/json").content("{\"dryRun\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.applied").value(false)).andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test void apiProvidesFullDetailsFiltersAndCatalogSummary() throws Exception {
        book(2.0, HASH);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH, DownloadStatus.DOWNLOADED, Instant.parse("2026-01-01T10:00:00Z"))));
        mvc.perform(post("/api/torrent/downloads/sync").contentType("application/json").content("{\"dryRun\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.records.created").value(1));
        mvc.perform(get("/api/torrent/downloads").param("eplId", "32").param("completed", "true")
                .param("status", "DOWNLOADED,NOT_FOUND").param("origin", "DISCOVERED").param("revision", "2")
                .param("completedAtFrom", "2026-01-01T00:00:00Z").param("sort", "revision,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].hash").value(HASH)).andExpect(jsonPath("$.items[0].client").value("stub"))
                .andExpect(jsonPath("$.items[0].discoveredAt").exists()).andExpect(jsonPath("$.items[0].lastSeenAt").exists());
        mvc.perform(get("/api/catalog/books/32")).andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("es")).andExpect(jsonPath("$.download.items[0].completed").value(true))
                .andExpect(jsonPath("$.download.items[0].revision").value(2.0)).andExpect(jsonPath("$.download.currentRevisionDownloaded").doesNotExist());
        mvc.perform(get("/api/catalog/books").param("page", "0").param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].download.items[0].completed").value(true));
        mvc.perform(get("/api/catalog/books"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].download.items[0].completed").value(true));
        var enriched = views.enrich(books.findAll());
        assertThat(enriched.getFirst().download().items()).hasSize(1);
        downloads.deleteAll();
        mvc.perform(get("/api/catalog/books/32")).andExpect(jsonPath("$.download.items").isEmpty());
        for (var pair : List.of(new String[]{"pages", "2"}, new String[]{"completed", "yes"}, new String[]{"revision", "NaN"},
                new String[]{"status", "BOGUS"}, new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"sort", "password,asc"}, new String[]{"createdAtFrom", "yesterday"})) {
            mvc.perform(get("/api/torrent/downloads").param(pair[0], pair[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/torrent/downloads").param("createdAtFrom", "2026-02-01T00:00:00Z")
                .param("createdAtTo", "2026-01-01T00:00:00Z")).andExpect(status().isBadRequest());
    }
}
