package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.service.*;
import com.rlibanez.eplsync.repository.*;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.torrent.bulk.BulkItem;
import com.rlibanez.eplsync.torrent.downloads.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","DOWNLOADS_READ","TORRENT_SEND","TORRENT_SYNC","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class CatalogMissingTests {
    @Autowired com.rlibanez.eplsync.service.CatalogImportStore previews;
    @AfterEach void clearPreviews() { previews.clear(); }
    @Autowired CatalogMissingService missing;
    @Autowired CatalogImportService imports;
    @Autowired CatalogBookRepository books;
    @Autowired CatalogMetadataRepository metadata;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean FileDownloader downloader;
    String csv;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        previews.clear();
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from BulkItem").executeUpdate();
            em.createQuery("delete from DownloadRecord").executeUpdate();
            em.createNativeQuery("DROP TRIGGER IF EXISTS fail_missing").executeUpdate();
        });
        metadata.deleteAll(); books.deleteAllInBatch(); missing.clear();
        books.save(CatalogBook.builder().eplId(1L).title("Ausente").author("Autor").revision(1.0).build());
        books.save(CatalogBook.builder().eplId(2L).title("Presente").author("Autor").revision(1.0).build());
        csv = "EPL Id,Título,Autor,Revisión\n2,Presente,Autor,1.0\n";
        when(downloader.download(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var zip = Files.createTempFile("missing-test", ".zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("catalog.csv"));
                out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
            return zip;
        });
    }
    @AfterEach void cleanup() { tx.executeWithoutResult(s -> em.createNativeQuery("DROP TRIGGER IF EXISTS fail_missing").executeUpdate()); }
    @Test void previewAndImportCountMissingWithoutRemovingThem() {
        var preview = imports.previewCatalog(null, 0, 20);
        assertThat(preview.summary().missingBooks()).isEqualTo(1);
        assertThat(books.count()).isEqualTo(2);
        var result = imports.updateCatalog(null);
        assertThat(result.missingBooks()).isEqualTo(1);
        assertThat(metadata.findById(1L).orElseThrow().getMissingRows()).isEqualTo(1);
        assertThat(books.count()).isEqualTo(2);
    }
    @Test void deletesOnlyReviewedBooksWithoutDownloadingAgainAndPreservesHistory() throws Exception {
        tx.executeWithoutResult(s -> {
            var record = new DownloadRecord(); record.setId("old"); record.setEplId(1L);
            record.setRevision(1.0); record.setHash("a".repeat(40)); record.setClient("qbittorrent");
            record.setClientInstanceId("test"); record.setStatus(DownloadStatus.DOWNLOADED);
            record.setOrigin(DownloadRecord.Origin.EPLSYNC); record.setCreatedAt(java.time.Instant.now()); em.persist(record);
        });
        var preview = missing.preview();
        assertThat(preview.total()).isEqualTo(1);
        assertThat(preview.items().getFirst().eplId()).isEqualTo(1);
        assertThat(books.count()).isEqualTo(2);
        assertThat(missing.page(preview.token(),0,1).items()).hasSize(1);
        assertThatThrownBy(() -> missing.delete(preview.token(),false)).isInstanceOf(IllegalArgumentException.class);
        csv = "EPL Id,Título,Autor,Revisión\n1,Ausente,Autor,1.0\n";
        assertThat(missing.delete(preview.token(),true).deleted()).isEqualTo(1);
        assertThat(books.existsById(1L)).isFalse(); assertThat(books.existsById(2L)).isTrue();
        tx.executeWithoutResult(s -> assertThat(em.find(DownloadRecord.class,"old")).isNotNull());
        verify(downloader,times(1)).download(anyString(),anyString(),anyString());
        assertThatThrownBy(() -> missing.delete(preview.token(),true)).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
    }
    @Test void rejectsMalformedEmptyAndPartialCsv() {
        csv = "EPL Id,Título,Autor,Revisión\n";
        assertThatThrownBy(missing::preview).isInstanceOf(IllegalArgumentException.class);
        csv += "2,Presente,Autor,1.0\n,Invalid,Autor,1.0\n";
        assertThatThrownBy(missing::preview).isInstanceOf(IllegalArgumentException.class);
        assertThat(imports.previewCatalog(null,0,20).summary().missingBooks()).isNull();
        assertThat(books.count()).isEqualTo(2);
    }
    @Test void rejectsChangedCatalogAndChangedCandidate() {
        var preview = missing.preview();
        var book = books.findById(1L).orElseThrow(); book.setTitle("Changed"); books.save(book);
        assertThatThrownBy(() -> missing.delete(preview.token(),true)).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        var next = missing.preview(); imports.updateCatalog(null);
        assertThatThrownBy(() -> missing.delete(next.token(),true)).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        assertThat(books.count()).isEqualTo(2);
    }
    @Test void rejectsPendingWorkAndRollsBackFailedDeletion() {
        var preview = missing.preview();
        tx.executeWithoutResult(s -> {
            var item = new BulkItem(); item.setId("pending"); item.setJobId("job"); item.setEplId(1L); item.setState(BulkItem.State.PENDING); em.persist(item);
        });
        assertThatThrownBy(() -> missing.delete(preview.token(),true)).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        assertThat(books.count()).isEqualTo(2);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from BulkItem").executeUpdate();
            em.createNativeQuery("CREATE TRIGGER fail_missing BEFORE DELETE ON catalog_books BEGIN SELECT RAISE(ABORT, 'test failure'); END").executeUpdate();
        });
        assertThatThrownBy(() -> missing.delete(preview.token(),true)).isInstanceOf(RuntimeException.class);
        assertThat(books.count()).isEqualTo(2);
    }
}
