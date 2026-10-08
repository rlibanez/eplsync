package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.dto.CatalogBookCsvRow;
import com.rlibanez.eplsync.exception.CatalogValidationException;

/** Semantic limits shared by previews, updates and replacement preflight. */
final class CatalogBookValidation {
    private CatalogBookValidation() {}

    static void validate(CatalogBookCsvRow row) {
        if (row.getEplId() == null || row.getEplId() <= 0)
            throw new CatalogValidationException("EPL Id debe ser un entero mayor que cero");
        if (row.getRevision() == null || !Double.isFinite(row.getRevision()) || row.getRevision() <= 0)
            throw new CatalogValidationException("Revisión debe ser un número finito mayor que cero");
        required("Autor",row.getAuthor(),16_384);
        required("Título",row.getTitle(),4_096);
        length("Colección",row.getCollection(),4_096);
        length("Géneros",row.getGenres(),4_096);
        length("Sinopsis",row.getSynopsis(),524_288);
        length("Enlace(s)",row.getLinks(),65_536);
        length("Portada",row.getCoverUrl(),8_192);
    }

    private static void required(String field, String value, int maximum) {
        if (value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
            throw new CatalogValidationException(field + " no puede estar vacío ni contener solo espacios");
        length(field,value,maximum);
    }
    private static void length(String field, String value, int maximum) {
        if (value != null && value.codePointCount(0,value.length()) > maximum)
            throw new CatalogValidationException(field + " supera el máximo de " + maximum + " caracteres");
    }
}
