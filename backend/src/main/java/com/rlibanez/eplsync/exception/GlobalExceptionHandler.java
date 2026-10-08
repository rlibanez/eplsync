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
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida", details, request));
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
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida", details, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        if (!(ex instanceof UserInputException))
            return incident(HttpStatus.BAD_REQUEST,"Solicitud inválida","Los parámetros proporcionados no son válidos",ex,request);
        String details = getMessageOrDefault(ex, "Los parámetros proporcionados no son válidos");
        log.warn("Solicitud inválida: {}", details);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida", details, request));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                        "El cuerpo JSON contiene un formato o valor inválido", request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(buildError(HttpStatus.BAD_REQUEST, "Solicitud inválida",
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

    @ExceptionHandler(CatalogOperationException.class)
    public ResponseEntity<ErrorResponse> handleCatalogOperation(CatalogOperationException ex,HttpServletRequest request) {
        log.warn("Operación de catálogo rechazada: {}",ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(buildError(ex.getStatus(),"Operación de catálogo rechazada",ex.getMessage(),request));
    }

    @ExceptionHandler({org.springframework.transaction.TransactionTimedOutException.class,org.springframework.dao.QueryTimeoutException.class,jakarta.persistence.QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseTimeout(Exception ex,HttpServletRequest request) {
        if(com.rlibanez.eplsync.importer.CatalogOperationBudget.active())
            return handleCatalogOperation(new CatalogOperationException(HttpStatus.REQUEST_TIMEOUT,
                "La operación de catálogo superó el tiempo disponible para la base de datos; los cambios no se han confirmado"),request);
        return incident(HttpStatus.INTERNAL_SERVER_ERROR,"Error de aplicación","No se pudo completar la operación",ex,request);
    }

    @ExceptionHandler(CatalogImportInterruptedException.class)
    public ResponseEntity<ErrorResponse> handleCatalogImportInterrupted(
            CatalogImportInterruptedException ex,
            HttpServletRequest request) {

        log.debug("Importación interrumpida: {}",ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(buildError(HttpStatus.SERVICE_UNAVAILABLE,"Proceso interrumpido","La operación de importación fue interrumpida",request));
    }

    @ExceptionHandler(CatalogImportException.class)
    public ResponseEntity<ErrorResponse> handleCatalogImport(CatalogImportException ex,HttpServletRequest request) {
        // Only our download rejections contain deliberately authored public messages.
        if(ex.getCause() instanceof CatalogDownloadException rejected) {
            log.warn("Descarga del catálogo rechazada: {}",rejected.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR,"Error de importación",rejected.getMessage(),request));
        }
        return incident(HttpStatus.INTERNAL_SERVER_ERROR,"Error de importación","No se pudo importar el catálogo",ex,request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        String details = "El método HTTP no está permitido para este recurso";
        log.warn("Método HTTP no permitido");

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
        String field=ex instanceof org.springframework.web.multipart.support.MissingServletRequestPartException part
            ? part.getRequestPartName() : ((org.springframework.web.bind.MissingServletRequestParameterException)ex).getParameterName();
        return ResponseEntity.badRequest().body(buildError(HttpStatus.BAD_REQUEST,
                "Solicitud inválida", "Falta el campo obligatorio: " + field, request));
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(org.springframework.web.multipart.MultipartException ex,HttpServletRequest request) {
        for(Throwable cause=ex;cause!=null;cause=cause.getCause())
            if(cause instanceof CatalogOperationException rejected) return handleCatalogOperation(rejected,request);
        log.warn("Carga multipart rechazada: {}",ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(buildError(HttpStatus.BAD_REQUEST,"Carga inválida","No se pudo leer el archivo enviado",request));
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
    @ExceptionHandler(com.rlibanez.eplsync.settings.SecretKeyRequiredException.class)
    public ResponseEntity<?> handleSecretKeyRequired(com.rlibanez.eplsync.settings.SecretKeyRequiredException ex) {
        return ResponseEntity.status(ex.getStatusCode()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(java.util.Map.of("code","SECRET_KEY_REQUIRED","details",ex.getReason()));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(java.util.Map.of("details", ex.getReason() == null ? "Solicitud rechazada" : ex.getReason()));
    }
    @ExceptionHandler({java.io.IOException.class, org.springframework.web.context.request.async.AsyncRequestTimeoutException.class})
    public ResponseEntity<?> handleTransferFailure(Exception ex, HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("Transferencia interrumpida: {}", ex.getClass().getSimpleName());
            return null;
        }
        if (!request.getRequestURI().equals("/api/catalog/magnets/export")) return handleGenericException(ex, request);
        resetDownloadResponse(response);
        return ResponseEntity.status(503).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("details", "La transferencia de la exportación se ha interrumpido o ha superado el tiempo máximo. Inténtalo de nuevo."));
    }

    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    public ResponseEntity<?> handleBusyExport(jakarta.servlet.http.HttpServletResponse response) {
        if (response.isCommitted()) return null;
        resetDownloadResponse(response);
        return ResponseEntity.status(503).header("Retry-After", "1").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(java.util.Map.of("details",
                "El servicio de exportación está ocupado. Inténtalo de nuevo en unos segundos."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        return incident(HttpStatus.INTERNAL_SERVER_ERROR,"Error inesperado","Ha ocurrido un error inesperado",ex,request);
    }

    private ResponseEntity<ErrorResponse> incident(HttpStatus status,String message,String publicDetails,
            Exception ex,HttpServletRequest request) {
        String id=java.util.UUID.randomUUID().toString();
        log.error("Error de aplicación: incidencia={}",id,ex);
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header("X-Incident-ID",id)
            .body(new ErrorResponse(status.value(),message,publicDetails+". Referencia: "+id,request.getRequestURI(),id));
    }

    private void resetDownloadResponse(jakarta.servlet.http.HttpServletResponse response) {
        var headers = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Content-Disposition")
                    && !name.equalsIgnoreCase("Content-Type"))
                headers.put(name, java.util.List.copyOf(response.getHeaders(name)));
        }
        response.reset();
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
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