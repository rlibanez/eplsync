package com.rlibanez.eplsync.exception;

import org.springframework.http.HttpStatus;

public class TorrentOperationException extends RuntimeException {
    private final HttpStatus status;

    public TorrentOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
