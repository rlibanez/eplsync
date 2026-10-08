package com.rlibanez.eplsync.exception;

import org.springframework.http.HttpStatus;

/** Mensajes controlados: nunca contiene cuerpos remotos, URL o credenciales. */
public class TorrentConnectionException extends RuntimeException {
    public enum Reason { AUTHENTICATION, UPSTREAM, TIMEOUT, INTERRUPTED, RESPONSE_TOO_LARGE }
    private final Reason reason;

    public TorrentConnectionException(Reason reason) {
        super(switch (reason) {
            case AUTHENTICATION -> "El cliente torrent ha rechazado la autenticación; revisa credenciales y acceso al cliente";
            case UPSTREAM -> "No se pudo obtener una respuesta válida del cliente torrent; revisa conexión y URL base";
            case TIMEOUT -> "Se agotó el tiempo de espera al conectar con el cliente torrent";
            case RESPONSE_TOO_LARGE -> "La respuesta del cliente torrent supera el límite de tamaño o de torrents; no se ha aplicado la sincronización";
            case INTERRUPTED -> "La comprobación del cliente torrent fue interrumpida";
        });
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
    public HttpStatus getStatus() {
        return switch (reason) {
            case AUTHENTICATION, UPSTREAM, RESPONSE_TOO_LARGE -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERRUPTED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
