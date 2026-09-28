package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false", "eplsync.torrent.client=stub", "eplsync.torrent.enabled=false",
        "eplsync.torrent.bulk.worker-enabled=false"})
@Import(UpdateTests.Config.class)
class UpdateTests {
    @TestConfiguration static class Config {
        @Bean TorrentClient stubClient() { var client = mock(TorrentClient.class); when(client.type()).thenReturn("stub"); return client; }
    }
    @Autowired UpdatePlanner planner;
    @Autowired UpdateCleanupService cleaner;
    @Autowired UpdateController controller;
    @Autowired UpdatePlanRepository plans;
    @Autowired UpdateCleanupRepository cleanup;
    @Autowired BulkStore bulk;
    @Autowired BulkJobRepository jobs;
    @Autowired BulkItemRepository items;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @Autowired CatalogBookRepository books;
    @Autowired TorrentClientService service;
    @Autowired TorrentClient stubClient;
    @Autowired TorrentProperties properties;
    MockMvc mvc;
    static final String OLD = "A".repeat(40), NEW = "B".repeat(40), OTHER = "C".repeat(40);

    @BeforeEach void setup() {
        cleanup.deleteAll(); plans.deleteAll(); items.deleteAll(); jobs.deleteAll(); downloads.deleteAll(); books.deleteAll();
        reset(stubClient);
        when(stubClient.withDefaults(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stubClient.addTorrent(any(), any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        when(stubClient.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        properties.setEnabled(true); properties.setBaseUrl("http://localhost:8080");
        properties.getBulk().setMultipleHashes(MultipleHashes.ALL);
        properties.getBulk().setInterval(java.time.Duration.ZERO);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        history(1L, 1.0, OLD, DownloadStatus.DOWNLOADED);
        book(1L, 1.2, NEW);
    }
    @AfterEach void close() { properties.setEnabled(false); }
    void book(long id, double revision, String hashes) {
        books.save(CatalogBook.builder().eplId(id).revision(revision).title("Book " + id).author("Author").links(hashes).build());
    }
    DownloadRecord history(long id, double revision, String hash, DownloadStatus status) {
        var row = new DownloadRecord(); row.setEplId(id); row.setRevision(revision); row.setHash(hash);
        row.setStatus(status); row.setOrigin(DownloadRecord.Origin.EPLSYNC); row.setClient("stub");
        row.setClientInstanceId(tracking.instanceId()); row.setCreatedAt(Instant.now());
        if (status == DownloadStatus.DOWNLOADED) row.setCompletedAt(Instant.now());
        return downloads.save(row);
    }
    BulkStore.View create(PreviousVersions policy) {
        synchronized (bulk) { return planner.create(null, false, new UpdateRequest(policy, null, 10, 2, "0ms", MultipleHashes.ALL)); }
    }
    RemoteTorrent remote(String hash, DownloadStatus status, String path) {
        return new RemoteTorrent(hash, status, null, Set.of(), path);
    }
    UpdateCleanup only(String id) { return cleaner.view(id).items().getFirst(); }

    @Test void previewIsReadOnlyAndSupportsSingleBookAndPagination() throws Exception {
        history(2, 1.0, OTHER, DownloadStatus.SUBMITTED); book(2, 2.0, "D".repeat(40));
        mvc.perform(get("/api/torrent/updates").param("eplId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].catalogRevision").value(1.2));
        mvc.perform(get("/api/torrent/updates").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eplId").value(2));
        assertThat(jobs.count()).isZero(); assertThat(plans.count()).isZero();
        assertThat(downloads.count()).isEqualTo(2);
        assertThat(downloads.findAll()).allMatch(row -> row.getLastCheckedAt() == null);
        verifyNoInteractions(stubClient);
    }

    @Test void invalidApiParametersCannotAccidentallySelectAllBooks() throws Exception {
        mvc.perform(post("/api/torrent/updates").param("eplid", "1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/torrent/updates").param("eplId", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/torrent/updates").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/torrent/updates").param("multipleHashes", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/torrent/updates").contentType("application/json")
                .content("{\"previousVersions\":\"deleteAll\"}")).andExpect(status().isBadRequest());
        assertThat(jobs.count()).isZero(); verifyNoInteractions(stubClient);
    }

    @Test void concurrentRequestsReserveOneUpdateAndInvalidOptionsRollBack() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> create(PreviousVersions.KEEP));
            var second = executor.submit(() -> create(PreviousVersions.KEEP));
            assertThat(first.get(5, TimeUnit.SECONDS).selectedBooks() + second.get(5, TimeUnit.SECONDS).selectedBooks()).isEqualTo(1);
            assertThat(items.count()).isEqualTo(1);
        }
        jobs.findAll().stream().filter(j -> j.getState() == BulkJob.State.QUEUED).forEach(j -> bulk.control(j.getId(), "cancel"));
        long count = jobs.count();
        assertThatThrownBy(() -> planner.create(null, false, new UpdateRequest(null, null, 0, 1, "0ms", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.count()).isEqualTo(count);
    }

    @Test void snapshotCleanupDoesNotDeleteALaterRevisionFromTheCatalog() {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        book(1, 1.5, OTHER);
        var old = remote(OLD, DownloadStatus.DOWNLOADED, null);
        var target = remote(NEW, DownloadStatus.DOWNLOADED, null);
        var newer = remote(OTHER, DownloadStatus.DOWNLOADED, null);
        when(stubClient.listTorrents()).thenReturn(List.of(old, target, newer), List.of(target, newer));
        cleaner.clean(job.jobId());
        verify(stubClient).deleteTorrent(OLD, false);
        verify(stubClient, never()).deleteTorrent(eq(OTHER), anyBoolean());
        verify(stubClient, never()).deleteTorrent(eq(NEW), anyBoolean());
    }

    @Test void missingPathOrIncompleteSecondTargetPreventsFileDeletion() {
        book(1, 1.2, NEW + "," + OTHER);
        var job = create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        var old = remote(OLD, DownloadStatus.DOWNLOADED, "/books/old.epub");
        var target = remote(NEW, DownloadStatus.DOWNLOADED, "/books/new.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(old, target));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        when(stubClient.listTorrents()).thenReturn(List.of(old, target, remote(OTHER, DownloadStatus.DOWNLOADED, null)));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.BLOCKED);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
    }

    @Test void explicitRetryRevalidatesCompletionBeforeRepeatingUnconfirmedDeletion() {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        var old = remote(OLD, DownloadStatus.DOWNLOADED, null);
        var target = remote(NEW, DownloadStatus.DOWNLOADED, null);
        when(stubClient.listTorrents()).thenReturn(List.of(old, target));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REQUESTED);
        when(stubClient.listTorrents()).thenReturn(List.of(old));
        cleaner.clean(job.jobId(), true);
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REQUESTED);
        verify(stubClient, times(1)).deleteTorrent(OLD, false);
    }

    @Test void selectionRequiresHigherRevisionAndEligibleHistory() {
        book(1, 0.9, NEW); assertThat(planner.preview(null, false, null)).isEmpty();
        book(1, 1.0, NEW); assertThat(planner.preview(null, false, null)).isEmpty();
        book(1, 1.2, OLD); assertThat(planner.preview(null, false, null)).isEmpty();
        book(1, 1.2, NEW);
        var row = downloads.findAll().getFirst(); row.setStatus(DownloadStatus.NOT_FOUND); downloads.save(row);
        assertThat(planner.preview(null, false, null)).isEmpty();
        assertThat(planner.preview(null, true, null)).hasSize(1);
        row.setStatus(DownloadStatus.ERROR); downloads.save(row);
        assertThat(planner.preview(null, true, null)).isEmpty();
        row.setStatus(DownloadStatus.UNKNOWN); downloads.save(row);
        assertThat(planner.preview(null, true, null)).isEmpty();
    }

    @Test void snapshotsSurviveCatalogChangesAndDuplicateRequestsDoNotEnqueueAgain() throws Exception {
        var job = create(PreviousVersions.KEEP);
        assertThat(job.selectedBooks()).isEqualTo(1);
        assertThat(planner.preview(null, false, null)).isEmpty();
        assertThat(create(PreviousVersions.KEEP).selectedBooks()).isZero();
        book(1, 1.5, OTHER);
        var worker = new BulkWorker(bulk, service, properties);
        try {
            worker.recover();
            for (int i = 0; i < 100 && bulk.view(job.jobId()).status() != BulkJob.State.COMPLETED; i++) {
                worker.tick(); Thread.sleep(5);
            }
            assertThat(bulk.view(job.jobId()).status()).isEqualTo(BulkJob.State.COMPLETED);
            var captor = org.mockito.ArgumentCaptor.forClass(TorrentDownload.class);
            verify(stubClient).addTorrent(captor.capture(), any());
            assertThat(captor.getValue().hash()).isEqualTo(NEW);
            assertThat(captor.getValue().book().getRevision()).isEqualTo(1.2);
            assertThat(cleaner.view(job.jobId()).updates().getFirst().catalogRevision()).isEqualTo(1.2);
        } finally { worker.close(); }
    }

    @Test void allFirstAndSkipPoliciesSelectExpectedTargets() {
        book(1, 1.2, NEW + "," + OTHER);
        assertThat(planner.preview(null, false, MultipleHashes.ALL).getFirst().targetHashes()).containsExactly(NEW, OTHER);
        assertThat(planner.preview(null, false, MultipleHashes.FIRST).getFirst().targetHashes()).containsExactly(NEW);
        assertThat(planner.preview(null, false, MultipleHashes.SKIP)).isEmpty();
    }

    @Test void defaultsKeepOldTorrentsAndCleanupDoesNotContactClient() throws Exception {
        mvc.perform(post("/api/torrent/updates").param("eplId", "1"))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"));
        var jobId = plans.findAll().getFirst().getJobId();
        clearInvocations(stubClient);
        assertThat(cleaner.clean(jobId).previousVersions()).isEqualTo(PreviousVersions.KEEP);
        assertThat(only(jobId).getState()).isEqualTo(UpdateCleanup.State.KEPT);
        verifyNoInteractions(stubClient);
    }

    @Test void cleanupWaitsForLiveCompletionThenDeletesOnlyOldTorrentAndPreservesHistory() {
        var oldRecord = downloads.findAll().getFirst();
        var job = create(PreviousVersions.REMOVE_TORRENT);
        var old = remote(OLD, DownloadStatus.DOWNLOADED, "/books/old.epub");
        var target = remote(NEW, DownloadStatus.DOWNLOADING, "/books/new.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(old, target));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
        target = remote(NEW, DownloadStatus.DOWNLOADED, "/books/new.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(old, target), List.of(target));
        cleaner.clean(job.jobId());
        verify(stubClient).deleteTorrent(OLD, false);
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        var retained = downloads.findById(oldRecord.getId()).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(DownloadStatus.NOT_FOUND);
        assertThat(retained.getCompletedAt()).isEqualTo(oldRecord.getCompletedAt());
        cleaner.clean(job.jobId());
        verify(stubClient, times(1)).deleteTorrent(any(), anyBoolean());
    }

    @Test void fileDeletionRequiresNonOverlappingKnownPathsAndUsesRemoteHybridId() {
        var job = create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        var old = new RemoteTorrent("D".repeat(40), DownloadStatus.DOWNLOADED, null, Set.of(OLD), "/books/old.epub");
        var target = remote(NEW, DownloadStatus.DOWNLOADED, "/books/old.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(old, target));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.BLOCKED);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
        target = remote(NEW, DownloadStatus.DOWNLOADED, "/books/new.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(old, target), List.of(target));
        cleaner.clean(job.jobId());
        verify(stubClient).deleteTorrent("D".repeat(40), true);
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
    }

    @Test void sharedTorrentAndChangedDestinationBlockDeletion() {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        book(2, 1.0, OLD);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, null), remote(NEW, DownloadStatus.DOWNLOADED, null)));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.BLOCKED);
        properties.setBaseUrl("http://another-client:8080");
        assertThatThrownBy(() -> cleaner.clean(job.jobId())).hasMessageContaining("destino");
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
    }

    @Test void uncertainDeletionIsNotAutomaticallyRetried() {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, null), remote(NEW, DownloadStatus.DOWNLOADED, null)));
        doThrow(new IllegalStateException("network lost")).when(stubClient).deleteTorrent(OLD, false);
        cleaner.clean(job.jobId()); cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REQUESTED);
        verify(stubClient, times(1)).deleteTorrent(OLD, false);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(NEW, DownloadStatus.DOWNLOADED, null)));
        cleaner.clean(job.jobId());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
    }

    @Test void cleanupRejectsConcurrentSubmissionWithoutDeleting() throws Exception {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(stubClient.addTorrent(any())).thenAnswer(inv -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return TorrentDownloadResult.Status.ACCEPTED; });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> service.addTorrent(new TorrentDownload(NEW, "magnet:?xt=urn:btih:" + NEW,
                    true, null, null, null, books.findById(1L).orElseThrow())));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> cleaner.clean(job.jobId())).hasMessageContaining("envíos");
                verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
            } finally { release.countDown(); }
            future.get(5, TimeUnit.SECONDS);
        }
    }
}
