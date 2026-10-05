package com.rlibanez.eplsync.dto;

/**
 * Respuesta de error estándar para la API
 */
public record ErrorResponse(
        int status,
        String message,
        String details,
        String path,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String incidentId
) {
    public ErrorResponse(int status,String message,String details,String path) { this(status,message,details,path,null); }
}
