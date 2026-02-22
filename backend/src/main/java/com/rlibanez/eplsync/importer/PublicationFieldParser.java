package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.model.enums.PublicationStatus;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parser para el campo "Publicado" del CSV de ePubLibre.
 * Extrae el estado de publicación y la fecha.
 */
public final class PublicationFieldParser {

    private static final DateTimeFormatter CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    public record PublicationInfo(PublicationStatus status, LocalDate date) {
    }

    private PublicationFieldParser() {
    }

    /**
     * Parsea el campo "Publicado" del CSV (ej: "P:20-02-2026").
     * 
     * @param raw Valor crudo del CSV.
     * @return PublicationInfo con estado y fecha.
     */
    public static PublicationInfo parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PublicationInfo(PublicationStatus.UNKNOWN, null);
        }

        String trimmed = raw.trim();
        String statusToken = trimmed;
        String dateToken = null;

        int colonIndex = trimmed.indexOf(':');
        if (colonIndex >= 0) {
            statusToken = trimmed.substring(0, colonIndex).trim();
            dateToken = trimmed.substring(colonIndex + 1).trim();
        }

        PublicationStatus status = PublicationStatus.fromCode(statusToken);
        LocalDate date = parseDate(dateToken);

        return new PublicationInfo(status, date);
    }

    /**
     * Parsea una fecha en formato dd-MM-uuuu (ej: "20-02-2026").
     *
     * @param token Cadena de fecha a parsear.
     * @return Fecha como LocalDate o null si el formato es inválido,nulo ovacío.
     */
    private static LocalDate parseDate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(token, CSV_DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
