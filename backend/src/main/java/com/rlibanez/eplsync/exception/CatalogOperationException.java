package com.rlibanez.eplsync.exception;

import org.springframework.http.HttpStatus;

/** Public resource-limit errors, without database or filesystem details. */
public class CatalogOperationException extends UserInputException {
    private final HttpStatus status;
    public CatalogOperationException(HttpStatus status, String message) { super(message); this.status=status; }
    public HttpStatus getStatus() { return status; }
}
