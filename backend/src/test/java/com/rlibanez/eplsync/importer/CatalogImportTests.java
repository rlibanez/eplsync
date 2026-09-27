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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class CatalogImportTests {
    @Autowired CatalogBookCsvImporter importer;
    @Autowired CatalogBookRepository repository;
    @TempDir Path directory;

    @BeforeEach
    void clearCatalog() {
        repository.deleteAllInBatch();
    }

    private Path csv(String rows) throws IOException {
        return Files.writeString(directory.resolve("catalog.csv"),
                "EPL Id,Revisión,Autor,Título\n" + rows);
    }

    @Test
    void updateCountsChangesAndPreservesMissingBooksAndDates() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n2,1,Autor,Igual\n3,1,Autor,Ausente\n"), true);
        LocalDate created = repository.findById(1L).orElseThrow().getInsertDate();
        var result = importer.importFile(csv("1,2,Autor,Modificado\n2,1,Autor,Igual\n4,1,Autor,Nuevo\n"), false);
        assertThat(result).isEqualTo(new CatalogBookCsvImporter.ImportStats(3, 0, 1, 1, 1));
        assertThat(repository.count()).isEqualTo(4);
        var updated = repository.findById(1L).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Modificado");
        assertThat(updated.getInsertDate()).isEqualTo(created);
        assertThat(updated.getLastModifiedDate()).isEqualTo(LocalDate.now());
        assertThat(repository.findById(2L).orElseThrow().getLastModifiedDate()).isNull();
        assertThat(repository.findById(4L).orElseThrow().getInsertDate()).isEqualTo(LocalDate.now());
        var repeated = importer.importFile(directory.resolve("catalog.csv"), false);
        assertThat(repeated).isEqualTo(new CatalogBookCsvImporter.ImportStats(3, 0, 0, 0, 3));
    }

    @Test
    void replacementRemovesPreviousCatalogAndCreatesBooksAgain() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n2,1,Autor,Eliminar\n"), false);
        importer.importFile(csv("1,2,Autor,Modificado\n"), false);
        var result = importer.importFile(csv("1,3,Autor,Reemplazo\n3,1,Autor,Nuevo\n"), true);
        assertThat(result).isEqualTo(new CatalogBookCsvImporter.ImportStats(2, 0, 0, 2, 0));
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
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(4, 1, 1, 2, 1));
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
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(2, 1, 0, 2, 0));
    }

    @Test
    void preservesCountsAndDatesAcrossBatchBoundary() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int id = 1; id <= 1001; id++) rows.append(id).append(",1,Autor,Libro\n");
        Path file = csv(rows.toString());
        assertThat(importer.importFile(file, true))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(1001, 0, 0, 1001, 0));
        var preview = importer.previewFile(file, 0, 50);
        assertThat(preview.summary().recordsUnchanged()).isEqualTo(1001);
        assertThat(importer.importFile(file, false))
                .isEqualTo(new CatalogBookCsvImporter.ImportStats(1001, 0, 0, 0, 1001));
        assertThat(repository.findById(1001L).orElseThrow().getLastModifiedDate()).isNull();
    }

    @Test
    void previewRejectsInvalidPagination() {
        assertThatIllegalArgumentException().isThrownBy(() -> importer.previewFile(directory.resolve("x"), -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> importer.previewFile(directory.resolve("x"), 0, 501));
    }

    @Test
    void failedReplacementRollsBackDeletion() throws Exception {
        importer.importFile(csv("1,1,Autor,Original\n"), true);
        assertThatThrownBy(() -> importer.importFile(directory.resolve("missing.csv"), true))
                .isInstanceOf(IOException.class);
        assertThat(repository.existsById(1L)).isTrue();
    }
}
