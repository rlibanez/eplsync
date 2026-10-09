package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.repository.CatalogBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class CatalogImportTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path walDatabaseDirectory;
    @org.springframework.test.context.DynamicPropertySource
    static void diskConcurrencyConfiguration(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (Boolean.getBoolean("eplsync.test.sqlite-disk")) {
            registry.add("spring.datasource.url", () -> "jdbc:sqlite:"+walDatabaseDirectory.resolve("test.db"));
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        }
    }

    @Autowired CatalogBookCsvImporter importer;
    @Autowired CatalogBookRepository repository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.junit.jupiter.api.AfterEach void removeTestTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS block_catalog_delete");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_second_insert");
    }
    @TempDir Path directory;

    @BeforeEach
    void clearCatalog() {
        repository.deleteAllInBatch();
    }

    private Path csv(String rows) throws IOException {
        return Files.writeString(directory.resolve("catalog.csv"),
                "EPL Id,Revisión,Autor,Título\n" + rows);
    }

    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test void expiryBeforeCommitRollsBackReplacementAndUpdate() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"),true);
        for(boolean replace:new boolean[]{true,false}) {
            var clock=new java.util.concurrent.atomic.AtomicLong();
            var source=csv("1,2,Autor,Cambiado\n2,1,Autor,Nuevo\n");
            try(var budget=new CatalogOperationBudget(java.time.Duration.ofSeconds(1),clock::get)) {
                assertThatThrownBy(() -> transactions.execute(tx -> {
                    try { importer.importFile(source,replace); }
                    catch(IOException ex) {throw new java.io.UncheckedIOException(ex);}
                    clock.set(java.time.Duration.ofSeconds(2).toNanos());
                    return null;
                })).isInstanceOf(com.rlibanez.eplsync.exception.CatalogOperationException.class);
            }
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findById(1L).orElseThrow().getTitle()).isEqualTo("Original");
        }
    }

    @Test void deadlineStopsConversionAndDoesNotTurnTimeoutIntoInvalidRows() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"),true);
        var source=csv("2,1,Autor,Nuevo\n".repeat(100));
        var clock=new java.util.concurrent.atomic.AtomicLong();
        try(var budget=new CatalogOperationBudget(java.time.Duration.ofSeconds(1),() -> clock.addAndGet(100_000_000))) {
            assertThatThrownBy(() -> importer.importFile(source,false)).isInstanceOf(com.rlibanez.eplsync.exception.CatalogOperationException.class);
        }
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="EPLSYNC_TEST_IMPORT_LOAD",matches="true")
    void measuresSynthetic73000BookImportAndPreview() throws Exception {
        var source=directory.resolve("load.csv");
        try(var output=Files.newBufferedWriter(source)) {
            output.write("EPL Id,Revisión,Autor,Título,Sinopsis\n");
            for(int id=1;id<=73000;id++) output.write(id+",1.2,Autor "+id+",Título "+id+",Sinopsis sintética del libro "+id+" para medir conversión y persistencia.\n");
        }
        long start=System.nanoTime();
        importer.importFile(source,true);
        long imported=System.nanoTime();
        importer.previewFile(source,0,50);
        long previewed=System.nanoTime();
        assertThat(repository.count()).isEqualTo(73000);
        System.out.printf("LOAD_73000 import=%.3fs preview=%.3fs%n",(imported-start)/1e9,(previewed-imported)/1e9);
    }

    @Test
    void updateCountsChangesAndPreservesMissingBooksAndDates() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n2,1,Autor,Igual\n3,1,Autor,Ausente\n"), true);
        Instant created = repository.findById(1L).orElseThrow().getInsertDate();
        Instant beforeUpdate = Instant.now().minusSeconds(1);
        var result = importer.importFile(csv("1,2,Autor,Modificado\n2,1,Autor,Igual\n4,1,Autor,Nuevo\n"), false);
        assertThat(result).isEqualTo(new CatalogBookCsvImporter.ImportStats(3, 0, 1, 1, 1, 1L));
        assertThat(repository.count()).isEqualTo(4);
        var updated = repository.findById(1L).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Modificado");
        assertThat(updated.getInsertDate()).isEqualTo(created);
        assertThat(updated.getLastModifiedDate()).isBetween(beforeUpdate, Instant.now().plusSeconds(1));
        assertThat(repository.findById(2L).orElseThrow().getLastModifiedDate()).isNull();
        assertThat(repository.findById(4L).orElseThrow().getInsertDate()).isBetween(beforeUpdate, Instant.now().plusSeconds(1));
        var repeated = importer.importFile(directory.resolve("catalog.csv"), false);
        assertThat(repeated).isEqualTo(new CatalogBookCsvImporter.ImportStats(3, 0, 0, 0, 3, 1L));
        assertThat(repository.findById(1L).orElseThrow().getLastModifiedDate())
                .isEqualTo(updated.getLastModifiedDate());
    }

    @Test
    void replacementRemovesPreviousCatalogAndCreatesBooksAgain() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n2,1,Autor,Eliminar\n"), false);
        importer.importFile(csv("1,2,Autor,Modificado\n"), false);
        var result = importer.importFile(csv("1,3,Autor,Reemplazo\n3,1,Autor,Nuevo\n"), true);
        assertThat(result).isEqualTo(new CatalogBookCsvImporter.ImportStats(2, 0, 0, 2, 0, 0L));
        assertThat(repository.existsById(2L)).isFalse();
        assertThat(repository.findById(1L).orElseThrow().getLastModifiedDate()).isNull();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void previewReturnsFullChangesWithoutWritingAndMatchesActualImport() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n2,1,Autor,Igual\n"), true);
        var original = repository.findById(1L).orElseThrow();
        Path file = csv("1,2,Autor,Modificado\n2,1,Autor,Igual\n3,1,Autor,Nuevo\n4,1,Autor,Otro\n3,1,Autor,Duplicado\n");
        var preview = importer.previewFile(file, 0, 1);
        assertThat(preview.summary().recordsProcessed()).isEqualTo(4);
        assertThat(preview.summary().errors()).isEqualTo(1);
        assertThat(preview.summary().recordsCreated()).isEqualTo(2);
        assertThat(preview.summary().recordsUpdated()).isEqualTo(1);
        assertThat(preview.summary().recordsUnchanged()).isEqualTo(1);
        assertThat(preview.createdBooks()).hasSize(1);
        assertThat(preview.createdBooks().getFirst().getEplId()).isEqualTo(3L);
        assertThat(preview.createdBooks().getFirst().getInsertDate()).isNull();
        var update = preview.updatedBooks().getFirst();
        assertThat(update.before()).usingRecursiveComparison().isEqualTo(original);
        assertThat(update.after().getTitle()).isEqualTo("Modificado");
        assertThat(update.after().getInsertDate()).isEqualTo(original.getInsertDate());
        assertThat(update.after().getLastModifiedDate()).isNull();
        assertThat(update.changedFields()).containsExactly("revision", "title");
        var nextPage = importer.previewFile(file, 1, 1);
        assertThat(nextPage.createdBooks().getFirst().getEplId()).isEqualTo(4L);
        assertThat(nextPage.updatedBooks()).isEmpty();
        assertThat(importer.previewFile(file, 10, 1).createdBooks()).isEmpty();
        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findById(1L).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
        assertThat(importer.importFile(file, false))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(4, 1, 1, 2, 1, null));
    }

    @Test
    void previewOnEmptyDatabaseTreatsEveryBookAsNewWithoutInserting() throws Exception {
        var result = importer.previewFile(csv("1,1,Autor,Primero\n2,1,Autor,Segundo\n"), 0, 50);
        assertThat(result.summary().recordsCreated()).isEqualTo(2);
        assertThat(result.summary().recordsUpdated()).isZero();
        assertThat(result.summary().recordsUnchanged()).isZero();
        assertThat(result.summary().errors()).isZero();
        assertThat(result.createdBooks()).hasSize(2);
        assertThat(result.updatedBooks()).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void invalidNumericRowIsSkippedWithoutLosingFollowingRows() throws Exception {
        Path file = csv("1,1,Autor,Primero\nincorrecto,1,Autor,Inválido\n2,1,Autor,Segundo\n");
        var preview = importer.previewFile(file, 0, 50);
        assertThat(preview.summary().recordsProcessed()).isEqualTo(2);
        assertThat(preview.summary().errors()).isEqualTo(1);
        assertThat(preview.createdBooks()).extracting("eplId").containsExactly(1L, 2L);
        assertThat(importer.importFile(file, false))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(2, 1, 0, 2, 0, null));
    }

    @Test
    void preservesCountsAndDatesAcrossBatchBoundary() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int id = 1; id <= 1001; id++) rows.append(id).append(",1,Autor,Libro\n");
        Path file = csv(rows.toString());
        assertThat(importer.importFile(file, true))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(1001, 0, 0, 1001, 0, 0L));
        var preview = importer.previewFile(file, 0, 50);
        assertThat(preview.summary().recordsUnchanged()).isEqualTo(1001);
        assertThat(importer.importFile(file, false))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(1001, 0, 0, 0, 1001, 0L));
        assertThat(repository.findById(1001L).orElseThrow().getLastModifiedDate()).isNull();
    }

    @Test
    void previewRejectsInvalidPagination() {
        assertThatIllegalArgumentException().isThrownBy(() -> importer.previewFile(directory.resolve("x"), -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> importer.previewFile(directory.resolve("x"), 0, 0));
    }

    @Test
    void previewAcceptsLargePageSize() throws Exception {
        var result = importer.previewFile(csv("1,1,Autor,Original\n"), 0, 10000);
        assertThat(result.size()).isEqualTo(10000);
    }

    @Test
    void failedReplacementRollsBackDeletion() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        assertThatThrownBy(() -> importer.importFile(directory.resolve("missing.csv"), true))
                .isInstanceOf(IOException.class);
        assertThat(repository.existsById(1L)).isTrue();
    }

    @Test
    void importsOptionalCoversAndRepairsOnlyExtraUrlQuoteIncludingMultilineRecords() throws Exception {
        Path file = Files.writeString(directory.resolve("covers.csv"), """
                "EPL Id","Revisión","Autor","Título","Sinopsis","Portada"
                "1","1","Autor","Uno","Texto","https://example.org/1.jpg"
                "2","1","Autor","Dos","Texto","https://example.org/2.jpg""
                "3","1","Autor","Tres","Texto",""
                "4","1","Autor","Cuatro","Texto","   "
                "5","1","Autor","Cinco","Primera línea
                Segunda línea con ""comillas""\","https://example.org/5.jpg""
                "6","1","Autor","Seis","Texto","https://example.org/6.jpg"
                """.replace("\n", "\r\n"));
        var preview = importer.previewFile(file, 0, 20);
        assertThat(preview.summary().errors()).isZero();
        assertThat(preview.createdBooks()).extracting("coverUrl").containsExactly(
                "https://example.org/1.jpg", "https://example.org/2.jpg", null, null,
                "https://example.org/5.jpg", "https://example.org/6.jpg");
        assertThat(importer.importFile(file, false))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(6, 0, 0, 6, 0, 0L));
        assertThat(repository.findById(5L).orElseThrow().getSynopsis())
                .contains("Primera línea\nSegunda línea con \"comillas\"");
        assertThat(repository.findById(2L).orElseThrow().getCoverUrl()).isEqualTo("https://example.org/2.jpg");
        assertThat(repository.findById(3L).orElseThrow().getCoverUrl()).isNull();
    }

    @Test
    void coverChangesAppearInPreviewAndMissingColumnClearsStoredCover() throws Exception {
        Path file = Files.writeString(directory.resolve("covers.csv"),
                "EPL Id,Revisión,Autor,Título,Portada\n1,1,Autor,Original,https://example.org/1.jpg\n");
        importer.importFile(file, false);
        var checked = repository.findById(1L).orElseThrow();
        checked.setCoverAvailable(false);
        repository.saveAndFlush(checked);
        assertThat(importer.importFile(file, false).unchanged()).isEqualTo(1);
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isFalse();
        Path sameUrl = Files.writeString(directory.resolve("same-url.csv"),
                "EPL Id,Revisión,Autor,Título,Portada\n1,2,Autor,Original,https://example.org/1.jpg\n");
        importer.importFile(sameUrl, false);
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isFalse();
        importer.importFile(file, false);
        Path changed = Files.writeString(directory.resolve("changed.csv"),
                "EPL Id,Revisión,Autor,Título,Portada\n1,1,Autor,Original,https://example.org/2.jpg\n");
        var preview = importer.previewFile(changed, 0, 20);
        assertThat(preview.updatedBooks().getFirst().changedFields()).containsExactly("coverUrl");
        assertThat(repository.findById(1L).orElseThrow().getCoverUrl()).endsWith("/1.jpg");
        assertThat(importer.importFile(changed, false).updated()).isEqualTo(1);
        assertThat(repository.findById(1L).orElseThrow().getCoverUrl()).endsWith("/2.jpg");
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isNull();
        importer.importFile(csv("1,1,Autor,Original\n2,1,Autor,Nuevo\n"), false);
        assertThat(repository.findAll()).allSatisfy(book -> assertThat(book.getCoverUrl()).isNull());
    }

    @Test
    void legacyQuotedEmptyAndEscapedFinalFieldsAreUnchanged() throws Exception {
        Path file = Files.writeString(directory.resolve("legacy.csv"), """
                "EPL Id","Revisión","Autor","Título","Sinopsis"
                "1","1","Autor","Uno",""
                "2","1","Autor","Dos","Termina en ""comillas""\"
                """ );
        assertThat(importer.importFile(file, false).processed()).isEqualTo(2);
        assertThat(repository.findById(2L).orElseThrow().getSynopsis()).isEqualTo("Termina en \"comillas\"");
    }

    @Test void excessiveConversionErrorsAbortAndRollBackCatalogReplacement() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        Path invalid = csv("2,1,Autor,Nuevo\n" + "invalid,1,Autor,Invalid\n".repeat(CatalogImportLimits.CONVERSION_ERRORS + 1));
        assertThatThrownBy(() -> importer.importFile(invalid, true)).hasStackTraceContaining("1000 errores de conversión");
        assertThat(repository.findById(1L).orElseThrow().getTitle()).isEqualTo("Original");
        assertThat(repository.existsById(2L)).isFalse();
    }

    @Test void csvWithoutAnyValidBookCannotEraseTheCatalog() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        assertThatThrownBy(() -> importer.importFile(csv("invalid,1,Autor,Invalid\n"), true))
            .hasMessageContaining("ningún libro válido");
        assertThat(repository.findById(1L).orElseThrow().getTitle()).isEqualTo("Original");
    }

    @Test void replacementRejectsPartialErrorsAndDuplicatesBeforeDeletingAnything() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        var original = repository.findById(1L).orElseThrow();
        jdbc.execute("CREATE TRIGGER block_catalog_delete BEFORE DELETE ON catalog_books BEGIN SELECT RAISE(ABORT, 'Deletion must not run'); END");
        for (String rows : new String[]{"2,1,Autor,Nuevo\ninvalid,1,Autor,Incorrecto\n", "2,1,Autor,Nuevo\n2,1,Autor,Duplicado\n"}) {
            assertThatThrownBy(() -> importer.importFile(csv(rows), true))
                .isInstanceOf(com.rlibanez.eplsync.exception.CatalogValidationException.class)
                .hasMessage("No se puede reemplazar el catálogo: el CSV contiene 1 registro con errores. El catálogo anterior se ha conservado.");
            assertThat(repository.findById(1L).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
            assertThat(repository.count()).isEqualTo(1);
        }
    }
    @Test void emptyReplacementIsRejectedBeforeDeletion() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        jdbc.execute("CREATE TRIGGER block_catalog_delete BEFORE DELETE ON catalog_books BEGIN SELECT RAISE(ABORT, 'Deletion must not run'); END");
        for (String content : new String[]{"", "EPL Id,Revisión,Autor,Título\n"}) {
            Path file = Files.writeString(directory.resolve("empty.csv"), content);
            assertThatThrownBy(() -> importer.importFile(file, true))
                .isInstanceOf(com.rlibanez.eplsync.exception.CatalogValidationException.class);
            assertThat(repository.findById(1L).orElseThrow().getTitle()).isEqualTo("Original");
        }
    }
    @Test void replacementRollsBackDeletionAndEarlierInsertsWhenPersistenceFails() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        var original = repository.findById(1L).orElseThrow();
        jdbc.execute("CREATE TRIGGER fail_second_insert BEFORE INSERT ON catalog_books WHEN NEW.epl_id=3 BEGIN SELECT RAISE(ABORT, 'Persistence failure'); END");
        assertThatThrownBy(() -> importer.importFile(csv("2,1,Autor,Nuevo\n3,1,Autor,Otro\n"), true))
            .isInstanceOf(RuntimeException.class);
        assertThat(repository.findById(1L).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
        assertThat(repository.count()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"-1,1,Autor,Invalid","0,1,Autor,Invalid","3,-1,Autor,Invalid",
        "3,0,Autor,Invalid","3,Infinity,Autor,Invalid","3,NaN,Autor,Invalid","3,1,   ,Invalid","3,1,Autor,   "})
    void semanticErrorsAreSharedAndCannotReplaceTheCatalog(String invalid) throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"),true);
        var path=csv("2,1,Autor,Valid\n"+invalid+"\n");
        var preview=importer.previewFile(path,0,20);
        assertThat(preview.summary().recordsProcessed()).isEqualTo(1);
        assertThat(preview.summary().errors()).isEqualTo(1);
        assertThatThrownBy(() -> importer.importFile(path,true))
            .hasMessageContaining("El catálogo anterior se ha conservado").hasMessageContaining("Fila 3");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(1L).orElseThrow().getTitle()).isEqualTo("Original");
        var update=importer.importFile(path,false);
        assertThat(update.processed()).isEqualTo(1);assertThat(update.errors()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(2);
    }
    @Test void extensiveAuthorListsAreAcceptedAndOversizedFieldsAreRejected() throws Exception {
        String authors="a".repeat(16_384);
        importer.importFile(csv("1,1,"+authors+",Title\n"),true);
        assertThat(repository.findById(1L).orElseThrow().getAuthor()).isEqualTo(authors);
        var path=csv("2,1,Author,Valid\n3,1,"+authors+"a,Invalid\n");
        assertThat(importer.previewFile(path,0,20).summary().errors()).isEqualTo(1);
        assertThatThrownBy(() -> importer.importFile(path,true)).hasMessageContaining("Autor").hasMessageContaining("16384");
        assertThat(repository.count()).isEqualTo(1);
    }
}
