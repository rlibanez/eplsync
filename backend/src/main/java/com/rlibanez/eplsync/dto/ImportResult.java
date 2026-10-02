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
        com.rlibanez.eplsync.model.CatalogMetadata metadata,
        PreviewFile preview
) {
    public record PreviewFile(String token, java.time.Instant expiresAt, String sourceModifiedAt) {}
    public ImportResult withPreview(PreviewFile file) {
        return new ImportResult(success, message, recordsProcessed, errors, recordsUpdated, recordsCreated,
                recordsUnchanged, missingBooks, metadata, file);
    }
    public ImportResult(boolean success, String message, int processed, int errors, int updated, int created,
                        int unchanged, Long missing, com.rlibanez.eplsync.model.CatalogMetadata metadata) {
        this(success, message, processed, errors, updated, created, unchanged, missing, metadata, null);
    }
    public ImportResult(boolean success, String message, int processed, int errors, int updated, int created, int unchanged, com.rlibanez.eplsync.model.CatalogMetadata metadata) {
        this(success, message, processed, errors, updated, created, unchanged, null, metadata);
    }
    public ImportResult(boolean success, String message, int processed, int errors, int updated, int created, int unchanged) {
        this(success, message, processed, errors, updated, created, unchanged, null, null);
    }
}
