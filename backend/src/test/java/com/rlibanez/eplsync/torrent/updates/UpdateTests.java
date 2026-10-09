package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
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

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","DOWNLOADS_DELETE","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true", "eplsync.torrent.client=stub", "eplsync.torrent.enabled=false",
        "eplsync.torrent.bulk.worker-enabled=false"})
@Import(UpdateTests.Config.class)
class UpdateTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path walDatabaseDirectory;
    @org.springframework.test.context.DynamicPropertySource
    static void diskConcurrencyConfiguration(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (Boolean.getBoolean("eplsync.test.sqlite-disk")) {
            registry.add("spring.datasource.url", () -> "jdbc:sqlite:"+walDatabaseDirectory.resolve("test.db"));
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        }
    }

    @TestConfiguration static class Config {
        @Bean TorrentClient stubClient() { var client = mock(TorrentClient.class); when(client.type()).thenReturn("stub"); return client; }
    }
    @Autowired CleanupQueue queue;
    @Autowired HistoryActions historyActions;
    @Autowired UpdatePlanner planner;
    @Autowired SelectedUpdateSender selectedSender;
    @Autowired UpdateCleanupService cleaner;
    @Autowired com.rlibanez.eplsync.events.EventJournal journal;
    @Autowired com.rlibanez.eplsync.security.AccountStore ownerStore;
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
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    MockMvc mvc;
    static final String OLD = "A".repeat(40), NEW = "B".repeat(40), OTHER = "C".repeat(40);

    @BeforeEach void setup() {
        cleanup.deleteAll(); plans.deleteAll(); items.deleteAll(); jobs.deleteAll(); downloads.deleteAll(); books.deleteAll();
        reset(stubClient);
        when(stubClient.withDefaults(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stubClient.addTorrent(any(), any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        when(stubClient.addTorrent(any())).thenReturn(TorrentDownloadResult.Status.ACCEPTED);
        properties.effective().setEnabled(true); properties.effective().setBaseUrl("http://localhost:8080");
        properties.getBulk().setMultipleHashes(MultipleHashes.ALL);
        properties.getBulk().setInterval(java.time.Duration.ZERO);
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        history(1L, 1.0, OLD, DownloadStatus.DOWNLOADED);
        book(1L, 1.2, NEW);
    }
    @AfterEach void close() { properties.effective().setEnabled(false); }
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

    void language(long id, com.rlibanez.eplsync.model.enums.Language language) {
        var book = books.findById(id).orElseThrow(); book.setLanguage(language); books.save(book);
    }

    void mixedCatalogue() {
        language(1, com.rlibanez.eplsync.model.enums.Language.ESPANOL);
        book(2, 1.0, OTHER); language(2, com.rlibanez.eplsync.model.enums.Language.ESPANOL);
        book(3, 1.0, "D".repeat(40)); language(3, com.rlibanez.eplsync.model.enums.Language.INGLES);
        history(4, 1.0, "E".repeat(40), DownloadStatus.DOWNLOADED);
        book(4, 2.0, "F".repeat(40)); language(4, com.rlibanez.eplsync.model.enums.Language.INGLES);
    }

    BulkStore.View immediateJob(String hashes,PreviousVersions policy) {
        ownerStore.clear();
        var owner=ownerStore.initialize("cleanupowner","owner@example.org","a permanent cleanup password","a permanent cleanup password");
        book(1L,1.2,hashes);
        return com.rlibanez.eplsync.events.EventContext.withActor(new com.rlibanez.eplsync.events.EventContext.Actor(owner.id(),owner.username(),"USER"),
            () -> selectedSender.create(new SelectedUpdateSender.Request(List.of(1L),List.of(DownloadStatus.DOWNLOADED),policy,true,null,2,10,"0ms",MultipleHashes.ALL,CleanupTiming.IMMEDIATE)));
    }
    @Test void immediateCleanupRequiresEveryAcceptedTargetAndSurvivesDeletionOfItsJob() {
        var job=immediateJob(NEW+","+OTHER,PreviousVersions.REMOVE_TORRENT);
        assertThat(job.cleanupTiming()).isEqualTo(CleanupTiming.IMMEDIATE);
        var sends=items.findByJobIdOrderByPosition(job.jobId(),org.springframework.data.domain.PageRequest.of(0,20)).getContent();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old.epub"),remote(NEW,DownloadStatus.DOWNLOADING,"/new.epub"),remote(OTHER,DownloadStatus.DOWNLOADING,"/other.epub"))).thenReturn(List.of(remote(NEW,DownloadStatus.DOWNLOADING,"/new.epub"),remote(OTHER,DownloadStatus.DOWNLOADING,"/other.epub")));
        assertThat(queue.runImmediate(100,Instant.now())).isZero();
        bulk.finish(sends.get(0).getId(),BulkItem.State.ACCEPTED,null,false,false);
        assertThat(only(job.jobId()).getReplacementAccepted()).isFalse();
        assertThat(queue.runImmediate(100,Instant.now())).isZero();
        bulk.finish(sends.get(1).getId(),BulkItem.State.ALREADY_EXISTS,null,false,false);
        assertThat(only(job.jobId()).getReplacementAccepted()).isTrue();
        items.deleteAll();jobs.deleteById(job.jobId());plans.deleteById(job.jobId());
        assertThat(queue.runImmediate(100,Instant.now())).isEqualTo(1);
        verify(stubClient).deleteTorrent(OLD,false);
        assertThat(cleanup.findByJobIdOrderByEplIdAsc(job.jobId()).getFirst().getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
        assertThat(event.actor().username()).isEqualTo("cleanupowner");
        assertThat(event.details()).containsEntry("deleteFiles",false);
    }
    @Test void failedReplacementNeverTriggersImmediateRemoval() {
        var job=immediateJob(NEW,PreviousVersions.REMOVE_TORRENT);
        var send=items.findByJobIdOrderByPosition(job.jobId(),org.springframework.data.domain.PageRequest.of(0,20)).getContent().getFirst();
        bulk.finish(send.getId(),BulkItem.State.FAILED,"rejected",false,false);
        assertThat(queue.runImmediate(100,Instant.now())).isZero();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old.epub"),remote(NEW,DownloadStatus.DOWNLOADING,"/new.epub")));
        cleaner.synchronize(false,false);
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
    }
    @Test void immediateFileRemovalStillBlocksSharedPathsAndIsNotRetriedWithinTheSameCycle() {
        var job=immediateJob(NEW,PreviousVersions.REMOVE_TORRENT_AND_FILES);
        var send=items.findByJobIdOrderByPosition(job.jobId(),org.springframework.data.domain.PageRequest.of(0,20)).getContent().getFirst();
        bulk.finish(send.getId(),BulkItem.State.ACCEPTED,null,false,false);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/shared.epub"),remote(NEW,DownloadStatus.DOWNLOADING,"/shared.epub")));
        var cycle=Instant.ofEpochMilli(System.currentTimeMillis());
        assertThat(queue.runImmediate(100,cycle)).isEqualTo(1);
        assertThat(queue.runImmediate(100,cycle)).isZero();
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.BLOCKED);
    }

    @Test void synchronizationAuditsEarlyFailuresWithoutDuplicatingTrackingFailures() {
        var filter=new com.rlibanez.eplsync.events.EventJournal.Filter(
            com.rlibanez.eplsync.events.EventJournal.Category.TORRENT,null,null,null,null,"SYNC_PREVIEW",null);
        for (boolean early : List.of(true,false)) {
            long before=journal.cursor();
            if (early) when(stubClient.listTorrents()).thenThrow(new IllegalStateException("private network details"));
            else { reset(stubClient); when(stubClient.listTorrents()).thenReturn(null); }
            assertThatThrownBy(() -> cleaner.synchronize(true,false)).isInstanceOf(RuntimeException.class);
            var entries=journal.search(filter,0,20).items().stream().filter(event -> event.id()>before).toList();
            assertThat(entries).hasSize(2);
            assertThat(entries).extracting((com.rlibanez.eplsync.events.EventJournal.Entry entryValue) -> java.util.Objects.requireNonNull(entryValue).outcome())
                .containsExactly(com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED,com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED);
            assertThat(entries).extracting((com.rlibanez.eplsync.events.EventJournal.Entry entryValue) -> java.util.Objects.requireNonNull(entryValue).operationId()).containsOnly(entries.getFirst().operationId());
            assertThat(entries.getFirst().details()).containsEntry("dryRun",true).containsEntry("reason","Ha ocurrido un error inesperado");
        }
    }

    @Test void automaticBatchSharesSnapshotAndObservesOnlyRelatedRecords() {
        var first=create(PreviousVersions.REMOVE_TORRENT);
        history(2,1.0,OTHER,DownloadStatus.DOWNLOADED);
        book(2,2.0,"D".repeat(40));
        create(PreviousVersions.REMOVE_TORRENT);
        var target=history(1,1.2,NEW,DownloadStatus.SUBMITTED);
        var unrelated=history(99,1.0,"E".repeat(40),DownloadStatus.SUBMITTED);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,null),
            remote(NEW,DownloadStatus.DOWNLOADING,null),remote(OTHER,DownloadStatus.DOWNLOADED,null),
            remote("D".repeat(40),DownloadStatus.DOWNLOADING,null)));
        service.exclusiveClient(adapter -> queue.execute(cleanup.findAll(),adapter,adapter.listTorrents(),false,false));
        verify(stubClient,times(1)).listTorrents();
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        assertThat(downloads.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.DOWNLOADING);
        assertThat(downloads.findById(unrelated.getId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
        assertThat(downloads.findById(unrelated.getId()).orElseThrow().getLastCheckedAt()).isNull();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(NEW,DownloadStatus.DOWNLOADED,null)));
        service.exclusiveClient(adapter -> queue.execute(cleanup.findByJobIdOrderByEplIdAsc(first.jobId()),adapter,adapter.listTorrents(),false,false));
        var completed=downloads.findById(target.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test void manualSyncReusesSnapshotForCleanupAndRecordsCompletion() {
        var job=create(PreviousVersions.REMOVE_TORRENT);
        var target=history(1,1.2,NEW,DownloadStatus.SUBMITTED);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),
            remote(NEW,DownloadStatus.DOWNLOADED,"/new"))).thenReturn(List.of(remote(NEW,DownloadStatus.DOWNLOADED,"/new")));
        var report=cleaner.synchronize(false,true);
        var details=webContext.getBean(com.rlibanez.eplsync.reports.ReportSnapshots.class)
            .page(report.detailsId(),"books",0,1000,List.of(),java.util.Set.of(),List.of(),
                com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.SyncItem.class).items();
        assertThat(details.stream().filter(i -> OLD.equals(i.hash())).findFirst().orElseThrow().resultingStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
        assertThat(report.cleanup()).hasSize(1);
        assertThat(report.cleanup().getFirst().state()).isEqualTo(UpdateCleanup.State.REMOVED);
        assertThat(report.cleanup().getFirst().hash()).isEqualTo(OLD);
        assertThat(downloads.findById(report.cleanup().getFirst().downloadId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.NOT_FOUND);
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
        assertThat(event.outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        assertThat(event.actor().username()).isEqualTo("user");
        assertThat(event.details()).containsEntry("confirmed",true).containsEntry("deleteFiles",false);
        verify(stubClient,times(2)).listTorrents(); // initial snapshot + deletion confirmation
        verify(stubClient).deleteTorrent(OLD,false);
        var observed=downloads.findById(target.getId()).orElseThrow();
        assertThat(observed.getStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
        assertThat(observed.getCompletedAt()).isNotNull();
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
    }

    @Test void manualCleanupReportsUnconfirmedDeletionWithoutRetrying() {
        create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),remote(NEW,DownloadStatus.DOWNLOADED,"/new")));
        var report=cleaner.synchronize(false,false);
        assertThat(report.items()).isNull();
        assertThat(report.cleanup().getFirst().state()).isEqualTo(UpdateCleanup.State.REQUESTED);
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
        assertThat(event.outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.PARTIAL);
        assertThat(event.details()).containsEntry("confirmed",false).containsEntry("requested",true);
        cleaner.synchronize(false,false);
        verify(stubClient,times(1)).deleteTorrent(OLD,false);
    }

    @Test void manualCleanupAuditsUnavailableConfirmation() {
        create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),remote(NEW,DownloadStatus.DOWNLOADED,"/new")))
            .thenThrow(new IllegalStateException("private technical detail"));
        var report=cleaner.synchronize(false,false);
        assertThat(report.cleanup().getFirst().state()).isEqualTo(UpdateCleanup.State.REQUESTED);
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
        assertThat(event.outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.PARTIAL);
        assertThat(event.details()).containsEntry("confirmed",false).containsEntry("deleteFiles",true);
        assertThat(event.details().toString()).doesNotContain("private technical detail");
    }

    @Test void manualCleanupAuditsBlockedFilesWithExecutingUser() {
        create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/shared"),remote(NEW,DownloadStatus.DOWNLOADED,"/shared")));
        var report=com.rlibanez.eplsync.events.EventContext.withActor(
            new com.rlibanez.eplsync.events.EventContext.Actor("executor-id","executor","USER"),() -> cleaner.synchronize(false,false));
        assertThat(report.cleanup().getFirst().state()).isEqualTo(UpdateCleanup.State.BLOCKED);
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
        assertThat(event.actor().username()).isEqualTo("executor");
        assertThat(event.outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED);
        assertThat(event.details()).containsEntry("requested",false).containsEntry("deleteFiles",true);
    }

    @Test void manualCleanupAuditsFailedDeleteWithoutLeakingException() {
        create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),remote(NEW,DownloadStatus.DOWNLOADED,"/new")));
        doThrow(new IllegalStateException("secret credential")).when(stubClient).deleteTorrent(OLD,false);
        var report=cleaner.synchronize(false,false);
        assertThat(report.cleanup().getFirst().state()).isEqualTo(UpdateCleanup.State.REQUESTED);
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
        assertThat(event.outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.PARTIAL);
        assertThat(event.details()).containsEntry("confirmed",false);
        assertThat(event.details().toString()).doesNotContain("secret credential");
    }

    @Test void syncPreviewNeverCleansAndFailedSyncNeverDeletes() {
        var job=create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,null),remote(NEW,DownloadStatus.DOWNLOADED,null)));
        cleaner.synchronize(true,false);
        verify(stubClient,times(1)).listTorrents();
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        when(stubClient.listTorrents()).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> cleaner.synchronize(false,false)).isInstanceOf(IllegalStateException.class);
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
    }

    @Test void manualSyncWithoutCleanupPermissionOnlySynchronizes() {
        var job=create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,null),remote(NEW,DownloadStatus.DOWNLOADED,null)));
        var previous=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var restricted=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        restricted.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reader",null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_SYNC"))));
        try {
            org.springframework.security.core.context.SecurityContextHolder.setContext(restricted);
            cleaner.synchronize(false,false);
        } finally {org.springframework.security.core.context.SecurityContextHolder.setContext(previous);}
        verify(stubClient,times(1)).listTorrents();
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
    }

    @Test void automaticQueueLimitsRecordsAndRotatesBlockedRequestsWithoutJobDependency() {
        history(1,0.8,OTHER,DownloadStatus.DOWNLOADED);
        history(1,0.9,"D".repeat(40),DownloadStatus.DOWNLOADED);
        var job=com.rlibanez.eplsync.events.EventContext.withActor(new com.rlibanez.eplsync.events.EventContext.Actor("owner","owner","USER"),() ->
            selectedSender.create(new SelectedUpdateSender.Request(List.of(1L),List.of(DownloadStatus.DOWNLOADED),PreviousVersions.REMOVE_TORRENT,null,null,1,10,"0ms",MultipleHashes.ALL)));
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,null),remote(OTHER,DownloadStatus.DOWNLOADED,null),
            remote("D".repeat(40),DownloadStatus.DOWNLOADED,null),remote(NEW,DownloadStatus.DOWNLOADING,null)));
        // Frozen cleanup requests no longer need either the job or its commands for execution.
        items.deleteAll(); jobs.deleteById(job.jobId()); plans.deleteById(job.jobId());
        for(int i=0;i<3;i++) queue.runAutomatic(1);
        verify(stubClient,times(3)).listTorrents();
        assertThat(cleanup.findAll()).hasSize(3).allMatch(e -> e.getLastCheckedAt()!=null);
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
    }

    @Test void destinationIdentityIsScopedToTheOperationAndRestoredAfterFailure() {
        var original=tracking.instanceId();
        var foreign=tracking.withInstance("stub","http://foreign:8080",tracking::instanceId);
        assertThat(foreign).isNotEqualTo(original);
        assertThatThrownBy(() -> tracking.withInstance("stub","http://foreign:8080",() -> {
            assertThat(tracking.instanceId()).isEqualTo(foreign);throw new IllegalStateException("stop");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tracking.instanceId()).isEqualTo(original);
    }

    @Test void disablingPeriodicCleanupDoesNotPreventManualRemovalAndResolvesAllRequests() {
        var first=create(PreviousVersions.REMOVE_TORRENT);
        var finished=jobs.findById(first.jobId()).orElseThrow();finished.setState(BulkJob.State.COMPLETED);jobs.save(finished);
        var second=create(PreviousVersions.REMOVE_TORRENT);
        var automatic=new AutomaticUpdateCleanup(queue,bulk);
        org.springframework.test.util.ReflectionTestUtils.setField(automatic,"enabled",false);
        automatic.tick(); verify(stubClient,never()).listTorrents();
        var row=downloads.findAll().getFirst();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),remote(NEW,DownloadStatus.DOWNLOADING,"/new"))).thenReturn(List.of(remote(NEW,DownloadStatus.DOWNLOADING,"/new")));
        var result=historyActions.remove(new HistoryActions.Selection(1L,List.of(row.getId()),false,false));
        assertThat(result.items()).singleElement().satisfies(r -> {assertThat(r.status()).isEqualTo(DownloadStatus.NOT_FOUND);assertThat(r.cleanupState()).isEqualTo("REMOVED");});
        assertThat(only(first.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        assertThat(only(second.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        clearInvocations(stubClient);
        queue.runAutomatic(100);verify(stubClient,never()).listTorrents();
    }

    @Test void deletingHistoryPreservesCleanupAndItsExecutionWithoutCallingClient() {
        var job=immediateJob(NEW,PreviousVersions.REMOVE_TORRENT_AND_FILES);
        var send=items.findByJobIdOrderByPosition(job.jobId(),org.springframework.data.domain.PageRequest.of(0,20)).getContent().getFirst();
        bulk.finish(send.getId(),BulkItem.State.ACCEPTED,null,false,false);
        var row=downloads.findAll().getFirst();
        var selection=new HistoryActions.HistoryDeletion(1L,List.of(row.getId()),true);
        clearInvocations(stubClient);
        assertThat(historyActions.deleteHistory(selection,true).pendingCleanup()).isEqualTo(1);
        assertThat(downloads.existsById(row.getId())).isTrue();
        var result=historyActions.deleteHistory(selection,false);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(downloads.existsById(row.getId())).isFalse();
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        verifyNoInteractions(stubClient);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old.epub"),remote(NEW,DownloadStatus.DOWNLOADING,"/new.epub"))).thenReturn(List.of(remote(NEW,DownloadStatus.DOWNLOADING,"/new.epub")));
        queue.runImmediate(100,Instant.now());
        verify(stubClient).deleteTorrent(OLD,true);
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"DELETE_DOWNLOAD_HISTORY",null),0,20).items().getFirst();
        assertThat(event.details()).containsEntry("historyDeleted",1);
    }
    @Test void combinedRemovalDeletesOnlyConfirmedHistoryAndPreservesBlockedRecords() {
        var old=downloads.findAll().getFirst();
        var blocked=history(1,1.0,OTHER,DownloadStatus.DOWNLOADED);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),remote(OTHER,DownloadStatus.DOWNLOADED,"/shared"),remote(NEW,DownloadStatus.DOWNLOADED,"/shared"))).thenReturn(List.of(remote(OTHER,DownloadStatus.DOWNLOADED,"/shared"),remote(NEW,DownloadStatus.DOWNLOADED,"/shared")));
        var result=historyActions.removeRecords(new HistoryActions.Removal(1L,List.of(old.getId(),blocked.getId()),true,"files",true,true));
        assertThat(result.items()).filteredOn(r -> r.id().equals(old.getId())).singleElement().satisfies(r -> assertThat(r.historyDeleted()).isTrue());
        assertThat(result.items()).filteredOn(r -> r.id().equals(blocked.getId())).singleElement().satisfies(r -> {assertThat(r.historyDeleted()).isFalse();assertThat(r.cleanupState()).isEqualTo("BLOCKED");});
        assertThat(downloads.existsById(old.getId())).isFalse();
        assertThat(downloads.existsById(blocked.getId())).isTrue();
        verify(stubClient).deleteTorrent(OLD,true);
        verify(stubClient,never()).deleteTorrent(OTHER,true);
    }
    @Test void combinedRemovalRetainsHistoryWhenAbsenceIsUnconfirmed() {
        var old=downloads.findAll().getFirst();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old")));
        var result=historyActions.removeRecords(new HistoryActions.Removal(1L,List.of(old.getId()),true,"torrent",true,false));
        assertThat(result.items()).singleElement().satisfies(r -> {assertThat(r.historyDeleted()).isFalse();assertThat(r.cleanupState()).isEqualTo("REQUESTED");});
        assertThat(downloads.existsById(old.getId())).isTrue();
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"BOOK_HISTORY_READ","TORRENT_CLEANUP"})
    void combinedRemovalChecksHistoryPermissionBeforeContactingClient() {
        var old=downloads.findAll().getFirst();clearInvocations(stubClient);
        assertThatThrownBy(() -> historyActions.removeRecords(new HistoryActions.Removal(1L,List.of(old.getId()),true,"torrent",true,false)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(stubClient);
        assertThat(downloads.existsById(old.getId())).isTrue();
    }
    @Test void synchronizationRediscoversCurrentHashButDoesNotRestoreOldHistory() {
        var current=history(1,1.2,NEW,DownloadStatus.SUBMITTED);
        var ids=downloads.findAll().stream().map((DownloadRecord entryValue) -> java.util.Objects.requireNonNull(entryValue).getId()).toList();
        historyActions.deleteHistory(new HistoryActions.HistoryDeletion(1L,ids,true),false);
        assertThat(downloads.count()).isZero();
        tracking.sync(() -> List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old"),remote(NEW,DownloadStatus.DOWNLOADED,"/new")),false,false);
        assertThat(downloads.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHash()).isEqualTo(NEW);
            assertThat(row.getId()).isNotEqualTo(current.getId());
            assertThat(row.getOrigin()).isEqualTo(DownloadRecord.Origin.DISCOVERED);
            assertThat(row.getRequestedAt()).isNull();
            assertThat(row.getSubmittedAt()).isNull();
        });
    }
    @Test void historyDeletionValidatesAllRecordsAndRequiresConfirmation() {
        var row=downloads.findAll().getFirst();
        assertThatThrownBy(() -> historyActions.deleteHistory(new HistoryActions.HistoryDeletion(1L,List.of(row.getId()),false),false)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(() -> historyActions.deleteHistory(new HistoryActions.HistoryDeletion(1L,List.of(row.getId(),"missing"),true),false)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(() -> historyActions.deleteHistory(new HistoryActions.HistoryDeletion(2L,List.of(row.getId()),true),false)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(downloads.existsById(row.getId())).isTrue();
        // History may be deleted even after changing the configured client destination.
        row.setClientInstanceId("previous-client");downloads.save(row);
        assertThat(historyActions.deleteHistory(new HistoryActions.HistoryDeletion(1L,List.of(row.getId()),true),false).deleted()).isEqualTo(1);
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"BOOK_HISTORY_READ"})
    void historyDeletionRequiresDedicatedPermission() throws Exception {
        var row=downloads.findAll().getFirst();
        mvc.perform(post("/api/torrent/downloads/delete-history").contentType("application/json").content("{\"eplId\":1,\"ids\":[\""+row.getId()+"\"],\"confirm\":true}"))
            .andExpect(status().isForbidden());
        assertThat(downloads.existsById(row.getId())).isTrue();
    }
    @Test void selectedRefreshOnlyObservesSelectedHashesAndConfirmsAbsenceWithoutDeleting() {
        var first=create(PreviousVersions.REMOVE_TORRENT);
        var row=downloads.findAll().getFirst();var untouched=history(1,1.2,NEW,DownloadStatus.SUBMITTED);
        when(stubClient.listTorrents(anySet())).thenReturn(List.of());
        var result=historyActions.refresh(new HistoryActions.Selection(1L,List.of(row.getId()),null,null));
        assertThat(result.items()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(DownloadStatus.NOT_FOUND));
        verify(stubClient).listTorrents(Set.of(OLD));verify(stubClient,never()).listTorrents();verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        assertThat(only(first.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        assertThat(downloads.findById(untouched.getId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.SUBMITTED);
    }

    @Test void manualFileRemovalRequiresConfirmationAndBlocksSharedPaths() {
        var row=downloads.findAll().getFirst();
        assertThatThrownBy(() -> historyActions.remove(new HistoryActions.Selection(1L,List.of(row.getId()),true,false))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        verify(stubClient,never()).listTorrents();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/books"),remote(NEW,DownloadStatus.DOWNLOADED,"/books/new")));
        var result=historyActions.remove(new HistoryActions.Selection(1L,List.of(row.getId()),true,true));
        assertThat(result.items()).singleElement().satisfies(r -> assertThat(r.cleanupState()).isEqualTo("BLOCKED"));
        verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
    }

    @Test void manualUncertainRemovalIsNeverAutomaticallyRepeated() {
        var row=downloads.findAll().getFirst();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,null)));
        doThrow(new IllegalStateException("upstream secret")).when(stubClient).deleteTorrent(OLD,false);
        var selection=new HistoryActions.Selection(1L,List.of(row.getId()),false,false);
        assertThat(historyActions.remove(selection).items()).singleElement().satisfies(r -> assertThat(r.cleanupState()).isEqualTo("REQUESTED"));
        historyActions.remove(selection);
        verify(stubClient,times(1)).deleteTorrent(OLD,false);
        assertThat(cleanup.findAll().toString()).doesNotContain("upstream secret");
    }

    @Test void cancellationStopsUnrequestedCleanupButKeepsUncertainConfirmation() {
        history(1,0.9,OTHER,DownloadStatus.DOWNLOADED);
        var job=create(PreviousVersions.REMOVE_TORRENT);
        var rows=cleanup.findByJobIdOrderByEplIdAsc(job.jobId());
        rows.getFirst().setState(UpdateCleanup.State.REQUESTED);cleanup.save(rows.getFirst());
        bulk.control(job.jobId(),"cancel");
        assertThat(cleanup.findByJobIdOrderByEplIdAsc(job.jobId())).extracting((UpdateCleanup entryValue) -> java.util.Objects.requireNonNull(entryValue).getState())
            .containsExactlyInAnyOrder(UpdateCleanup.State.REQUESTED,UpdateCleanup.State.CANCELLED);
    }

    @Test void selectedActionsRejectOtherBooksDestinationsAndMissingPermissions() throws Exception {
        var row=downloads.findAll().getFirst();
        assertThatThrownBy(() -> historyActions.refresh(new HistoryActions.Selection(2L,List.of(row.getId()),null,null))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        properties.effective().setBaseUrl("http://other:8080");
        assertThatThrownBy(() -> historyActions.remove(new HistoryActions.Selection(1L,List.of(row.getId()),false,false))).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        verify(stubClient,never()).listTorrents();verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        var previous=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reader",null,List.of(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("BOOK_HISTORY_READ"))));
        try {
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            mvc.perform(post("/api/torrent/downloads/remove-selected").contentType("application/json").content("{\"eplId\":1,\"ids\":[\""+row.getId()+"\"]}"))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/torrent/downloads/refresh-selected").contentType("application/json").content("{\"eplId\":1,\"ids\":[\""+row.getId()+"\"]}"))
                .andExpect(status().isForbidden());
        } finally {org.springframework.security.core.context.SecurityContextHolder.setContext(previous);}
    }

    @Test void selectedUpdatesPersistPolicyAndAutomaticCleanupWaitsForCompletion() {
        var request=new SelectedUpdateSender.Request(List.of(1L),List.of(DownloadStatus.DOWNLOADED),PreviousVersions.REMOVE_TORRENT_AND_FILES,true,null,2,10,"0ms",MultipleHashes.ALL);
        var job=com.rlibanez.eplsync.events.EventContext.withActor(new com.rlibanez.eplsync.events.EventContext.Actor("owner","owner","USER"),() -> selectedSender.create(request));
        assertThat(plans.findById(job.jobId()).orElseThrow().getAutomaticCleanup()).isTrue();
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        var automatic=new AutomaticUpdateCleanup(queue,bulk);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/books/old.epub"),remote(NEW,DownloadStatus.DOWNLOADING,"/books/new.epub")));
        automatic.tick();verify(stubClient,never()).deleteTorrent(anyString(),anyBoolean());
        // Finish dispatch and allow the downloaded replacement to trigger cleanup.
        items.findByJobIdOrderByPosition(job.jobId(),org.springframework.data.domain.PageRequest.of(0,20)).forEach(item -> {item.setState(BulkItem.State.ACCEPTED);items.save(item);});
        automatic.tick(); // wraps the polling cursor
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/books/old.epub"),remote(NEW,DownloadStatus.DOWNLOADED,"/books/new.epub"))).thenReturn(List.of(remote(NEW,DownloadStatus.DOWNLOADED,"/books/new.epub")));
        automatic.tick();verify(stubClient).deleteTorrent(OLD,true);
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
    }
    @Test void selectedUpdatesValidateFileRemovalConfirmation() {
        var denied=new SelectedUpdateSender.Request(List.of(1L),List.of(DownloadStatus.DOWNLOADED),PreviousVersions.REMOVE_TORRENT_AND_FILES,false,null,2,10,"0ms",MultipleHashes.ALL);
        assertThatThrownBy(() -> selectedSender.create(denied)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(jobs.count()).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"IMMEDIATE,deleted", "IMMEDIATE,disabled", "IMMEDIATE,revoked", "AFTER_DOWNLOAD,deleted", "AFTER_DOWNLOAD,disabled", "AFTER_DOWNLOAD,revoked"})
    void persistedCleanupSurvivesOwnerChanges(CleanupTiming timing,String change) {
        ownerStore.clear();
        var owner=ownerStore.initialize("cleanupowner","owner@example.org","a permanent cleanup password","a permanent cleanup password");
        ownerStore.create("remainingadmin","remaining@example.org","ADMIN",owner.id());
        var job=com.rlibanez.eplsync.events.EventContext.withActor(new com.rlibanez.eplsync.events.EventContext.Actor(owner.id(),owner.username(),"USER"),
            () -> selectedSender.create(new SelectedUpdateSender.Request(List.of(1L),List.of(DownloadStatus.DOWNLOADED),PreviousVersions.REMOVE_TORRENT_AND_FILES,true,null,2,10,"0ms",MultipleHashes.ALL,timing)));
        var send=items.findByJobIdOrderByPosition(job.jobId(),org.springframework.data.domain.PageRequest.of(0,20)).getContent().getFirst();
        bulk.finish(send.getId(),BulkItem.State.ACCEPTED,null,false,false);
        switch(change) {
            case "deleted" -> ownerStore.delete(owner.id());
            case "disabled" -> ownerStore.update(owner.id(),"ADMIN","DISABLED",Map.of(),owner.id());
            case "revoked" -> ownerStore.update(owner.id(),"USER","ACTIVE",Map.of(),owner.id());
            default -> throw new AssertionError(change);
        }
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        // No logged-in user or current privileges are needed to execute an authorized queued request.
        var context=org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        try {
            var state=timing==CleanupTiming.IMMEDIATE ? DownloadStatus.DOWNLOADING : DownloadStatus.DOWNLOADED;
            when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD,DownloadStatus.DOWNLOADED,"/old.epub"),remote(NEW,state,"/new.epub"))).thenReturn(List.of(remote(NEW,state,"/new.epub")));
            if(timing==CleanupTiming.IMMEDIATE) queue.runImmediate(100,Instant.now());
            else queue.runAutomatic(100);
            verify(stubClient).deleteTorrent(OLD,true);
            assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
            var event=journal.search(new com.rlibanez.eplsync.events.EventJournal.Filter(null,null,null,null,null,"CLEANUP",null),0,20).items().getFirst();
            assertThat(event.actor().id()).isEqualTo(owner.id());
            assertThat(event.actor().username()).isEqualTo("cleanupowner");
        } finally {org.springframework.security.core.context.SecurityContextHolder.setContext(context);}
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"TORRENT_SEND","CATALOG_READ","TORRENT_SYNC"})
    void selectedUpdatesCannotRequestCleanupWithoutPermission() throws Exception {
        mvc.perform(post("/api/torrent/revision-updates/send").contentType("application/json")
            .content("{\"ids\":[1],\"states\":[\"DOWNLOADED\"],\"previousVersions\":\"removeTorrent\"}"))
            .andExpect(status().isForbidden());
        assertThat(jobs.count()).isZero();
    }

    @Test void largeSelectionsKeepPagedPreviewsAndPersistEveryFrozenCommand() {
        for (long id=100;id<305;id++) book(id,1.0,String.format("%040X",id));
        var page=planner.previewPage(null,false,MultipleHashes.ALL,UpdatePlanner.Selection.NEW,2,7);
        assertThat(page.items()).hasSize(7);
        assertThat(page.meta().totalItems()).isEqualTo(205);
        assertThat(jobs.count()).isZero();
        var job=planner.create(null,false,new UpdateRequest(PreviousVersions.KEEP,null,10,2,"0ms",MultipleHashes.ALL),UpdatePlanner.Selection.NEW);
        assertThat(items.count()).isEqualTo(205);
        assertThat(plans.findById(job.jobId()).orElseThrow().getSnapshot()).isEqualTo("{\"items\":[]}");
        assertThat(cleaner.view(job.jobId(),2,7).updates()).hasSize(7);
    }

    @Test void detailedHistorySortsBeforePaginationAndRejectsUnsupportedFields() throws Exception {
        var earliest=history(1L,2.0,"D".repeat(40),DownloadStatus.DOWNLOADED);
        earliest.setLastCheckedAt(Instant.parse("2026-10-01T12:00:00Z"));
        earliest.setCompletedAt(Instant.parse("2026-09-29T12:00:00Z"));
        earliest.setClient("qbittorrent"); earliest.setOrigin(DownloadRecord.Origin.DISCOVERED);
        downloads.save(earliest);
        var latest=history(1L,3.0,"E".repeat(40),DownloadStatus.ERROR);
        latest.setLastCheckedAt(Instant.parse("2026-10-06T12:00:00Z")); latest.setLastError("Error de conexión"); downloads.save(latest);
        var view=new CatalogDownloadViewService(downloads);
        var newest=view.history(1L,0,1,"lastCheckedAt,desc").items().getFirst();
        assertThat(newest.hash()).isEqualTo(latest.getHash());
        assertThat(newest.lastCheckedAt()).isEqualTo(latest.getLastCheckedAt());
        assertThat(newest.lastError()).isEqualTo("Error de conexión");
        var discovered=view.history(1L,1,1,"lastCheckedAt,desc").items().getFirst();
        assertThat(discovered.client()).isEqualTo("qbittorrent");
        assertThat(discovered.clientInstanceId()).isEqualTo(earliest.getClientInstanceId());
        assertThat(discovered.origin()).isEqualTo(DownloadRecord.Origin.DISCOVERED);
        assertThat(discovered.completedAt()).isEqualTo(earliest.getCompletedAt());
        assertThat(view.history(1L,0,1,"revision,asc").items().getFirst().revision()).isEqualTo(1.0);
        assertThat(view.history(1L,0,1,"revision,desc").items().getFirst().revision()).isEqualTo(3.0);
        assertThat(view.history(1L,0,1).items().getFirst().revision()).isEqualTo(3.0);
        for(String invalid:java.util.List.of("password,asc","revision,sideways","hash,asc,client"))
            assertThatThrownBy(() -> view.history(1L,0,20,invalid)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/catalog/books/1/history").param("size","1").param("sort","revision,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].hash").value(latest.getHash()))
            .andExpect(jsonPath("$.items[0].lastError").value("Error de conexión"));
    }

    @Test void catalogHistoryIsBoundedButCompleteHistoryRemainsPaged() {
        for (int index=0;index<45;index++) history(1L,2.0+index,String.format("%040X",1000+index),DownloadStatus.ERROR);
        var view=new CatalogDownloadViewService(downloads);
        var embedded=view.enrich(java.util.List.of(books.findById(1L).orElseThrow())).getFirst().download();
        assertThat(embedded.items()).hasSize(20);
        assertThat(embedded.totalItems()).isEqualTo(46);
        assertThat(embedded.statuses()).contains(DownloadStatus.DOWNLOADED,DownloadStatus.ERROR);
        var first=view.history(1L,0,20); var second=view.history(1L,1,20);
        assertThat(first.meta().totalItems()).isEqualTo(46);
        assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
        assertThat(view.history(1L,2,20).items()).hasSize(6);
    }

    @Test void filteredPreviewsSeparateNewAndUpdatedWithoutWritesOrClientCalls() throws Exception {
        mixedCatalogue();
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", true).field("language", "es"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].eplId").value(1))
                .andExpect(jsonPath("$.items[1].eplId").value(2))
                .andExpect(jsonPath("$.items[1].existingDownloads").isEmpty());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", true).field("selection", "new").field("language", "es"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eplId").value(2));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", true).field("language", "es"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eplId").value(1));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", true).field("language", "es").field("page", "1").field("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].eplId").value(2));
        assertThat(jobs.count()).isZero(); assertThat(plans.count()).isZero();
        assertThat(cleanup.count()).isZero(); assertThat(downloads.count()).isEqualTo(2);
        verifyNoInteractions(stubClient);
    }

    @Test void combinedPostCreatesOneJobWithOptionsAndOnlyOldRevisionCleanup() throws Exception {
        mixedCatalogue();
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", false).field("language", "es")
                .contentType("application/json").content("""
                {"previousVersions":"removeTorrentAndFiles", "multipleHashes":"all",
                 "batchSize":17,"concurrency":3,"interval":"250ms",
                 "options":{"start":false,"qbittorrent":{"category":"Libros","tags":["es"]}}}
                """))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.selectedBooks").value(2)).andExpect(jsonPath("$.selectedTorrents").value(2))
                .andExpect(jsonPath("$.batchSize").value(17)).andExpect(jsonPath("$.concurrency").value(3))
                .andExpect(jsonPath("$.interval").value("250ms"));
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(items.findAll()).extracting(value -> Objects.requireNonNull(value).getEplId()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(items.findAll()).allMatch(i -> i.getCommandJson().contains("\"start\":false"));
        var jobId = plans.findAll().getFirst().getJobId();
        assertThat(cleaner.view(jobId).updates()).hasSize(2);
        assertThat(cleaner.view(jobId).items()).hasSize(1);
        assertThat(only(jobId).getEplId()).isEqualTo(1);
        assertThat(cleaner.view(jobId).previousVersions()).isEqualTo(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
        // Cleaning this mixed job only deletes the old revision of book 1.
        var newRevision = remote(NEW, DownloadStatus.DOWNLOADED, "/books/new.epub");
        var newBook = remote(OTHER, DownloadStatus.DOWNLOADING, "/books/other.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, "/books/old.epub"),
                newRevision, newBook)).thenReturn(List.of(newRevision, newBook));
        cleaner.clean(jobId);
        verify(stubClient).deleteTorrent(OLD, true);
        verify(stubClient, never()).deleteTorrent(eq(OTHER), anyBoolean());
        assertThat(only(jobId).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
    }

    @Test void filteredUpdateAndNewPostsUseSeparateSelections() throws Exception {
        mixedCatalogue();
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", false).field("language", "es"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selectedBooks").value(1));
        assertThat(items.findAll()).extracting(value -> Objects.requireNonNull(value).getEplId()).containsExactly(1L);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false).field("selection", "new").field("language", "es"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selectedBooks").value(1));
        assertThat(items.findAll()).extracting(value -> Objects.requireNonNull(value).getEplId()).containsExactlyInAnyOrder(1L, 2L);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", false).field("language", "es"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selectedBooks").value(0));
        assertThat(plans.findAll()).allMatch(p -> p.getPreviousVersions() == PreviousVersions.KEEP);
    }

    @Test void anyLocalHistoryExcludesNewButOtherClientHistoryDoesNot() throws Exception {
        for (int id = 2; id <= 5; id++) book(id, 1.0, String.format("%040X", id));
        history(2, 1.0, String.format("%040X", 2), DownloadStatus.ERROR);
        history(3, 1.0, String.format("%040X", 3), DownloadStatus.NOT_FOUND);
        history(4, 1.0, String.format("%040X", 4), DownloadStatus.UNKNOWN);
        var foreign = history(5, 1.0, String.format("%040X", 5), DownloadStatus.DOWNLOADED);
        foreign.setClientInstanceId("another-instance"); downloads.save(foreign);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", true).field("selection", "new"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eplId").value(5));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", true).field("includeNotFound", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
    }

    @Test void combinedRequestRespectsHashPoliciesForBothGroups() {
        book(1, 1.2, NEW + "," + OTHER);
        book(2, 1.0, "D".repeat(40) + "," + "E".repeat(40));
        var filter = new com.rlibanez.eplsync.filter.CatalogBookFilter();
        assertThat(planner.preview(filter, false, MultipleHashes.SKIP, UpdatePlanner.Selection.BOTH)).isEmpty();
        assertThat(planner.preview(filter, false, MultipleHashes.FIRST, UpdatePlanner.Selection.BOTH))
                .allMatch(c -> c.targetHashes().size() == 1);
        assertThat(planner.preview(filter, false, MultipleHashes.ALL, UpdatePlanner.Selection.BOTH))
                .hasSize(2).allMatch(c -> c.targetHashes().size() == 2);
    }

    @Test void combinedAndNewRejectInvalidFiltersWithoutEnqueuing() throws Exception {
        for (String path : List.of("/api/torrent/refresh", "/api/torrent/updates", "/api/torrent/books?selection=new")) {
            mvc.perform(post(path).param("langauge", "es")).andExpect(status().isBadRequest());
            mvc.perform(post(path).param("language", "not-a-language")).andExpect(status().isBadRequest());
            mvc.perform(post(path).param("eplId", "0")).andExpect(status().isBadRequest());
            mvc.perform(post(path).param("language", "es", "en")).andExpect(status().isBadRequest());
            mvc.perform(post(path).param("size", "1")).andExpect(status().isBadRequest());
        }
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/books", false).field("selection", "unknown")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", false).contentType("application/json")
                .content("{\"options\":{\"hash\":\"" + NEW + "\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/refresh", false).contentType("application/json")
                .content("{\"concurrency\":0}"))
                .andExpect(status().isBadRequest());
        assertThat(jobs.count()).isZero(); assertThat(plans.count()).isZero(); assertThat(cleanup.count()).isZero();
    }

    @Test void combinedSelectionIsReservedAcrossConcurrentRequestsAndPausedJobs() throws Exception {
        book(2, 1.0, OTHER);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<BulkStore.View> task = () -> {
                synchronized (bulk) {
                    return planner.create(new com.rlibanez.eplsync.filter.CatalogBookFilter(), false, null, UpdatePlanner.Selection.BOTH);
                }
            };
            var first = executor.submit(task); var second = executor.submit(task);
            var one = first.get(5, TimeUnit.SECONDS); var two = second.get(5, TimeUnit.SECONDS);
            assertThat(one.selectedBooks() + two.selectedBooks()).isEqualTo(2);
            var job = one.selectedBooks() == 2 ? one : two;
            bulk.control(job.jobId(), "pause");
            assertThat(planner.preview(new com.rlibanez.eplsync.filter.CatalogBookFilter(), false, null,
                    UpdatePlanner.Selection.BOTH)).isEmpty();
        }
    }

    @Test void globalCleanupUsesTwoSnapshotsAndExcludesKeptAndRemovedJobs() throws Exception {
        var first = create(PreviousVersions.REMOVE_TORRENT);
        history(2, 1.0, OTHER, DownloadStatus.DOWNLOADED); book(2, 1.2, "D".repeat(40));
        var second = create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        history(3, 1.0, "E".repeat(40), DownloadStatus.DOWNLOADED); book(3, 1.2, "F".repeat(40));
        create(PreviousVersions.KEEP);
        var a = remote(OLD, DownloadStatus.DOWNLOADED, "/books/a.epub");
        var b = remote(NEW, DownloadStatus.DOWNLOADED, "/books/b.epub");
        var c = remote(OTHER, DownloadStatus.DOWNLOADED, "/books/c.epub");
        var d = remote("D".repeat(40), DownloadStatus.DOWNLOADED, "/books/d.epub");
        when(stubClient.listTorrents()).thenReturn(List.of(a, b, c, d)).thenReturn(List.of(b, d));
        mvc.perform(post("/api/torrent/updates/cleanup"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedJobs").value(2))
                .andExpect(jsonPath("$.checked").value(2)).andExpect(jsonPath("$.removed").value(2))
                .andExpect(jsonPath("$.failedJobs").value(0)).andExpect(jsonPath("$.jobs.length()").value(2));
        verify(stubClient, times(2)).listTorrents();
        verify(stubClient).deleteTorrent(OLD, false);
        verify(stubClient).deleteTorrent(OTHER, true);
        assertThat(only(first.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        assertThat(only(second.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        clearInvocations(stubClient);
        assertThat(cleaner.cleanAll(false).selectedJobs()).isZero();
        verifyNoInteractions(stubClient);
    }

    @Test void globalCleanupRechecksWaitingBlockedAndUnconfirmedWithoutRepeatingWrites() {
        var first = create(PreviousVersions.REMOVE_TORRENT);
        history(2, 1.0, OTHER, DownloadStatus.DOWNLOADED); book(2, 1.2, "D".repeat(40));
        var second = create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        var entry = only(first.jobId()); entry.setState(UpdateCleanup.State.REQUESTED); cleanup.save(entry);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, "/a"),
                remote(NEW, DownloadStatus.DOWNLOADED, "/b"), remote(OTHER, DownloadStatus.DOWNLOADED, "/c"),
                remote("D".repeat(40), DownloadStatus.DOWNLOADING, "/d")));
        var result = cleaner.cleanAll(false);
        assertThat(result.requested()).isEqualTo(1); assertThat(result.waiting()).isEqualTo(1);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
        verify(stubClient, times(1)).listTorrents();
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, "/a"),
                remote(NEW, DownloadStatus.DOWNLOADED, "/b"), remote(OTHER, DownloadStatus.DOWNLOADED, "/c"),
                remote("D".repeat(40), DownloadStatus.DOWNLOADED, "/c")));
        result = cleaner.cleanAll(false);
        assertThat(result.blocked()).isEqualTo(1);
        cleaner.cleanAll(true);
        verify(stubClient).deleteTorrent(OLD, false);
        verify(stubClient, never()).deleteTorrent(eq(OTHER), anyBoolean());
        assertThat(only(second.jobId()).getState()).isEqualTo(UpdateCleanup.State.BLOCKED);
    }

    @Test void globalCleanupReportsWrongDestinationAndContinuesAfterDeleteFailure() {
        var first = create(PreviousVersions.REMOVE_TORRENT);
        history(2, 1.0, OTHER, DownloadStatus.DOWNLOADED); book(2, 1.2, "D".repeat(40));
        var second = create(PreviousVersions.REMOVE_TORRENT);
        history(3, 1.0, "E".repeat(40), DownloadStatus.DOWNLOADED); book(3, 1.2, "F".repeat(40));
        var third = create(PreviousVersions.REMOVE_TORRENT);
        var foreign = plans.findById(third.jobId()).orElseThrow(); foreign.setClientInstanceId("different"); plans.save(foreign);
        var b = remote(NEW, DownloadStatus.DOWNLOADED, null);
        var d = remote("D".repeat(40), DownloadStatus.DOWNLOADED, null);
        var a = remote(OLD, DownloadStatus.DOWNLOADED, null);
        when(stubClient.listTorrents()).thenReturn(List.of(a, b, remote(OTHER, DownloadStatus.DOWNLOADED, null), d)).thenReturn(List.of(a, b, d));
        doThrow(new IllegalStateException("secret remote body")).when(stubClient).deleteTorrent(OLD, false);
        var result = cleaner.cleanAll(false);
        assertThat(result.selectedJobs()).isEqualTo(3); assertThat(result.failedJobs()).isEqualTo(2);
        assertThat(result.removed()).isEqualTo(1); assertThat(result.requested()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("secret remote body");
        assertThat(only(first.jobId()).getState()).isEqualTo(UpdateCleanup.State.REQUESTED);
        assertThat(only(second.jobId()).getState()).isEqualTo(UpdateCleanup.State.REMOVED);
        assertThat(only(third.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        verify(stubClient, never()).deleteTorrent(eq("E".repeat(40)), anyBoolean());
    }

    @Test void globalCleanupProtectsTargetsOfOtherPlansAndDeduplicatesDeletes() {
        var first = create(PreviousVersions.REMOVE_TORRENT);
        var completedJob=jobs.findById(first.jobId()).orElseThrow();completedJob.setState(BulkJob.State.COMPLETED);jobs.save(completedJob);
        history(1, 1.2, NEW, DownloadStatus.DOWNLOADED); book(1, 1.3, OTHER);
        var second = create(PreviousVersions.REMOVE_TORRENT);
        var a = remote(OLD, DownloadStatus.DOWNLOADED, null);
        var b = remote(NEW, DownloadStatus.DOWNLOADED, null);
        var c = remote(OTHER, DownloadStatus.DOWNLOADED, null);
        when(stubClient.listTorrents()).thenReturn(List.of(a, b, c)).thenReturn(List.of(b, c));
        var result = cleaner.cleanAll(false);
        assertThat(result.removed()).isEqualTo(2); assertThat(result.blocked()).isEqualTo(1);
        verify(stubClient, times(1)).deleteTorrent(OLD, false);
        verify(stubClient, never()).deleteTorrent(NEW, false);
        // El job anterior ya no participa: ahora la revisión intermedia puede limpiarse.
        when(stubClient.listTorrents()).thenReturn(List.of(b, c)).thenReturn(List.of(c));
        result = cleaner.cleanAll(false);
        assertThat(result.selectedJobs()).isEqualTo(1); assertThat(result.removed()).isEqualTo(1);
        assertThat(cleaner.view(second.jobId()).items()).allMatch(row -> row.getState() == UpdateCleanup.State.REMOVED);
    }

    @Test void failedGlobalSnapshotDoesNotChangeEntriesOrDeleteAnything() {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> cleaner.cleanAll(false)).isInstanceOf(IllegalStateException.class);
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.WAITING);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
    }

    @Test void failedGlobalConfirmationReturnsPerJobErrorAndKeepsUncertainty() {
        var job = create(PreviousVersions.REMOVE_TORRENT);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, null),
                remote(NEW, DownloadStatus.DOWNLOADED, null))).thenThrow(new IllegalStateException("unavailable"));
        var result = cleaner.cleanAll(false);
        assertThat(result.failedJobs()).isEqualTo(1);
        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.jobs().getFirst().error()).contains("confirmar");
        assertThat(only(job.jobId()).getState()).isEqualTo(UpdateCleanup.State.REQUESTED);
    }

    @Test void globalCleanupBlocksConflictingPoliciesAndRejectsUnknownParameters() throws Exception {
        var first = create(PreviousVersions.REMOVE_TORRENT);
        var completedJob=jobs.findById(first.jobId()).orElseThrow();completedJob.setState(BulkJob.State.COMPLETED);jobs.save(completedJob);
        create(PreviousVersions.REMOVE_TORRENT_AND_FILES);
        when(stubClient.listTorrents()).thenReturn(List.of(remote(OLD, DownloadStatus.DOWNLOADED, "/a"), remote(NEW, DownloadStatus.DOWNLOADED, "/b")));
        var result = cleaner.cleanAll(false);
        assertThat(result.blocked()).isEqualTo(2);
        verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
        mvc.perform(post("/api/torrent/updates/cleanup").param("deleteFiles", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/torrent/updates/cleanup").param("retryUnconfirmed", "true", "false")).andExpect(status().isBadRequest());
    }

    @Test void previewIsReadOnlyAndSupportsSingleBookAndPagination() throws Exception {
        history(2, 1.0, OTHER, DownloadStatus.SUBMITTED); book(2, 2.0, "D".repeat(40));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", true).field("eplId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].catalogRevision").value(1.2));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", true).field("page", "1").field("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eplId").value(2));
        assertThat(jobs.count()).isZero(); assertThat(plans.count()).isZero();
        assertThat(downloads.count()).isEqualTo(2);
        assertThat(downloads.findAll()).allMatch(row -> row.getLastCheckedAt() == null);
        verifyNoInteractions(stubClient);
    }

    @Test void invalidApiParametersCannotAccidentallySelectAllBooks() throws Exception {
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", false).field("eplid", "1")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", false).field("eplId", "1", "-2")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", true).field("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", true).field("multipleHashes", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", false).contentType("application/json")
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
        when(stubClient.listTorrents()).thenReturn(List.of(old, target, newer)).thenReturn(List.of(target, newer));
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
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/torrent/updates", false).field("eplId", "1"))
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
        when(stubClient.listTorrents()).thenReturn(List.of(old, target)).thenReturn(List.of(target));
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
        when(stubClient.listTorrents()).thenReturn(List.of(old, target)).thenReturn(List.of(target));
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
        properties.effective().setBaseUrl("http://another-client:8080");
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
                assertThatThrownBy(() -> cleaner.cleanAll(false)).hasMessageContaining("envíos");
                verify(stubClient, never()).deleteTorrent(any(), anyBoolean());
            } finally { release.countDown(); }
            future.get(5, TimeUnit.SECONDS);
        }
    }
}
