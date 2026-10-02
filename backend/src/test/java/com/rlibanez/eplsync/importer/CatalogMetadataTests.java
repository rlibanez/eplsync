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

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class CatalogMetadataTests {
    @Autowired CatalogImportService service;
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired CatalogMetadataRepository metadata;
    @Autowired CatalogBookRepository books;
    @MockitoBean FileDownloader downloader;
    String csv;
    @BeforeEach void setup() throws Exception {
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
}
