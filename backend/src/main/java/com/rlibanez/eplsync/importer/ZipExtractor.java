package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import org.springframework.stereotype.Component;

/** Validates every entry, including discarded files, before publishing the extracted CSV. */
@Component
public class ZipExtractor {
    private final long maxEntryBytes;
    private final long maxExpandedBytes;
    private final int maxEntries;
    private final int maxRatio;
    private final java.time.Duration timeout;

    public ZipExtractor() {
        this(CatalogImportLimits.CSV_BYTES, CatalogImportLimits.EXPANDED_BYTES,
            CatalogImportLimits.ENTRIES, CatalogImportLimits.COMPRESSION_RATIO, CatalogImportLimits.EXTRACTION_TIME);
    }
    ZipExtractor(long maxEntryBytes, long maxExpandedBytes, int maxEntries, int maxRatio, java.time.Duration timeout) {
        this.maxEntryBytes = maxEntryBytes;
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxEntries = maxEntries;
        this.maxRatio = maxRatio;
        this.timeout = timeout;
    }

    public record ExtractedCsv(Path path, String name, String modifiedAt) {}

    public Path extractCsv(Path zipFile) throws IOException {
        return extractCsvWithMetadata(zipFile).path();
    }

    public ExtractedCsv extractCsvWithMetadata(Path zipFile) throws IOException {
        CatalogImportLimits.checkZip(zipFile);
        long deadline = System.nanoTime() + timeout.toNanos();
        ZipArchiveValidator.validate(zipFile, maxEntries, deadline);
        Path extracted = null;
        String name = null;
        String modifiedAt = null;
        long total = 0;
        int count = 0;
        try (var zip = new ZipFile(zipFile.toFile()); var input = new EntryStream(Files.newInputStream(zipFile))) {
            var indexed = new java.util.HashMap<String, ZipEntry>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                CatalogFileNames.validateEntry(entry.getName());
                if (indexed.putIfAbsent(entry.getName(), entry) != null)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP contiene nombres de entrada duplicados");
            }
            for (ZipEntry local; (local = input.getNextEntry()) != null;) {
                CatalogImportLimits.checkDeadline(deadline);
                var entry = indexed.remove(local.getName());
                if (entry == null || entry.getMethod() != local.getMethod())
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP contiene entradas incompatibles con su directorio central");
                if (++count > maxEntries)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera el máximo de 128 entradas");
                if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getCompressedSize() > Files.size(zipFile) || entry.getSize() > maxEntryBytes)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("Una entrada del ZIP supera el máximo de 512 MiB o tiene un tamaño inválido");
                long ratioLimit = Math.max(1, entry.getCompressedSize()) * maxRatio;
                if (entry.getSize() > ratioLimit)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera la relación máxima de compresión de 200:1");
                CatalogFileNames.validateEntry(local.getName());
                String entryName = entry.getName().replace('\\', '/');
                boolean csv = !entry.isDirectory() && entryName.toLowerCase(Locale.ROOT).endsWith(".csv");
                if (csv) {
                    if (extracted != null) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP contiene múltiples archivos CSV");
                    name = CatalogFileNames.display(entryName, "catalog.csv");
                    modifiedAt = entry.getTimeLocal() == null ? null : entry.getTimeLocal().toString();
                    extracted = Files.createTempFile("eplsync-csv-", ".csv");
                }
                long bytes = 0;
                var crc = new CRC32();
                try (var output = csv ? Files.newOutputStream(extracted) : OutputStream.nullOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        CatalogImportLimits.checkDeadline(deadline);
                        bytes += read;
                        total += read;
                        if (bytes > maxEntryBytes || bytes > entry.getSize() || bytes > ratioLimit
                                || total > maxExpandedBytes)
                            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera los límites de descompresión del catálogo");
                        crc.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                // The inflater reports consumed compressed bytes, independently of forged ZIP metadata.
                long consumed = entry.getMethod() == ZipEntry.DEFLATED ? input.compressedBytes() : bytes;
                if (consumed != entry.getCompressedSize() || bytes > Math.max(1, consumed) * maxRatio)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP contiene un tamaño comprimido incorrecto o supera la relación máxima de compresión de 200:1");
                if (bytes != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP está dañado: tamaño o CRC incorrecto");
            }
            if (!indexed.isEmpty()) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP contiene entradas ausentes o truncadas");
            if (extracted == null) throw new com.rlibanez.eplsync.exception.CatalogValidationException("No se encontró ningún archivo CSV en el ZIP");
            CatalogCsvValidator.validate(extracted, deadline);
            return new ExtractedCsv(extracted, name, modifiedAt);
        } catch (IOException | RuntimeException ex) {
            if (extracted != null) {
                try { Files.deleteIfExists(extracted); }
                catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            if (ex instanceof java.io.EOFException)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP está incompleto o dañado", ex);
            if (ex instanceof IllegalArgumentException && !(ex instanceof com.rlibanez.eplsync.exception.CatalogValidationException))
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP contiene metadatos inválidos", ex);
            if (ex instanceof java.util.zip.ZipException)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP está dañado o no es un archivo ZIP compatible", ex);
            throw ex;
        }
    }
    private static final class EntryStream extends ZipInputStream {
        EntryStream(java.io.InputStream input) { super(input); }
        long compressedBytes() { return inf.getBytesRead(); }
    }
}
