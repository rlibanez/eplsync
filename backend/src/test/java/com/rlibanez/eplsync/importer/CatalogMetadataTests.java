package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.service.CatalogImportService;
import com.rlibanez.eplsync.repository.CatalogMetadataRepository;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class CatalogMetadataTests {
    @Autowired com.rlibanez.eplsync.service.CatalogImportStore previews;
    @AfterEach void clearPreviews() { previews.clear(); }
    @Autowired CatalogImportService service;
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired CatalogMetadataRepository metadata;
    @Autowired CatalogBookRepository books;
    @MockitoBean FileDownloader downloader;
    String csv;
    @BeforeEach void setup() throws Exception {
        previews.clear();
        metadata.deleteAll(); books.deleteAllInBatch();
        csv = "EPL Id,Título,Autor,Revisión\n2,Nuevo,Autor,1.0\n";
        when(downloader.download(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var zip = Files.createTempFile("metadata-test", ".zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                var entry = new ZipEntry("folder/catalog.csv");
                entry.setTimeLocal(LocalDateTime.of(2026,9,30,4,0,50));
                out.putNextEntry(entry); out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
            return zip;
        });
    }
    @Test void previewRecordsGroupedStartAndResultWithoutImportingBooks() {
        long cursor = events.cursor();
        service.previewCatalog(null, 0, 20);
        var entries = events.after(cursor, 10);
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.action()).isEqualTo("PREVIEW");
            assertThat(entry.details().get("dryRun")).isEqualTo(true);
        });
        assertThat(entries.getFirst().outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED);
        assertThat(entries.getLast().outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        assertThat(entries.getLast().operationId()).isEqualTo(entries.getFirst().operationId());
        assertThat(books.count()).isZero();
        assertThat(metadata.count()).isZero();
        csv = "EPL Id,Título,Autor,Revisión\n3,\"unterminated";
        cursor = events.cursor();
        assertThatThrownBy(() -> service.previewCatalog(null,0,20)).isInstanceOf(RuntimeException.class);
        assertThat(events.after(cursor,10).getLast().outcome()).isEqualTo(com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED);
    }
    @Test void storesOriginalDateCountsAndDigestAndReplacesSingleton() throws Exception {
        var result = service.updateCatalog("https://example.test/catalog.zip");
        var stored = metadata.findById(1L).orElseThrow();
        assertThat(stored.getSourceModifiedAt()).isEqualTo("2026-09-30T04:00:50");
        assertThat(stored.getSourceFileName()).isEqualTo("catalog.csv");
        assertThat(stored.getSourceType()).isEqualTo("URL");
        assertThat(stored.getSourceArchiveName()).isEqualTo("catalog.zip");
        assertThat(stored.getSourceZipSha256()).isEqualTo(previews.archive().sha256());
        assertThat(stored.getSourceUrl()).isEqualTo("https://example.test/catalog.zip");
        assertThat(stored.getTotalRows()).isEqualTo(1);
        assertThat(stored.getInsertedRows()).isEqualTo(1);
        assertThat(result.metadata().getSourceSha256()).isEqualTo(java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        service.updateCatalog(null);
        assertThat(metadata.count()).isEqualTo(1);
        assertThat(metadata.findById(1L).orElseThrow().getUnchangedRows()).isEqualTo(1);
    }
    @Test void previewAndFailurePreserveLastAppliedMetadata() {
        service.updateCatalog(null);
        var before = metadata.findById(1L).orElseThrow();
        csv = "EPL Id,Título,Autor,Revisión\n3,Otro,Autor,1.0\n";
        service.previewCatalog(null,0,10);
        assertThat(metadata.findById(1L).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        csv = "EPL Id,Título,Autor,Revisión\n3,\"unterminated";
        assertThatThrownBy(() -> service.importCatalog(null)).isInstanceOf(RuntimeException.class);
        assertThat(metadata.findById(1L).orElseThrow()).usingRecursiveComparison().isEqualTo(before);
        assertThat(books.existsById(2L)).isTrue();
    }

    @Test void partialReplacementPreservesBooksDatesMetadataAndRecordsFailureReason() {
        service.updateCatalog(null);
        var originalBook = books.findById(2L).orElseThrow();
        var originalMetadata = metadata.findById(1L).orElseThrow();
        long cursor = events.cursor();
        csv = "EPL Id,Título,Autor,Revisión\n3,Nuevo válido,Autor,1.0\ninvalid,Incorrecto,Autor,1.0\n";
        assertThatThrownBy(() -> service.importCatalog(null))
            .hasMessage("No se puede reemplazar el catálogo: el CSV contiene 1 registro con errores. El catálogo anterior se ha conservado.");
        assertThat(books.count()).isEqualTo(1);
        assertThat(books.findById(2L).orElseThrow()).usingRecursiveComparison().isEqualTo(originalBook);
        assertThat(metadata.findById(1L).orElseThrow()).usingRecursiveComparison().isEqualTo(originalMetadata);
        var recorded = events.after(cursor,10);
        assertThat(recorded).extracting((com.rlibanez.eplsync.events.EventJournal.Entry entryValue) -> java.util.Objects.requireNonNull(entryValue).outcome())
            .containsExactly(com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED,com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED);
        assertThat(recorded.getLast().details().get("reason")).isEqualTo("No se puede reemplazar el catálogo: el CSV contiene 1 registro con errores. El catálogo anterior se ha conservado.");
        var preview = service.previewCatalog(null,0,20);
        assertThat(preview.summary().errors()).isEqualTo(1);
        assertThat(preview.summary().recordsCreated()).isEqualTo(1);
        assertThat(books.existsById(3L)).isFalse();
        var partial = service.updateCatalog(null);
        assertThat(partial.errors()).isEqualTo(1);
        assertThat(books.findById(2L).orElseThrow()).usingRecursiveComparison().isEqualTo(originalBook);
        assertThat(books.existsById(3L)).isTrue();
    }
}
