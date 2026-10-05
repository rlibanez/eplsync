package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Executes exactly one request using the resolved, validated target addresses. */
interface CatalogDownloadTransport {
    record Result(int status, String location, String contentType, Path file) {}
    @FunctionalInterface interface BodyWriter {
        Path write(int status, String contentType, InputStream body) throws IOException, InterruptedException;
    }
    Result fetch(CatalogDownloadPolicy.Target target, BodyWriter writer) throws IOException, InterruptedException;
    default Result fetch(CatalogDownloadPolicy.Target target, BodyWriter writer, DownloadBudget budget)
            throws IOException, InterruptedException {
        return fetch(target, writer);
    }
}
