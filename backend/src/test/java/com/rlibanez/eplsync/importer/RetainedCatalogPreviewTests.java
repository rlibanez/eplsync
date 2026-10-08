package com.rlibanez.eplsync.importer;

import java.util.Objects;

import com.rlibanez.eplsync.config.CatalogImportProperties;
import com.rlibanez.eplsync.service.*;
import com.rlibanez.eplsync.repository.*;
import com.rlibanez.eplsync.exception.CatalogPreviewException;
import com.rlibanez.eplsync.model.CatalogBook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=create-drop",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class RetainedCatalogPreviewTests {
    @TempDir static Path directory;
    @Autowired CatalogImportService service;
    @Autowired CatalogImportStore store;
    @Autowired CatalogImportProperties properties;
    @Autowired CatalogBookRepository books;
    @Autowired CatalogMetadataRepository metadata;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FileDownloader downloader;
    String csv;
    Path zip;
    Path cache = Path.of(System.getProperty("java.io.tmpdir"), "eplsync-catalog-import");
    @BeforeEach void setup() throws Exception {
        store.clear(); books.deleteAllInBatch(); metadata.deleteAll();
        properties.effective().setRetention(Duration.ofHours(24));
        csv = "EPL Id,Título,Autor,Revisión\n2,Original,Autor,1.0\n";
        when(downloader.download(anyString(), anyString(), anyString())).thenAnswer(call -> {
            zip = Files.createTempFile(directory, "download-", ".zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                var entry = new ZipEntry("catalog.csv"); entry.setTimeLocal(LocalDateTime.of(2026,10,2,4,0));
                out.putNextEntry(entry); out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
            return zip;
        });
    }
    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_preview_apply");
        store.clear();
    }
    @Test void appliesExactCsvWithoutDownloadAndPreservesSourceMetadata() throws Exception {
        Instant before = Instant.now();
        var summary = service.previewCatalog("https://example.test/original.zip",0,50).summary();
        var handle = summary.preview();
        assertThat(handle.expiresAt()).isBetween(before.plus(Duration.ofHours(24)),Instant.now().plus(Duration.ofHours(24)));
        assertThat(handle.sourceModifiedAt()).isEqualTo("2026-10-02T04:00");
        assertThat(Files.exists(zip)).isFalse();
        assertThat(books.count()).isZero();
        csv = "EPL Id,Título,Autor,Revisión\n3,Changed remote CSV,Autor,1.0\n";
        var applied = service.applyPreview(handle.token());
        assertThat(applied.recordsCreated()).isEqualTo(1);
        assertThat(applied.metadata().getSourceType()).isEqualTo("URL");
        assertThat(books.findById(2L).orElseThrow().getTitle()).isEqualTo("Original");
        assertThat(books.existsById(3L)).isFalse();
        assertThat(applied.metadata().getSourceUrl()).isEqualTo("https://example.test/original.zip");
        assertThat(applied.metadata().getSourceModifiedAt()).isEqualTo(handle.sourceModifiedAt());
        assertThat(Files.exists(cache.resolve("catalog.zip"))).isTrue();
        assertThat(store.archive().sha256()).isNotEqualTo(applied.metadata().getSourceSha256());
        assertThat(applied.metadata().getSourceZipSha256()).isEqualTo(store.archive().sha256());
        assertThatThrownBy(() -> service.applyPreview(handle.token())).isInstanceOf(CatalogPreviewException.class).hasMessage("PREVIEW_EXPIRED");
        verify(downloader,times(1)).download(anyString(),anyString(),anyString());
    }
    @Test void catalogChangeRequiresRecalculationUsingRetainedFile() throws Exception {
        var token = service.previewCatalog(null,0,50).summary().preview().token();
        books.save(CatalogBook.builder().eplId(2L).title("Modified locally").author("Autor").revision(1.0).build());
        assertThatThrownBy(() -> service.applyPreview(token)).isInstanceOf(CatalogPreviewException.class).hasMessage("PREVIEW_STALE");
        assertThat(books.findById(2L).orElseThrow().getTitle()).isEqualTo("Modified locally");
        var refreshed = service.refreshPreview(token);
        assertThat(refreshed.recordsUpdated()).isEqualTo(1);
        assertThat(refreshed.preview().token()).isEqualTo(token);
        assertThat(service.applyPreview(token).metadata().getSourceType()).isEqualTo("URL");
        assertThat(books.findById(2L).orElseThrow().getTitle()).isEqualTo("Original");
        verify(downloader,times(1)).download(anyString(),anyString(),anyString());
    }
    @Test void discardIsIdempotentAndLeavesDatabaseUnchanged() throws Exception {
        var token = service.previewCatalog(null,0,50).summary().preview().token();
        service.discardPreview(token); service.discardPreview(token);
        assertThat(Files.exists(cache.resolve("catalog.zip"))).isTrue();
        assertThatThrownBy(() -> service.retainedPreview(token)).hasMessage("PREVIEW_EXPIRED");
        var archived = store.archive();
        service.runSaved(archived.id(), CatalogImportService.Mode.PREVIEW);
        assertThat(store.archive().expiresAt()).isEqualTo(archived.expiresAt());
        verify(downloader, times(1)).download(anyString(),anyString(),anyString());
        assertThat(books.count()).isZero(); assertThat(metadata.count()).isZero();
    }
    @Test void newDownloadReplacesPreviousZipAndExpiryRemovesEverything() throws Exception {
        var first = service.previewCatalog(null,0,50).summary().preview().token();
        var oldArchive = store.archive().id();
        var second = service.previewCatalog(null,0,50).summary().preview().token();
        assertThatThrownBy(() -> service.retainedPreview(first)).hasMessage("PREVIEW_EXPIRED");
        assertThatThrownBy(() -> service.runSaved(oldArchive,CatalogImportService.Mode.UPDATE)).hasMessage("ARCHIVE_EXPIRED");
        assertThat(service.retainedPreview(second).preview().token()).isEqualTo(second);
        properties.effective().setRetention(Duration.ofMillis(50));
        var expired = service.previewCatalog(null,0,50).summary().preview().token();
        Thread.sleep(100); store.prune();
        assertThat(store.archive()).isNull();
        assertThat(Files.exists(cache.resolve("catalog.zip"))).isFalse();
        assertThatThrownBy(() -> service.retainedPreview(expired)).hasMessage("PREVIEW_EXPIRED");
    }
    @Test void recoversDescriptorAfterRestartAndRejectsTamperedZip() throws Exception {
        var token = service.previewCatalog(null,0,50).summary().preview().token();
        var restarted = new CatalogImportStore(properties, new ZipExtractor(), cache);
        assertThat(restarted.usePreview(token, value -> Objects.requireNonNull(value).summary()).recordsCreated()).isEqualTo(1);
        assertThat(restarted.<String>usePreview(token, value -> Objects.requireNonNull(value).sourceType())).isEqualTo("URL");
        Files.writeString(cache.resolve("catalog.zip"),"changed");
        assertThatThrownBy(() -> service.applyPreview(token)).hasMessage("PREVIEW_FILE_CHANGED");
        assertThat(books.count()).isZero();
        service.discardPreview(token);
    }
    @Test void failedImportRollsBackAndKeepsZipForRetry() {
        var token = service.previewCatalog(null,0,50).summary().preview().token();
        jdbc.execute("CREATE TRIGGER fail_preview_apply BEFORE INSERT ON catalog_books BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        assertThatThrownBy(() -> service.applyPreview(token)).isInstanceOf(RuntimeException.class);
        assertThat(books.count()).isZero(); assertThat(metadata.count()).isZero();
        assertThat(Files.exists(cache.resolve("catalog.zip"))).isTrue();
        jdbc.execute("DROP TRIGGER fail_preview_apply");
        assertThat(service.applyPreview(token).recordsCreated()).isEqualTo(1);
    }
    @Test void malformedCsvIsRejectedBeforeRetainingZip() throws Exception {
        csv = "EPL Id,Título,Autor,Revisión\n3,\"unterminated";
        assertThatThrownBy(() -> service.previewCatalog(null,0,50)).isInstanceOf(RuntimeException.class);
        try (var files = Files.list(cache)) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void failedReplacementRemovesPreviousArchive() throws Exception {
        var token = service.previewCatalog(null,0,50).summary().preview().token();
        when(downloader.download(anyString(),anyString(),anyString())).thenThrow(new java.io.IOException("offline"));
        assertThatThrownBy(() -> service.updateCatalog(null)).hasMessageContaining("ZIP");
        assertThat(store.archive()).isNull();
        assertThatThrownBy(() -> service.retainedPreview(token)).hasMessage("PREVIEW_EXPIRED");
    }
    @Test void uploadAndSavedUpdateKeepZipAndOriginalExpiration() throws Exception {
        // Generate fixture without involving the service's URL branch.
        Path fixture = downloader.download("fixture","fixture",".zip");
        byte[] bytes = Files.readAllBytes(fixture); Files.delete(fixture); clearInvocations(downloader);
        var file = new org.springframework.mock.web.MockMultipartFile("file","C:\\tmp\\books.ZIP","application/zip",bytes);
        var preview = service.runUpload(file,CatalogImportService.Mode.PREVIEW);
        var archive = store.archive();
        assertThat(archive.name()).isEqualTo("books.ZIP");
        assertThat(archive.sourceUrl()).isNull();
        assertThat(archive.sha256()).isEqualTo(CatalogImportStore.digest(cache.resolve("catalog.zip")));
        var uploaded = service.runUpload(file,CatalogImportService.Mode.UPDATE);
        assertThat(uploaded.metadata().getSourceType()).isEqualTo("LOCAL_FILE");
        assertThat(uploaded.metadata().getSourceZipSha256()).isEqualTo(store.archive().sha256());
        assertThat(uploaded.metadata().getSourceArchiveName()).isEqualTo("books.ZIP");
        archive = store.archive();
        var result = service.runSaved(archive.id(),CatalogImportService.Mode.UPDATE);
        assertThat(result.recordsUnchanged()).isEqualTo(1);
        assertThat(result.metadata().getSourceType()).isEqualTo("SAVED_ZIP");
        assertThat(result.metadata().getSourceZipSha256()).isEqualTo(archive.sha256());
        assertThat(result.metadata().getSourceUrl()).isNull();
        assertThat(store.archive()).isEqualTo(archive);
        assertThatThrownBy(() -> service.retainedPreview(preview.preview().token())).hasMessage("PREVIEW_EXPIRED");
        verifyNoInteractions(downloader);
    }
    @Test void applyingPreviewPreservesLocalOrExplicitSavedSelection() throws Exception {
        Path fixture = downloader.download("fixture","fixture",".zip");
        byte[] bytes = Files.readAllBytes(fixture); Files.delete(fixture);
        var file = new org.springframework.mock.web.MockMultipartFile("file","books.zip","application/zip",bytes);
        var local = service.runUpload(file,CatalogImportService.Mode.PREVIEW);
        assertThat(service.applyPreview(local.preview().token()).metadata().getSourceType()).isEqualTo("LOCAL_FILE");
        var saved = service.runSaved(store.archive().id(),CatalogImportService.Mode.PREVIEW);
        service.refreshPreview(saved.preview().token());
        assertThat(service.applyPreview(saved.preview().token()).metadata().getSourceType()).isEqualTo("SAVED_ZIP");
    }
    @Test void invalidUploadDoesNotImportAndBrokenZipReplacesOldArchive() {
        service.previewCatalog(null,0,50);
        var invalid = new org.springframework.mock.web.MockMultipartFile("file","books.txt","text/plain",new byte[]{1});
        assertThatThrownBy(() -> service.runUpload(invalid,CatalogImportService.Mode.UPDATE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.archive()).isNotNull();
        var broken = new org.springframework.mock.web.MockMultipartFile("file","books.zip","application/zip",new byte[]{1});
        assertThatThrownBy(() -> service.runUpload(broken,CatalogImportService.Mode.UPDATE)).isInstanceOf(RuntimeException.class);
        assertThat(store.archive()).isNull();
        assertThat(books.count()).isZero();
    }
    @Test void concurrentApplyOnlyImportsOnce() throws Exception {
        var token = service.previewCatalog(null,0,50).summary().preview().token();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> apply = () -> { try { service.applyPreview(token); return true; } catch (CatalogPreviewException ex) { return false; } };
            var first = executor.submit(apply); var second = executor.submit(apply);
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(books.count()).isEqualTo(1);
    }

    @Test void invalidIncomingCsvPreservesExistingCatalogAndMetadata() throws Exception {
        service.updateCatalog(null);
        var original = books.findById(2L).orElseThrow();
        var sourceHash = metadata.findById(1L).orElseThrow().getSourceSha256();
        csv = "EPL Id,Título,Autor,Revisión\n1,Wrong column count\n";
        assertThatThrownBy(() -> service.importCatalog(null)).hasMessageContaining("columnas incorrecto");
        assertThat(books.findById(2L).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
        assertThat(metadata.findById(1L).orElseThrow().getSourceSha256()).isEqualTo(sourceHash);
        assertThat(store.archive()).isNull();
        try (var files = Files.list(cache)) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void oversizedRetainedArchiveIsRejectedBeforeHashing() throws Exception {
        service.updateCatalog(null);
        String id = store.archive().id();
        try (var file = new java.io.RandomAccessFile(cache.resolve("catalog.zip").toFile(), "rw")) {
            file.setLength(CatalogImportLimits.ZIP_BYTES + 1);
        }
        assertThatThrownBy(() -> service.runSaved(id, CatalogImportService.Mode.UPDATE)).hasMessageContaining("128 MiB");
        assertThat(books.findById(2L).orElseThrow().getTitle()).isEqualTo("Original");
    }
    @Test void oversizedUploadIsRejectedWithoutAllocatingItsContents() {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "catalog.zip", "application/zip", new byte[]{1}) {
            @Override public long getSize() { return CatalogImportLimits.ZIP_BYTES + 1; }
            @Override public java.io.InputStream getInputStream() { throw new AssertionError("Oversized upload must not be read"); }
        };
        assertThatThrownBy(() -> service.runUpload(file, CatalogImportService.Mode.UPDATE)).hasMessageContaining("128 MiB");
        assertThat(store.archive()).isNull();
        assertThat(books.count()).isZero();
    }

    @Test void longUploadNameUsesOriginalExtensionAndBoundedDisplayMetadata() throws Exception {
        Path fixture = downloader.download("fixture", "fixture", ".zip");
        byte[] bytes = Files.readAllBytes(fixture); Files.delete(fixture);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "x".repeat(210) + ".ZIP", "application/zip", bytes);
        assertThat(service.runUpload(file, CatalogImportService.Mode.PREVIEW).recordsCreated()).isEqualTo(1);
        assertThat(store.archive().name()).isEqualTo("x".repeat(200));
        assertThat(books.count()).isZero();
    }
}
