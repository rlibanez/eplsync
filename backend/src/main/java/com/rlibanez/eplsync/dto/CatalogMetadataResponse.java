package com.rlibanez.eplsync.dto;

import com.rlibanez.eplsync.model.CatalogMetadata;
import java.net.URI;
import java.time.Instant;

/** Public projection; the persisted download URL is never modified. */
public record CatalogMetadataResponse(Long id, String sourceUrl, String sourceType,
        String sourceArchiveName, String sourceFileName, String sourceModifiedAt,
        Instant importedAt, String importMode, long totalRows, long insertedRows,
        long updatedRows, long unchangedRows, long errorRows, Long missingRows,
        long durationMs, String sourceSha256, String sourceZipSha256) {
    public static CatalogMetadataResponse from(CatalogMetadata metadata, boolean fullSourceUrl) {
        return new CatalogMetadataResponse(metadata.getId(),
                fullSourceUrl ? metadata.getSourceUrl() : publicUrl(metadata.getSourceUrl()),
                metadata.getSourceType(), metadata.getSourceArchiveName(), metadata.getSourceFileName(),
                metadata.getSourceModifiedAt(), metadata.getImportedAt(), metadata.getImportMode(),
                metadata.getTotalRows(), metadata.getInsertedRows(), metadata.getUpdatedRows(),
                metadata.getUnchangedRows(), metadata.getErrorRows(), metadata.getMissingRows(),
                metadata.getDurationMs(), metadata.getSourceSha256(), metadata.getSourceZipSha256());
    }

    private static String publicUrl(String value) {
        if (value == null) return null;
        try {
            var uri = URI.create(value);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) return null;
            // Preserve escaped paths and IPv6 authorities without re-encoding them.
            String authority = uri.getRawAuthority();
            authority = authority.substring(authority.lastIndexOf('@') + 1);
            return uri.getScheme() + "://" + authority + uri.getRawPath();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
