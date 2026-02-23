package com.rlibanez.eplsync.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utilidad para extraer archivos de un ZIP.
 */
@Component
public class ZipExtractor {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractor.class);

    /**
     * Extrae el único archivo CSV de un ZIP temporalmente.
     *
     * @param zipFile Ruta al archivo ZIP de entrada.
     * @return Ruta al archivo CSV extraído.
     * @throws IOException              Si ocurre un error de E/S al leer o extraer.
     * @throws IllegalArgumentException Si no hay o hay múltiples archivos CSV.
     */
    public Path extractCsv(Path zipFile) throws IOException {
        log.trace("Extrayendo archivo CSV desde ZIP: {}", zipFile);

        Path extractedFile = null;
        int csvCount = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');

                if (!entry.isDirectory() && entryName.toLowerCase().endsWith(".csv")) {
                    csvCount++;
                    if (csvCount > 1) {
                        throw new IllegalArgumentException(
                                "El ZIP contiene múltiples archivos CSV. No es posible determinar cuál usar: "
                                        + zipFile);
                    }
                    String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                    String prefix = fileName.replaceAll("[^a-zA-Z0-9]", "_");
                    String suffix = ".csv";
                    Path tempFile = Files.createTempFile(prefix + "-", suffix);
                    try {
                        Files.copy(zis, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        log.trace("CSV extraído exitosamente: {} ({} bytes)",
                                tempFile, Files.size(tempFile));
                        extractedFile = tempFile;
                    } catch (IOException e) {
                        Files.deleteIfExists(tempFile);
                        throw e;
                    }
                }
                zis.closeEntry();
            }
        }

        if (csvCount == 0) {
            throw new IllegalArgumentException(
                    "No se encontró ningún archivo CSV en el ZIP: " + zipFile);
        }
        
        return extractedFile;
    }
}
