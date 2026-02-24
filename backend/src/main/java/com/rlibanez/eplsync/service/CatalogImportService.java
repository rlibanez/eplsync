package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.dto.ImportResult;
import com.rlibanez.eplsync.exception.CatalogImportException;
import com.rlibanez.eplsync.exception.CatalogImportInterruptedException;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter.ImportStats;
import com.rlibanez.eplsync.importer.FileDownloader;
import com.rlibanez.eplsync.importer.ZipExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Servicio para importar el catálogo de libros de ePubLibre.
 */
@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);

    private static final String EPUBLIBRE_ZIP_URL = "https://epublibre.org/rssweb/csv/epub.zip";
    // private static final String EPUBLIBRE_ZIP_URL =
    // "https://www.dropbox.com/s/a9r4p7oyaftaz1b/csv_full_imgs.zip?dl=1";

    private final FileDownloader fileDownloader;
    private final ZipExtractor zipExtractor;
    private final CatalogBookCsvImporter csvImporter;

    public CatalogImportService(FileDownloader fileDownloader,
            ZipExtractor zipExtractor,
            CatalogBookCsvImporter csvImporter) {
        this.fileDownloader = fileDownloader;
        this.zipExtractor = zipExtractor;
        this.csvImporter = csvImporter;
    }

    /**
     * Importa el catálogo completo desde la URL oficial de ePubLibre.
     * 
     * @return Resumen del proceso de importación.
     */
    public ImportResult importCatalog() {
        return importCatalog(EPUBLIBRE_ZIP_URL);
    }

    /**
     * Importa el catálogo desde una URL específica.
     * 
     * @param zipUrl URL del archivo ZIP que contiene el CSV.
     * @return Resumen del proceso de importación.
     */
    public ImportResult importCatalog(String zipUrl) {
        log.info("Iniciando importación del catálogo desde: {}", zipUrl);

        Path zipFile = null;
        Path csvFile = null;

        try {
            // Paso 1: Descargar el ZIP
            zipFile = fileDownloader.download(zipUrl, "epublibre-", ".zip");
            log.info("ZIP descargado: {} ({} bytes)", zipFile, Files.size(zipFile));

            // Paso 2: Extraer el CSV del ZIP
            csvFile = zipExtractor.extractCsv(zipFile);
            log.info("CSV extraído: {} ({} bytes)", csvFile, Files.size(csvFile));

            // Paso 3: Procesar CSV y guardar en BD
            boolean truncateBeforeImport = true;
            ImportStats stats = csvImporter.importFile(csvFile, truncateBeforeImport);

            log.info("Importación completada. Procesadas={}, Errores={}", stats.processed(), stats.errors());

            return new ImportResult(
                    true,
                    "Importación completada",
                    stats.processed(),
                    stats.errors());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogImportInterruptedException("La operación de importación fue interrumpida", e);
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
