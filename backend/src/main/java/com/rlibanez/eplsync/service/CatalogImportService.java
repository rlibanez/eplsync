package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.dto.ImportResult;
import com.rlibanez.eplsync.exception.CatalogImportException;
import com.rlibanez.eplsync.exception.CatalogImportInterruptedException;
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

    public CatalogImportService(FileDownloader fileDownloader,
            ZipExtractor zipExtractor) {
        this.fileDownloader = fileDownloader;
        this.zipExtractor = zipExtractor;
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

            // TODO: Paso 3 - Procesar el CSV y guardar en BD
            // Aquí se procesará el CSV antes de borrarlo

            // Por ahora solo retornamos éxito con la descarga y extracción
            log.info("Descarga y extracción completadas. CSV disponible en: {}", csvFile);

            return new ImportResult(
                    true,
                    "Archivo descargado y extraído exitosamente (pendiente procesamiento)",
                    0, // TODO: contador de registros procesados
                    0 // TODO: contador de errores
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogImportInterruptedException("La operación de importación fue interrumpida", e);
        } catch (IOException e) {
            throw new CatalogImportException("Error de entrada/salida durante la importación", e);
        } finally {
            cleanupTempFile(zipFile);
            // TODO: Borrar csvFile después de implementar el paso 3
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
