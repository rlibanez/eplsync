package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Shared resource limits for downloaded, uploaded and retained catalog archives. */
public final class CatalogImportLimits {
    public static final long ZIP_BYTES = 128L * 1024 * 1024;
    public static final long CSV_BYTES = 512L * 1024 * 1024;
    public static final long EXPANDED_BYTES = 768L * 1024 * 1024;
    public static final int ENTRIES = 128;
    public static final int COMPRESSION_RATIO = 200;
    public static final int COLUMNS = 64;
    public static final int RECORD_LINES = 1000;
    public static final int RECORD_CHARS = 1024 * 1024;
    public static final int CONVERSION_ERRORS = 1000;
    public static final int RECORDS = 1_000_000;
    public static final Duration DOWNLOAD_TIME = Duration.ofMinutes(5);
    public static final Duration EXTRACTION_TIME = Duration.ofMinutes(2);
    private CatalogImportLimits() {}

    public static void checkZip(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0 || size > ZIP_BYTES)
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP debe ocupar entre 1 byte y 128 MiB");
    }

    static void checkDeadline(long deadline) {
        CatalogOperationBudget.check();
        if (Thread.currentThread().isInterrupted())
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("Procesamiento del catálogo interrumpido");
        if (System.nanoTime() - deadline >= 0)
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El archivo supera el tiempo máximo de validación del catálogo");
    }
}
