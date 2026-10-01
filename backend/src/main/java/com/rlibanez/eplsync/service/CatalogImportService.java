package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.dto.ImportResult;
import com.rlibanez.eplsync.dto.ImportPreviewResult;
import com.rlibanez.eplsync.exception.CatalogImportException;
import com.rlibanez.eplsync.exception.CatalogDownloadException;
import com.rlibanez.eplsync.exception.CatalogImportInterruptedException;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter.ImportStats;
import com.rlibanez.eplsync.importer.FileDownloader;
import com.rlibanez.eplsync.importer.ZipExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Servicio para importar el catálogo de libros de ePubLibre.
 */
@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private final String catalogZipUrl;
    private final com.rlibanez.eplsync.repository.CatalogMetadataRepository metadataRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    private final FileDownloader fileDownloader;
    private final ZipExtractor zipExtractor;
    private final CatalogBookCsvImporter csvImporter;

    public CatalogImportService(FileDownloader fileDownloader,
            ZipExtractor zipExtractor,
            CatalogBookCsvImporter csvImporter,
            @Value("${eplsync.catalog.zip-url}") String catalogZipUrl,
            com.rlibanez.eplsync.repository.CatalogMetadataRepository metadataRepository,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.metadataRepository = metadataRepository;
        this.transactions = transactions;
        this.catalogZipUrl = catalogZipUrl;
        this.fileDownloader = fileDownloader;
        this.zipExtractor = zipExtractor;
        this.csvImporter = csvImporter;
    }

    /**
     * Reemplaza el catálogo completo desde la URL configurada.
     * 
     * @return Resumen del proceso de importación.
     */
    public ImportResult importCatalog() {
        return importCatalog(catalogZipUrl);
    }

    /**
     * Reemplaza el catálogo desde una URL específica (null usa la URL configurada).
     * 
     * @param zipUrl URL del archivo ZIP que contiene el CSV.
     * @return Resumen del proceso de importación.
     */
    public ImportResult importCatalog(String zipUrl) {
        return importCatalog(zipUrl, true);
    }

    /** Actualiza el catálogo sin borrarlo; null usa la URL configurada. */
    public ImportResult updateCatalog(String zipUrl) {
        return importCatalog(zipUrl, false);
    }

    private ImportResult importCatalog(String zipUrl, boolean truncateBeforeImport) {
        long started = System.nanoTime();
        return withCatalogFile(zipUrl, csv -> {
            var metadata = new com.rlibanez.eplsync.model.CatalogMetadata();
            metadata.setSourceUrl(zipUrl == null ? catalogZipUrl : zipUrl);
            metadata.setSourceFileName(csv.name());
            metadata.setSourceModifiedAt(csv.modifiedAt());
            metadata.setImportMode(truncateBeforeImport ? "REPLACE" : "UPDATE");
            try (var input = Files.newInputStream(csv.path())) {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                metadata.setSourceSha256(java.util.HexFormat.of().formatHex(digest.digest()));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            return transactions.execute(status -> {
                try {
                    ImportStats stats = csvImporter.importFile(csv.path(), truncateBeforeImport);
                    metadata.setTotalRows((long) stats.processed() + stats.errors());
                    metadata.setInsertedRows(stats.created());
                    metadata.setUpdatedRows(stats.updated());
                    metadata.setUnchangedRows(stats.unchanged());
                    metadata.setErrorRows(stats.errors());
                    metadata.setImportedAt(java.time.Instant.now());
                    metadata.setDurationMs((System.nanoTime() - started) / 1_000_000);
                    metadataRepository.saveAndFlush(metadata);
                    return new ImportResult(true, "Importación completada", stats.processed(), stats.errors(),
                            stats.updated(), stats.created(), stats.unchanged(), metadata);
                } catch (IOException e) {
                    throw new CatalogImportException("Error durante la importación", e);
                }
            });
        });
    }

    public ImportPreviewResult previewCatalog(String zipUrl, int page, int size) {
        return withCatalogFile(zipUrl, csv -> csvImporter.previewFile(csv.path(), page, size));
    }

    @FunctionalInterface
    private interface CatalogFileOperation<T> {
        T apply(ZipExtractor.ExtractedCsv csvFile) throws IOException;
    }

    private <T> T withCatalogFile(String zipUrl, CatalogFileOperation<T> operation) {
        if (zipUrl == null) {
            zipUrl = catalogZipUrl;
        }
        log.info("Iniciando importación del catálogo desde: {}", zipUrl);

        Path zipFile = null;
        Path csvFile = null;

        try {
            // Paso 1: Descargar el ZIP
            zipFile = fileDownloader.download(zipUrl, "epublibre-", ".zip");
            log.info("ZIP descargado: {} ({} bytes)", zipFile, Files.size(zipFile));

            // Paso 2: Extraer el CSV del ZIP
            var extracted = zipExtractor.extractCsvWithMetadata(zipFile);
            csvFile = extracted.path();
            log.info("CSV extraído: {} ({} bytes), fecha del CSV: {}", csvFile, Files.size(csvFile),
                    extracted.modifiedAt() == null ? "desconocida" : extracted.modifiedAt());

            // Paso 3: Procesar el CSV según la operación solicitada: reemplazar, actualizar o previsualizar.
            return operation.apply(extracted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogImportInterruptedException("La operación de importación fue interrumpida", e);
        } catch (CatalogDownloadException e) {
            throw new CatalogImportException(e.getMessage(), e);
        } catch (IOException e) {
            throw new CatalogImportException("Error de entrada/salida durante la importación", e);
        } catch (Exception e) {
            throw new CatalogImportException("Error durante el procesamiento del CSV", e);
        } finally {
            cleanupTempFile(zipFile);
            cleanupTempFile(csvFile);
        }
    }

    /**
     * Elimina un archivo temporal de forma segura.
     *
     * @param file Ruta al archivo temporal a eliminar. Puede ser null.
     * @implNote Si ocurre un error al borrar, se registra en el log pero no se
     *           lanza excepción.
     */
    private void cleanupTempFile(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
                log.debug("Archivo temporal eliminado: {}", file);
            } catch (IOException e) {
                log.warn("No se pudo eliminar archivo temporal: {}", file, e);
            }
        }
    }
}
