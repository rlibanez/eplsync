package com.rlibanez.eplsync.dto;

/**
 * Respuesta de error estándar para la API
 */
public record ErrorResponse(
        int status,
        String message,
        String details,
        String path
) {}
