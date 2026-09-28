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
    @Autowired DownloadRepository downloads;
    @Autowired CatalogBookRepository books;
    @Autowired DownloadTrackingService tracking;
    @Autowired TorrentClientService service;
    @Autowired TorrentDownloadService individual;
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
        properties.setEnabled(true); properties.setBaseUrl("http://localhost:8080");
        when(stubClient.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        when(stubClient.addTorrent(any(), any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        when(stubClient.withDefaults(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stubClient.listTorrents()).thenReturn(List.of());
        mvc = MockMvcBuilders.standaloneSetup(controller, catalog).setCustomArgumentResolvers(new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { properties.setEnabled(false); }
    CatalogBook book(double revision, String hash) {
        return books.save(CatalogBook.builder().eplId(32L).revision(revision).author("Author").title("Title")
                .language(Language.ESPANOL).links(hash).build());
    }
    TorrentDownload command(double revision, String hash) {
        return new TorrentDownload(hash, "magnet:?xt=urn:btih:" + hash, true, null, null, null, book(revision, hash));
    }
    DownloadRecord only() { return downloads.findAll().getFirst(); }

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
        realMvc.perform(post("/api/torrent/books").param("size", "10000").param("sort", "eplId,asc"))
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

    @Test void individualAndBulkContextRecordRevisionsAndRetriesWithoutDuplicates() {
        book(1.0, HASH); individual.download(32L, null);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        assertThat(only().getSubmittedAt()).isNotNull();
        service.addTorrent(command(1.1, OTHER), new TorrentSubmissionContext());
        service.addTorrent(command(1.1, OTHER), new TorrentSubmissionContext());
        assertThat(downloads.findAll()).extracting(DownloadRecord::getRevision).containsExactlyInAnyOrder(1.0, 1.1);
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

    @Test void completionDisappearanceAndReappearancePreserveEvidenceAndIdentity() {
        service.addTorrent(command(1.0, HASH));
        var id = only().getId(); var completion = Instant.parse("2026-01-01T10:00:00Z");
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH.toLowerCase(Locale.ROOT), DownloadStatus.DOWNLOADED, completion)));
        assertThat(service.syncDownloads().completed()).isEqualTo(1);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
        when(stubClient.listTorrents()).thenReturn(List.of());
        assertThat(service.syncDownloads().notFound()).isEqualTo(1);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.NOT_FOUND);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH, DownloadStatus.DOWNLOADED, null)));
        service.syncDownloads();
        assertThat(only().getId()).isEqualTo(id);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
        assertThat(only().getCompletedAt()).isEqualTo(completion);
    }

    @Test void discoversOnlyMatchingHashesAndKeepsOriginAndOriginalRevision() {
        book(1.0, HASH + "," + OTHER);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH, DownloadStatus.PAUSED, null),
                new RemoteTorrent(OTHER, DownloadStatus.DOWNLOADED, null), new RemoteTorrent("C".repeat(40), DownloadStatus.DOWNLOADED, null)));
        var result = service.syncDownloads();
        assertThat(result.created()).isEqualTo(2); assertThat(result.ignored()).isEqualTo(1);
        assertThat(downloads.findAll()).allMatch(row -> row.getOrigin() == DownloadRecord.Origin.DISCOVERED
                && row.getDiscoveredAt() != null && row.getSubmittedAt() == null && row.getRequestedAt() == null);
        assertThat(service.syncDownloads().created()).isZero();
        service.addTorrent(command(2.0, HASH));
        assertThat(downloads.findAll()).allMatch(row -> row.getRevision() == 1.0 && row.getOrigin() == DownloadRecord.Origin.DISCOVERED);
    }

    @Test void failuresAreRecordedAndFailedSyncDoesNotChangeDatesOrStates() {
        var command = command(1.0, HASH);
        when(stubClient.addTorrent(any())).thenThrow(new IllegalArgumentException("secret path"));
        assertThatThrownBy(() -> service.addTorrent(command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.ERROR);
        assertThat(only().getLastError()).doesNotContain("secret");
        service.syncDownloads(); service.syncDownloads();
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.ERROR);
        doThrow(new TorrentConnectionException(TorrentConnectionException.Reason.TIMEOUT)).when(stubClient).addTorrent(any());
        assertThatThrownBy(() -> service.addTorrent(command)).isInstanceOf(TorrentConnectionException.class);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.UNKNOWN);
        var checked = only().getLastCheckedAt();
        when(stubClient.listTorrents()).thenThrow(new TorrentConnectionException(TorrentConnectionException.Reason.UPSTREAM));
        assertThatThrownBy(service::syncDownloads).isInstanceOf(TorrentConnectionException.class);
        assertThat(only().getLastCheckedAt()).isEqualTo(checked);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.UNKNOWN);
    }

    @Test void existingTorrentIsTrackedAndDisabledClientDoesNotSend() {
        var command = command(1, HASH);
        when(stubClient.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ALREADY_EXISTS);
        service.addTorrent(command);
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.ALREADY_EXISTS);
        assertThat(only().getSubmittedAt()).isNull();
        properties.setEnabled(false);
        assertThatThrownBy(service::syncDownloads).isInstanceOf(TorrentOperationException.class);
        verify(stubClient, never()).listTorrents();
    }

    @Test void serverChangeAndCatalogDeletionDoNotDestroyHistory() {
        service.addTorrent(command(1.0, HASH));
        var oldInstance = only().getClientInstanceId();
        properties.setBaseUrl("http://localhost:9090");
        service.syncDownloads();
        assertThat(only().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        service.addTorrent(command(1.0, HASH));
        assertThat(downloads.findAll()).hasSize(2);
        books.deleteAll(); service.syncDownloads();
        assertThat(downloads.findAll()).filteredOn(row -> row.getClientInstanceId().equals(oldInstance))
                .allMatch(row -> row.getStatus() == DownloadStatus.SUBMITTED);
        assertThat(downloads.count()).isEqualTo(2);
    }

    @Test void rejectsOverlappingSyncAndSyncDuringSubmission() throws Exception {
        var command = command(1, HASH);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(stubClient.listTorrents()).thenAnswer(inv -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return List.of(); });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(service::syncDownloads);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(service::syncDownloads).isInstanceOf(TorrentOperationException.class);
            release.countDown(); future.get(5, TimeUnit.SECONDS);
        }
        var sending = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(stubClient.addTorrent(any())).thenAnswer(inv -> { sending.countDown(); finish.await(5, TimeUnit.SECONDS); return TorrentDownloadResult.Status.ACCEPTED; });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> service.addTorrent(command));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(service::syncDownloads).isInstanceOf(TorrentOperationException.class);
            finish.countDown(); future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void apiProvidesFullDetailsFiltersAndCatalogSummary() throws Exception {
        book(2.0, HASH);
        when(stubClient.listTorrents()).thenReturn(List.of(new RemoteTorrent(HASH, DownloadStatus.DOWNLOADED, Instant.parse("2026-01-01T10:00:00Z"))));
        mvc.perform(post("/api/torrent/downloads/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
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
