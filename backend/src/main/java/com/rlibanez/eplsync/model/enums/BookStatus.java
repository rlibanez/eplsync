package com.rlibanez.eplsync.model.enums;

/**
 * Estado del libro.
 */
public enum BookStatus {
    DISPONIBLE("Disponible", "Disp."),
    VERIFICADO("Verificado", "Ver."),
    DESCONOCIDO("Desconocido", "");

    private final String label;
    private final String code;

    BookStatus(String label, String code) {
        this.label = label;
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public String getCode() {
        return code;
    }

    /**
     * Obtiene el enum a partir del código del CSV.
     * 
     * @param code El texto del CSV ("Disp." o "Ver.")
     * @return El enum correspondiente
     */
    public static BookStatus fromString(String code) {
        if (code == null || code.isBlank()) {
            return DESCONOCIDO;
        }

        String normalized = code.trim();

        for (BookStatus status : values()) {
            if (status.code.equalsIgnoreCase(normalized)) {
                return status;
            }
        }

        return DESCONOCIDO;
    }
}
