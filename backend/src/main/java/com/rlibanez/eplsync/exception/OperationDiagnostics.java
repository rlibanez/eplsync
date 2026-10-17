package com.rlibanez.eplsync.exception;

import java.util.Collections;
import java.util.IdentityHashMap;

/** Diagnostics deliberately exclude exception messages, URLs, SQL and remote response bodies. */
public final class OperationDiagnostics {
    private OperationDiagnostics() {}

    public static String technical(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var result = new StringBuilder();
        for (Throwable cause = failure; cause != null && seen.add(cause) && seen.size() <= 8; cause = cause.getCause()) {
            if (!result.isEmpty()) result.append(" <- ");
            result.append(cause.getClass().getName());
            if (cause instanceof org.springframework.web.server.ResponseStatusException status)
                result.append("[HTTP ").append(status.getStatusCode().value()).append(']');
            if (cause instanceof java.sql.SQLException sql) result.append("[SQL code=").append(sql.getErrorCode()).append(']');
            for (var frame : cause.getStackTrace()) {
                if (frame.getClassName().startsWith("com.rlibanez.eplsync.")) {
                    result.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
                    break;
                }
            }
        }
        return result.toString();
    }

    public static String reason(Throwable failure, String fallback) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause) && seen.size() <= 8; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException)
                return "Se agotó el tiempo de espera del servicio; comprueba la conexión antes de reintentar";
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException)
                return "No se pudo conectar con el servicio; comprueba que está disponible y revisa la conexión";
            if (cause instanceof java.nio.file.AccessDeniedException)
                return "No se puede leer el EPUB; revisa los permisos de las carpetas";
            if (cause instanceof java.nio.file.NoSuchFileException)
                return "El archivo EPUB ya no está disponible; revisa la descarga y las carpetas";
            if (cause instanceof java.util.zip.ZipException)
                return "El EPUB no es un archivo ZIP válido; comprueba el archivo descargado";
        }
        return fallback;
    }
}
