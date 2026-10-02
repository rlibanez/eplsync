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
        Long missingBooks,
        com.rlibanez.eplsync.model.CatalogMetadata metadata
) {
    public ImportResult(boolean success, String message, int processed, int errors, int updated, int created, int unchanged, com.rlibanez.eplsync.model.CatalogMetadata metadata) {
        this(success, message, processed, errors, updated, created, unchanged, null, metadata);
    }
    public ImportResult(boolean success, String message, int processed, int errors, int updated, int created, int unchanged) {
        this(success, message, processed, errors, updated, created, unchanged, null, null);
    }
}
