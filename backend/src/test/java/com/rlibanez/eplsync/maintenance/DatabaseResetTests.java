package com.rlibanez.eplsync.maintenance;

import com.rlibanez.eplsync.controller.DatabaseResetController;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.updates.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.nio.file.Files;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import com.rlibanez.eplsync.importer.FileDownloader;
import com.rlibanez.eplsync.service.CatalogImportService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class DatabaseResetTests {
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired DatabaseResetService service;
    @Autowired CatalogImportService catalog;
    @MockitoBean FileDownloader downloader;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired BulkStore bulk;
    @Autowired DownloadTrackingService tracking;
    TransactionTemplate tx;
    String csv;
    private static final String[] ENTITIES = {"UpdateCleanup", "UpdatePlan", "BulkItem", "BulkJob", "DownloadRecord", "CatalogBook", "CatalogMetadata"};

    @BeforeEach void seed() throws Exception {
        csv = "EPL Id,Título,Autor,Revisión\n2,Nuevo,Autor,1.0\n";
        when(downloader.download(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var zip = Files.createTempFile("reset-test-", ".zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("catalog.csv"));
                out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            return zip;
        });
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DROP TRIGGER IF EXISTS fail_catalog_reset").executeUpdate();
            for (String entity : ENTITIES) em.createQuery("delete from " + entity).executeUpdate();
            em.createNativeQuery("DELETE FROM app_events").executeUpdate();
            em.persist(new com.rlibanez.eplsync.model.CatalogMetadata());
            events.record(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG, "UPDATE", com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED, com.rlibanez.eplsync.events.EventContext.Origin.MANUAL, "seed", java.util.Map.of());
            em.persist(CatalogBook.builder().eplId(1L).title("Libro").author("Autor").revision(1.0).build());
            var download = new DownloadRecord(); download.setId("download"); download.setEplId(1L);
            download.setRevision(1.0); download.setHash("a".repeat(40)); download.setClient("qbittorrent");
            download.setClientInstanceId("instance"); download.setStatus(DownloadStatus.DOWNLOADED);
            download.setOrigin(DownloadRecord.Origin.EPLSYNC); download.setCreatedAt(Instant.now()); em.persist(download);
            var job = new BulkJob(); job.setId("job"); job.setState(BulkJob.State.PAUSED); em.persist(job);
            var item = new BulkItem(); item.setId("item"); item.setJobId("job"); item.setState(BulkItem.State.PENDING); em.persist(item);
            var plan = new UpdatePlan(); plan.setJobId("job"); plan.setClientInstanceId("instance");
            plan.setPreviousVersions(PreviousVersions.KEEP); plan.setCreatedAt(Instant.now()); plan.setSnapshot("{}"); em.persist(plan);
            var cleanup = new UpdateCleanup(); cleanup.setJobId("job"); cleanup.setDownloadId("download");
            cleanup.setEplId(1L); cleanup.setHash("a".repeat(40)); cleanup.setState(UpdateCleanup.State.KEPT); em.persist(cleanup);
        });
    }
    @AfterEach void removeTrigger() {
        tx.executeWithoutResult(status -> em.createNativeQuery("DROP TRIGGER IF EXISTS fail_catalog_reset").executeUpdate());
    }
    void assertRows(long expected) {
        tx.executeWithoutResult(status -> {
            for (String entity : ENTITIES) assertThat(em.createQuery("select count(e) from " + entity + " e", Long.class)
                    .getSingleResult()).as(entity).isEqualTo(expected);
        });
    }
    @Test void resetsAllDataAndCanBeRepeated() {
        var result = service.reset();
        assertThat(result).isEqualTo(new DatabaseResetService.ResetResult(true, 1, 1, 1, 1, 1, 1, 1, 1, 0));
        assertRebuilt();
        assertThat(events.after(0, 100)).isEmpty();
        assertThat(service.reset()).isEqualTo(new DatabaseResetService.ResetResult(true, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertRebuilt();
        verifyNoInteractions(downloader);
    }
    void assertRebuilt() { assertRows(0); }
    @Test void resetAlsoDiscardsRetainedCsvPreviews() {
        var token = catalog.previewCatalog(null,0,50).summary().preview().token();
        service.reset();
        assertRows(0);
        assertThatThrownBy(() -> catalog.retainedPreview(token))
                .isInstanceOf(com.rlibanez.eplsync.exception.CatalogPreviewException.class).hasMessage("PREVIEW_EXPIRED");
    }
    @Test void rollsBackEveryDeletionIfLastTableFails() {
        tx.executeWithoutResult(status -> em.createNativeQuery("CREATE TRIGGER fail_catalog_reset BEFORE DELETE ON app_events "
                + "BEGIN SELECT RAISE(ABORT, 'test reset failure'); END").executeUpdate());
        assertThatThrownBy(service::reset).isInstanceOf(RuntimeException.class);
        assertRows(1);
    }
    @Test void rejectsPersistedInFlightItemsWithoutDeletingAnything() {
        tx.executeWithoutResult(status -> em.find(BulkItem.class,"item").setState(BulkItem.State.IN_FLIGHT));
        assertThatThrownBy(service::reset).isInstanceOf(TorrentOperationException.class);
        assertRows(1);
    }
    @Test void rejectsLiveWorkerAndClearsItsCacheOnlyAfterCommit() {
        var worker = mock(BulkWorker.class);
        @SuppressWarnings("unchecked") ObjectProvider<BulkWorker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(worker);
        var guarded = new DatabaseResetService(bulk,provider,tracking,em,manager,new com.rlibanez.eplsync.service.CatalogOperationGate(new com.rlibanez.eplsync.config.CatalogImportProperties()));
        when(worker.hasInFlightSends()).thenReturn(true);
        assertThatThrownBy(guarded::reset).isInstanceOf(TorrentOperationException.class); assertRows(1);
        verify(worker,never()).clearIdleState();
        when(worker.hasInFlightSends()).thenReturn(false);
        doAnswer(invocation -> {assertRebuilt(); return null;}).when(worker).clearIdleState();
        guarded.reset(); verify(worker).clearIdleState();
    }
    @Test void requiresExplicitConfirmation() throws Exception {
        var mocked = mock(DatabaseResetService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseResetController(mocked))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : new String[]{"{}", "{\"confirm\":false}", "{\"confirm\":null}"})
            mvc.perform(post("/api/maintenance/reset").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(mocked);
        when(mocked.reset(false)).thenReturn(new DatabaseResetService.ResetResult(true,0,0,0,0,0,0,0,0, 0));
        mvc.perform(post("/api/maintenance/reset").contentType("application/json").content("{\"confirm\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mocked).reset(false);
    }
}
