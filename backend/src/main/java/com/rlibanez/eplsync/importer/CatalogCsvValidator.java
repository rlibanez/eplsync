package com.rlibanez.eplsync.importer;

import com.opencsv.CSVReaderBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Streaming structural validation, before catalog writes; conversion errors remain the importer's responsibility. */
final class CatalogCsvValidator {
    private CatalogCsvValidator() {}
    static void validate(Path path, long deadline) throws IOException {
        if (Files.size(path) > CatalogImportLimits.CSV_BYTES)
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 512 MiB");
        CatalogImportLimits.checkDeadline(deadline);
        try (var csv = new CSVReaderBuilder(new CoverCsvReader(Files.newBufferedReader(path)))
                .withMultilineLimit(CatalogImportLimits.RECORD_LINES).build()) {
            String[] header = csv.readNext();
            if (header == null) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV está vacío");
            var names = new HashSet<String>();
            for (String field : header) {
                if (!names.add(field.strip().toUpperCase(java.util.Locale.ROOT))) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV contiene columnas duplicadas");
            }
            if (!names.containsAll(Set.of("EPL ID", "TÍTULO", "AUTOR", "REVISIÓN")))
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV no contiene las columnas obligatorias: EPL Id, Título, Autor y Revisión");
            int rows = 0;
            for (String[] row; (row = csv.readNext()) != null;) {
                CatalogImportLimits.checkDeadline(deadline);
                if (++rows > CatalogImportLimits.RECORDS)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 1000000 registros");
                if (row.length != header.length)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV contiene una fila con un número de columnas incorrecto");
            }
            if (rows == 0) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV no contiene libros");
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV debe ser un archivo de texto UTF-8 válido", ex);
        } catch (com.opencsv.exceptions.CsvMalformedLineException | com.opencsv.exceptions.CsvMultilineLimitBrokenException ex) {
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV contiene comillas sin cerrar o supera el máximo de 1000 líneas por registro", ex);
        } catch (com.opencsv.exceptions.CsvValidationException ex) {
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV tiene un formato inválido", ex);
        }
    }
}
