package com.rlibanez.eplsync.dto;

/**
 * Resultado de una operación de importación del catálogo
 */
public record ImportResult(
        boolean success,
        String message,
        int recordsProcessed,
        int errors
) {}
