package com.rlibanez.eplsync.exception;

import java.io.IOException;

/** Respuesta remota que impide descargar el catálogo. */
public class CatalogDownloadException extends IOException {
    public CatalogDownloadException(String message) {
        super(message);
    }
}
