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

    private static final byte[] CSV = "EPL Id,Título,Autor,Revisión\n1,Libro,Autor,1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private Path archive(String name, byte[] data, boolean stored) throws Exception {
        Path path = directory.resolve("archive.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            var entry = new ZipEntry(name);
            if (stored) {
                var crc = new java.util.zip.CRC32(); crc.update(data);
                entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
            }
            zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
        }
        return path;
    }
    private java.util.Set<Path> temporaryCsvs() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("eplsync-csv-")).collect(java.util.stream.Collectors.toSet());
        }
    }
    @Test void acceptsOneNestedCsvAndChecksDiscardedEntriesWithoutExtractingTheirPaths() throws Exception {
        Path path = directory.resolve("safe.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : new String[]{"folder/catalog.csv", "ignored.txt"}) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(name.endsWith(".csv") ? CSV : new byte[]{1,2}); zip.closeEntry();
            }
        }
        var csv = new ZipExtractor().extractCsvWithMetadata(path);
        try { assertThat(Files.readAllBytes(csv.path())).isEqualTo(CSV); assertThat(csv.name()).isEqualTo("catalog.csv"); }
        finally { Files.deleteIfExists(csv.path()); }
        assertThat(Files.exists(directory.resolve("catalog.csv"))).isFalse();
    }
    @Test void rejectsCompressionBombEvenInDiscardedEntry() throws Exception {
        for (String name : new String[]{"catalog.csv", "ignored.bin"}) {
            Path zip = archive(name, new byte[8 * 1024 * 1024], false);
            assertThat(Files.size(zip)).isLessThan(10_000);
            assertThatThrownBy(() -> new ZipExtractor().extractCsv(zip)).hasMessageContaining("200:1");
        }
    }
    @Test void rejectsTooManyEntriesWithoutCreatingTemporaryCsv() throws Exception {
        var before = temporaryCsvs();
        Path path = directory.resolve("many.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("catalog.csv")); zip.write(CSV); zip.closeEntry();
            for (int i=0; i<CatalogImportLimits.ENTRIES; i++) {
                zip.putNextEntry(new ZipEntry("ignored"+i)); zip.closeEntry();
            }
        }
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("128 entradas");
        assertThat(temporaryCsvs()).isEqualTo(before);
    }
    @Test void rejectsDamagedCrcAndCleansTemporaryCsv() throws Exception {
        var before = temporaryCsvs();
        Path path = archive("catalog.csv", CSV, true);
        byte[] bytes = Files.readAllBytes(path);
        bytes[30 + "catalog.csv".length()] ^= 1;
        Files.write(path, bytes);
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("dañado");
        assertThat(temporaryCsvs()).isEqualTo(before);
    }
    @Test void rejectsTruncatedZipAndOversizedCompressedInput() throws Exception {
        Path path = archive("catalog.csv", CSV, false);
        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 20));
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("dañado");
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) { file.setLength(CatalogImportLimits.ZIP_BYTES + 1); }
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("128 MiB");
    }
    @Test void rejectsInvalidCsvBeforeReturningItsPathAndCleansTemporaryFile() throws Exception {
        var before = temporaryCsvs();
        Path path = archive("catalog.csv", "not a catalog".getBytes(), false);
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("obligatorias");
        assertThat(temporaryCsvs()).isEqualTo(before);
    }

    @Test void enforcesEntryAndCombinedExpansionBudgets() throws Exception {
        var small = new ZipExtractor(1024, 1200, 128, 200, java.time.Duration.ofSeconds(5));
        Path path = directory.resolve("combined.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("catalog.csv")); zip.write(CSV); zip.closeEntry();
            for (int i=0; i<2; i++) {
                byte[] data = new byte[800]; new java.util.Random(i).nextBytes(data);
                zip.putNextEntry(new ZipEntry("ignored"+i)); zip.write(data); zip.closeEntry();
            }
        }
        var before = temporaryCsvs();
        assertThatThrownBy(() -> small.extractCsv(path)).hasMessageContaining("límites de descompresión");
        assertThat(temporaryCsvs()).isEqualTo(before);
        byte[] data = new byte[1025]; new java.util.Random(1).nextBytes(data);
        Path oversized = archive("ignored.bin", data, false);
        assertThatThrownBy(() -> small.extractCsv(oversized)).hasMessageContaining("entrada del ZIP");
    }
    @Test void checksActualOutputAgainstDishonestCentralDirectorySize() throws Exception {
        Path path = archive("catalog.csv", CSV, true);
        byte[] bytes = Files.readAllBytes(path);
        for (int i=0; i<bytes.length-24; i++) {
            if (bytes[i]=='P' && bytes[i+1]=='K' && bytes[i+2]==1 && bytes[i+3]==2) {
                java.nio.ByteBuffer.wrap(bytes, i+24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1);
                break;
            }
        }
        Files.write(path, bytes);
        var before = temporaryCsvs();
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("límites de descompresión");
        assertThat(temporaryCsvs()).isEqualTo(before);
    }
    @Test void enforcesExtractionDeadlineAndInterruption() throws Exception {
        Path path = archive("catalog.csv", CSV, false);
        var timed = new ZipExtractor(1024,2048,128,200,java.time.Duration.ZERO);
        assertThatThrownBy(() -> timed.extractCsv(path)).hasMessageContaining("tiempo máximo");
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("interrumpido"); }
        finally { Thread.interrupted(); }
    }

    @Test void refusesFalseEntryCountBeforeConstructingZipIndex() throws Exception {
        Path path = directory.resolve("false-count.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (int i=0; i<129; i++) { zip.putNextEntry(new ZipEntry("entry"+i)); zip.closeEntry(); }
        }
        byte[] bytes = Files.readAllBytes(path);
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(bytes.length-22+8,(short)1); buffer.putShort(bytes.length-22+10,(short)1);
        Files.write(path, bytes);
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("128 entradas");
    }

    @Test void rejectsForgedCompressedSizeUsingActualInflaterConsumption() throws Exception {
        Path path = directory.resolve("forged-compression.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.setComment("x".repeat(60_000));
            zip.putNextEntry(new ZipEntry("catalog.csv")); zip.write(new byte[8 * 1024 * 1024]); zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(path);
        for (int i=0; i<bytes.length-24; i++) {
            if (bytes[i]=='P' && bytes[i+1]=='K' && bytes[i+2]==1 && bytes[i+3]==2) {
                java.nio.ByteBuffer.wrap(bytes, i+20, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(50_000);
                break;
            }
        }
        Files.write(path, bytes);
        var before = temporaryCsvs();
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("tamaño comprimido incorrecto");
        assertThat(temporaryCsvs()).isEqualTo(before);
    }

    private Path zip64End() throws Exception {
        Path path = archive("catalog.csv", CSV, false);
        byte[] classic = Files.readAllBytes(path);
        int end = classic.length - 22;
        var original = java.nio.ByteBuffer.wrap(classic).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long centralSize = Integer.toUnsignedLong(original.getInt(end+12));
        long centralOffset = Integer.toUnsignedLong(original.getInt(end+16));
        var result = java.nio.ByteBuffer.allocate(classic.length + 76).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        result.put(classic,0,end);
        result.putInt(0x06064b50).putLong(44).putShort((short)45).putShort((short)45);
        result.putInt(0).putInt(0).putLong(1).putLong(1).putLong(centralSize).putLong(centralOffset);
        result.putInt(0x07064b50).putInt(0).putLong(end).putInt(1);
        byte[] eocd = java.util.Arrays.copyOfRange(classic,end,classic.length);
        var marker = java.nio.ByteBuffer.wrap(eocd).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        marker.putShort(8,(short)-1).putShort(10,(short)-1).putInt(12,-1).putInt(16,-1);
        result.put(eocd);
        return Files.write(path,result.array());
    }
    @Test void acceptsZip64EndRecordsWithinNormalResourceLimits() throws Exception {
        Path zip = zip64End();
        var csv = new ZipExtractor().extractCsv(zip);
        try { assertThat(Files.readAllBytes(csv)).isEqualTo(CSV); }
        finally { Files.deleteIfExists(csv); }
    }
    @Test void acceptsForcedZip64LocalSizesForSmallFiles() throws Exception {
        Path path = archive("catalog.csv",CSV,true);
        byte[] original = Files.readAllBytes(path);
        int localHeader = 30 + "catalog.csv".length();
        var result = java.nio.ByteBuffer.allocate(original.length+20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        result.put(original,0,localHeader);
        result.putShort(4,(short)45).putInt(18,-1).putInt(22,-1).putShort(28,(short)20);
        result.position(localHeader);
        result.putShort((short)1).putShort((short)16).putLong(CSV.length).putLong(CSV.length);
        result.put(original,localHeader,original.length-localHeader);
        int end = result.capacity()-22;
        result.putInt(end+16,result.getInt(end+16)+20);
        Files.write(path,result.array());
        var csv = new ZipExtractor().extractCsv(path);
        try { assertThat(Files.readAllBytes(csv)).isEqualTo(CSV); }
        finally { Files.deleteIfExists(csv); }
    }
    @Test void rejectsZip64ExcessiveCountsNegativeOffsetsAndInconsistentSizes() throws Exception {
        for (int field : new int[]{32,40,48}) {
            Path path = zip64End();
            byte[] bytes = Files.readAllBytes(path);
            int record = bytes.length - 98;
            var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (field == 32) { buffer.putLong(record+24,129); buffer.putLong(record+32,129); }
            else buffer.putLong(record+field,Long.MAX_VALUE);
            Files.write(path,bytes);
            assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsTruncatedZip64RecordAndMultiVolumeLocator() throws Exception {
        for (int offset : new int[]{4,16}) {
            Path path = zip64End();
            byte[] bytes = Files.readAllBytes(path);
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.length-42+offset,2);
            Files.write(path,bytes);
            assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("dañado");
        }
        Path path = zip64End(); byte[] bytes = Files.readAllBytes(path);
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(bytes.length-98+4,100);
        Files.write(path,bytes);
        assertThatThrownBy(() -> new ZipExtractor().extractCsv(path)).hasMessageContaining("dañado");
    }
    @Test void rejectsUnsafeEntryNamesBeforeCreatingTemporaryFiles() throws Exception {
        var before = temporaryCsvs();
        for (String name : new String[]{"../../catalog.csv", "/catalog.csv", "C:\\catalog.csv", "folder/../catalog.csv", "evil\n.csv", "evil\u202e.csv", "x".repeat(201)+".csv"}) {
            Path path = archive(name,CSV,false);
            assertThatThrownBy(() -> new ZipExtractor().extractCsv(path))
                .isInstanceOf(com.rlibanez.eplsync.exception.CatalogValidationException.class);
            assertThat(temporaryCsvs()).isEqualTo(before);
        }
    }
}
