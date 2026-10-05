package com.rlibanez.eplsync.exception;

/** Safe, concise catalog rejection reason suitable for API responses and events. */
public class CatalogValidationException extends IllegalArgumentException {
    public CatalogValidationException(String message) { super(message); }
    public CatalogValidationException(String message, Throwable cause) { super(message, cause); }
}
