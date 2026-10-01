package com.rlibanez.eplsync.dto;

/**
 * Resultado de una operación de importación del catálogo
 */
public record ImportResult(
        boolean success,
        String message,
        int recordsProcessed,
        int errors,
        int recordsUpdated,
        int recordsCreated,
        int recordsUnchanged,
        com.rlibanez.eplsync.model.CatalogMetadata metadata
) {
    public ImportResult(boolean success, String message, int processed, int errors, int updated, int created, int unchanged) {
        this(success, message, processed, errors, updated, created, unchanged, null);
    }
}
