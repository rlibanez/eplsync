package com.rlibanez.eplsync.model.enums;

/**
 * Idioma del libro.
 */
public enum Language {
    ESPANOL("Español", "es"),
    INGLES("Inglés", "en"),
    CATALAN("Catalán", "ca"),
    GALLEGO("Gallego", "gl"),
    EUSKERA("Euskera", "eu"),
    FRANCES("Francés", "fr"),
    ITALIANO("Italiano", "it"),
    PORTUGUES("Portugués", "pt"),
    ALEMAN("Alemán", "de"),
    ESPERANTO("Esperanto", "eo"),
    SUECO("Sueco", "sv"),
    OTRO("Otro", "other");

    private final String displayName;
    private final String isoCode;

    Language(String displayName, String isoCode) {
        this.displayName = displayName;
        this.isoCode = isoCode;
    }

    /**
     * Nombre legible del idioma para mostrar al usuario.
     *
     * @return el nombre del idioma (ej.: "Español", "Inglés").
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Código ISO 639-1 asociado al idioma.
     *
     * @return el código ISO 639-1 (ej.: "es", "en").
     */
    public String getIsoCode() {
        return isoCode;
    }

    /**
     * Parsea un valor de idioma que puede venir como nombre o como código ISO.
     *
     * @param text el valor de idioma (ej.: "Español", "English", "Français", "es", "en").
     * @return el enum correspondiente, u {@link #OTRO} si no se reconoce.
     */
    public static Language fromString(String text) {
        if (text == null || text.isBlank()) {
            return OTRO;
        }

        String n = text.trim().toLowerCase();

        // Si viene como ISO directamente
        Language byIso = fromIsoCode(n);
        if (byIso != OTRO) {
            return byIso;
        }

        return switch (n) {
            case "español", "espanol" -> ESPANOL;
            case "inglés", "ingles", "english" -> INGLES;
            case "catalán", "catalan", "català" -> CATALAN;
            case "gallego", "galego" -> GALLEGO;
            case "euskera", "vasco" -> EUSKERA;
            case "francés", "frances", "français", "french" -> FRANCES;
            case "italiano", "italian" -> ITALIANO;
            case "portugués", "portugues", "portuguese" -> PORTUGUES;
            case "alemán", "aleman", "deutsch", "german" -> ALEMAN;
            case "esperanto" -> ESPERANTO;
            case "sueco", "swedish", "svenska" -> SUECO;
            default -> OTRO;
        };
    }

    /**
     * Parsea un código ISO 639-1.
     *
     * @param code el código ISO 639-1 (ej.: "es", "en").
     * @return el enum correspondiente, u {@link #OTRO} si no se reconoce.
     */
    public static Language fromIsoCode(String code) {
        if (code == null || code.isBlank()) {
            return OTRO;
        }

        return switch (code.trim().toLowerCase()) {
            case "es" -> ESPANOL;
            case "en" -> INGLES;
            case "ca" -> CATALAN;
            case "gl" -> GALLEGO;
            case "eu" -> EUSKERA;
            case "fr" -> FRANCES;
            case "it" -> ITALIANO;
            case "pt" -> PORTUGUES;
            case "de" -> ALEMAN;
            case "eo" -> ESPERANTO;
            case "sv" -> SUECO;
            default -> OTRO;
        };
    }
}