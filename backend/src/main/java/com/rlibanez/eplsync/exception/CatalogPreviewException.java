package com.rlibanez.eplsync.exception;

public class CatalogPreviewException extends RuntimeException {
    private final String code;
    public CatalogPreviewException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
