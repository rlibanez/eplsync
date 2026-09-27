package com.rlibanez.eplsync.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class ZipExtractorTests {
    @TempDir Path directory;

    @Test
    void failedExtractionRemovesCsvAlreadyExtracted() throws Exception {
        String prefix = "extracttest" + UUID.randomUUID().toString().replace("-", "");
        Path zip = directory.resolve("multiple.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : new String[] {prefix + ".csv", "second.csv"}) {
                output.putNextEntry(new ZipEntry(name));
                output.write("EPL Id,Revisión,Autor,Título\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ZipExtractor().extractCsv(zip))
                .withMessageContaining("múltiples archivos CSV");
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()).isEmpty();
        }
    }
}
