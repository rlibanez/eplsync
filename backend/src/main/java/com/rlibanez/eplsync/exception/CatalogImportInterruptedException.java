package com.rlibanez.eplsync.exception;

public class CatalogImportInterruptedException extends RuntimeException {
    public CatalogImportInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}