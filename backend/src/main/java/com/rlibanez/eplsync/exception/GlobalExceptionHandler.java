package com.rlibanez.eplsync.exception;

import com.rlibanez.eplsync.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Manejador de excepciones API para los controllers REST.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        String details = ex.getConstraintViolations()
                .stream()
                .map(violation -> java.util.Objects.requireNonNull(violation).getMessage())
                .findFirst()
                .orElse("Parámetros inválidos");

        log.warn("Error de validación: {}", details);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida", details, request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String details = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Cuerpo de la solicitud inválido");

        log.warn("Error de validación: {}", details);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida", details, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        String details = getMessageOrDefault(ex, "Los parámetros proporcionados no son válidos");
        log.warn("Solicitud inválida: {}", details);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida", details, request));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                        "El cuerpo JSON contiene un formato o valor inválido", request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                        "El parámetro '" + ex.getName() + "' tiene un formato incorrecto", request));
    }

    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            Exception ex,
            HttpServletRequest request) {

        log.debug("Recurso no encontrado: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildError(HttpStatus.NOT_FOUND, "Recurso no encontrado",
                        "No existe el recurso solicitado", request));
    }

    @ExceptionHandler(CatalogImportInterruptedException.class)
    public ResponseEntity<ErrorResponse> handleCatalogImportInterrupted(
            CatalogImportInterruptedException ex,
            HttpServletRequest request) {

        log.error("Importación interrumpida", ex);

        String details = getMessageOrDefault(ex, "La importación fue interrumpida");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE, "Proceso interrumpido", details, request));
    }

    @ExceptionHandler(CatalogImportException.class)
    public ResponseEntity<ErrorResponse> handleCatalogImport(
            CatalogImportException ex,
            HttpServletRequest request) {

        log.error("Error de importación", ex);

        String details = getMessageOrDefault(ex, "No se pudo importar el catálogo");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Error de importación", details, request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        String details = ex.getMessage(); 
        log.warn("Método HTTP no permitido: {}", details);

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(buildError(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido", details, request));
    }

    @ExceptionHandler(TorrentConnectionException.class)
    public ResponseEntity<ErrorResponse> handleTorrentConnection(
            TorrentConnectionException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(buildError(ex.getStatus(), "Conexión con el cliente torrent fallida", ex.getMessage(), request));
    }

    @ExceptionHandler(TorrentOperationException.class)
    public ResponseEntity<ErrorResponse> handleTorrentOperation(
            TorrentOperationException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(buildError(ex.getStatus(), "Operación torrent fallida", ex.getMessage(), request));
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleDisconnectedStream(org.springframework.web.context.request.async.AsyncRequestNotUsableException ex) {
        log.debug("Conexión asíncrona cerrada por el cliente: {}", ex.getMessage());
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMissingInput(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(buildError(HttpStatus.BAD_REQUEST,
                "Solicitud inválida", ex.getMessage(), request));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleUploadSize() {
        return ResponseEntity.status(413).body(java.util.Map.of("code", "ZIP_TOO_LARGE"));
    }

    @ExceptionHandler(CatalogPreviewException.class)
    public ResponseEntity<?> handleCatalogPreview(CatalogPreviewException ex) {
        return ResponseEntity.status((ex.code().equals("PREVIEW_EXPIRED") || ex.code().equals("ARCHIVE_EXPIRED")) ? 410 : 409)
                .body(java.util.Map.of("code", ex.code()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied() {
        return ResponseEntity.status(403).body(java.util.Map.of("code", "ACCESS_DENIED"));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(java.util.Map.of("details", ex.getReason() == null ? "Solicitud rechazada" : ex.getReason()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Error inesperado", ex);

        String details = "Ha ocurrido un error inesperado";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Error inesperado", details, request));
    }

    private ErrorResponse buildError(HttpStatus status, String message, String details, HttpServletRequest request) {
        return new ErrorResponse(
                status.value(),
                message,
                details,
                request.getRequestURI());
    }

    private String getMessageOrDefault(Exception ex, String defaultMessage) {
        String message = ex.getMessage();
        return (message != null && !message.isBlank()) ? message : defaultMessage;
    }
}