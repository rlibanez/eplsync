package com.rlibanez.eplsync.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CatalogCsvValidatorTests {
    @TempDir Path directory;
    private static final String HEADER = "EPL Id,Título,Autor,Revisión\n";
    private void validate(String text) throws Exception {
        var path = Files.writeString(directory.resolve("catalog.csv"), text);
        CatalogCsvValidator.validate(path, System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos());
    }
    @Test void acceptsQuotedMultilineFieldsAndLegacyCoverRepair() throws Exception {
        validate("EPL Id,Título,Autor,Revisión,Sinopsis,Portada\n1,Libro,Autor,1,\"Primera línea\nSegunda con \"\"comillas\"\"\",\"https://example.org/cover.jpg\"\"\n");
    }
    @Test void rejectsEmptyAndHeaderOnlyFiles() {
        assertThatThrownBy(() -> validate("")).hasMessageContaining("vacío");
        assertThatThrownBy(() -> validate(HEADER)).hasMessageContaining("no contiene libros");
    }
    @Test void rejectsMissingDuplicateOrExcessiveColumns() {
        assertThatThrownBy(() -> validate("Título,Autor\nLibro,Autor\n")).hasMessageContaining("obligatorias");
        assertThatThrownBy(() -> validate("EPL Id,Título,Autor,Revisión,título\n1,L,A,1,L\n")).hasMessageContaining("duplicadas");
        assertThatThrownBy(() -> validate(HEADER + "1,L,A,1" + ",x".repeat(64) + "\n")).hasMessageContaining("64 columnas");
    }
    @Test void rejectsWrongColumnCountAndUnclosedQuotes() {
        assertThatThrownBy(() -> validate(HEADER + "1,Libro,Autor\n")).hasMessageContaining("columnas incorrecto");
        assertThatThrownBy(() -> validate(HEADER + "1,\"Libro,Autor,1\n")).isInstanceOfAny(java.io.IOException.class, IllegalArgumentException.class);
    }
    @Test void boundsPhysicalAndMultilineRecordsBeforeOpenCsvAllocatesThem() {
        assertThatThrownBy(() -> validate(HEADER + "1," + "x".repeat(CatalogImportLimits.RECORD_CHARS) + ",A,1\n"))
            .hasMessageContaining("1048576 caracteres");
        String line = "x".repeat(600_000);
        assertThatThrownBy(() -> validate(HEADER + "1,\"" + line + "\n" + line + "\",A,1\n"))
            .hasMessageContaining("1048576 caracteres");
    }
    @Test void rejectsBinaryDataAndInvalidUtf8() throws Exception {
        assertThatThrownBy(() -> validate(HEADER + "1,Libro\0,Autor,1\n")).hasMessageContaining("nulos");
        var path = Files.write(directory.resolve("invalid.csv"), new byte[]{(byte)0xc3, 0x28});
        assertThatThrownBy(() -> CatalogCsvValidator.validate(path, System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos())).hasMessageContaining("UTF-8 válido");
    }
    @Test void enforcesDeadline() throws Exception {
        var path = Files.writeString(directory.resolve("catalog.csv"), HEADER + "1,Libro,Autor,1\n");
        assertThatThrownBy(() -> CatalogCsvValidator.validate(path, System.nanoTime() - 1))
            .hasMessageContaining("tiempo máximo");
    }

    @Test void boundsTotalRecordCountAndMultilineLineCount() throws Exception {
        var path = directory.resolve("many.csv");
        try (var output = Files.newBufferedWriter(path)) {
            output.write(HEADER);
            for (int i=0; i<=CatalogImportLimits.RECORDS; i++) output.write("1,L,A,1\n");
        }
        assertThatThrownBy(() -> CatalogCsvValidator.validate(path, System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos()))
            .hasMessageContaining("1000000 registros");
        assertThatThrownBy(() -> validate(HEADER + "1,\"" + "x\n".repeat(1001) + "\",A,1\n"))
            .hasMessageContaining("1000 líneas");
    }

    @Test void matchesCaseInsensitiveHeadersAcceptedByTheImporter() throws Exception {
        validate("epl id,TÍTULO,autor,REVISIÓN\n1,Libro,Autor,1\n");
    }
}
