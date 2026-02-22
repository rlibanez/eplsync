package com.rlibanez.eplsync.model.enums;

/**
 * Estado de publicación del libro.
 */
public enum PublicationStatus {
    PUBLISHED("Publicado", "P"),
    UPDATED("Actualizado", "A"),
    UNKNOWN("Desconocido", "");

    private final String displayName;
    private final String code;

    PublicationStatus(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCode() {
        return code;
    }

    /**
     * Obtiene el enum a partir del código del CSV (P o A)
     * @param code El código "P" (Publicado) o "A" (Actualizado)
     * @return El enum correspondiente
     */
    public static PublicationStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        
        String normalized = code.trim().toUpperCase();
        return switch (normalized) {
            case "P" -> PUBLISHED;
            case "A" -> UPDATED;
            default -> UNKNOWN;
        };
    }
}
