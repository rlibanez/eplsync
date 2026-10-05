package com.rlibanez.eplsync.service;

import java.util.Objects;

import com.rlibanez.eplsync.dto.ImportResult;
import com.rlibanez.eplsync.dto.ImportPreviewResult;
import com.rlibanez.eplsync.exception.CatalogImportException;
import com.rlibanez.eplsync.exception.CatalogDownloadException;
import com.rlibanez.eplsync.exception.CatalogImportInterruptedException;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter.ImportStats;
import com.rlibanez.eplsync.importer.FileDownloader;
import com.rlibanez.eplsync.importer.ZipExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Servicio para importar el catálogo de libros de ePubLibre.
 */
@Service
public class CatalogImportService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.rlibanez.eplsync.events.EventJournal events;
    @org.springframework.beans.factory.annotation.Autowired private CatalogImportStore previews;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private java.util.Map<String, ?> eventSummary(ImportResult result) {
        return java.util.Map.of("processed", result.recordsProcessed(), "created", result.recordsCreated(),
            "updated", result.recordsUpdated(), "unchanged", result.recordsUnchanged(), "errors", result.errors());
    }


    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private final String catalogZipUrl;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.rlibanez.eplsync.settings.ServerSettings settings;
    private String configuredUrl() { return settings == null ? catalogZipUrl : settings.snapshot().zipUrl(); }
    private final com.rlibanez.eplsync.repository.CatalogMetadataRepository metadataRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    private final FileDownloader fileDownloader;
    private final ZipExtractor zipExtractor;
    private final CatalogBookCsvImporter csvImporter;

    public CatalogImportService(FileDownloader fileDownloader,
            ZipExtractor zipExtractor,
            CatalogBookCsvImporter csvImporter,
            @Value("${eplsync.catalog.zip-url}") String catalogZipUrl,
            com.rlibanez.eplsync.repository.CatalogMetadataRepository metadataRepository,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.metadataRepository = metadataRepository;
        this.transactions = transactions;
        this.catalogZipUrl = catalogZipUrl;
        this.fileDownloader = fileDownloader;
        this.zipExtractor = zipExtractor;
        this.csvImporter = csvImporter;
    }

    /**
     * Reemplaza el catálogo completo desde la URL configurada.
     * 
     * @return Resumen del proceso de importación.
     */
    public ImportResult importCatalog() {
        return importCatalog(configuredUrl());
    }

    /**
     * Reemplaza el catálogo desde una URL específica (null usa la URL configurada).
     * 
     * @param zipUrl URL del archivo ZIP que contiene el CSV.
     * @return Resumen del proceso de importación.
     */
    public ImportResult importCatalog(String zipUrl) {
        return importCatalog(zipUrl, true);
    }

    /** Actualiza el catálogo sin borrarlo; null usa la URL configurada. */
    public ImportResult updateCatalog(String zipUrl) {
        return importCatalog(zipUrl, false);
    }

    private ImportResult importCatalog(String zipUrl, boolean truncateBeforeImport) {
        if (events == null) return performImport(zipUrl, truncateBeforeImport);
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG, truncateBeforeImport ? "REPLACE" : "UPDATE",
            () -> performImport(zipUrl, truncateBeforeImport), this::eventSummary);
    }

    private ImportResult performImport(String zipUrl, boolean truncateBeforeImport) {
        long started = System.nanoTime();
        String source = zipUrl == null ? configuredUrl() : zipUrl;
        return previews.replace(source, zipName(source), () -> fileDownloader.download(source, "epublibre-", ".zip"),
                (archive,csv) -> importFile(csv, archive, "URL", truncateBeforeImport, started));
    }

    private ImportResult importFile(ZipExtractor.ExtractedCsv csv, CatalogImportStore.Archive archive, String sourceType, boolean truncateBeforeImport, long started) throws IOException {
        var metadata = new com.rlibanez.eplsync.model.CatalogMetadata();
        metadata.setSourceUrl(archive.sourceUrl());
        metadata.setSourceType(sourceType);
        metadata.setSourceArchiveName(archive.name());
        metadata.setSourceFileName(csv.name());
        metadata.setSourceModifiedAt(csv.modifiedAt());
        metadata.setImportMode(truncateBeforeImport ? "REPLACE" : "UPDATE");
        metadata.setSourceSha256(CatalogImportStore.digest(csv.path()));
        metadata.setSourceZipSha256(archive.sha256());
        return transactions.execute(status -> {
            try {
                ImportStats stats = csvImporter.importFile(csv.path(), truncateBeforeImport);
                metadata.setTotalRows((long) stats.processed() + stats.errors());
                metadata.setInsertedRows(stats.created());
                metadata.setUpdatedRows(stats.updated());
                metadata.setUnchangedRows(stats.unchanged());
                metadata.setErrorRows(stats.errors());
                metadata.setMissingRows(stats.missingBooks());
                metadata.setImportedAt(java.time.Instant.now());
                metadata.setDurationMs((System.nanoTime() - started) / 1_000_000);
                metadataRepository.saveAndFlush(metadata);
                var result = new ImportResult(true, "Importación completada", stats.processed(), stats.errors(),
                        stats.updated(), stats.created(), stats.unchanged(), stats.missingBooks(), metadata);
                if (events != null) events.completed(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG,
                        truncateBeforeImport ? "REPLACE" : "UPDATE", eventSummary(result));
                return result;
            } catch (IOException e) {
                throw new CatalogImportException("Error durante la importación", e);
            }
        });
    }

    public CatalogBookCsvImporter.Analysis analyzeMissing() {
        return withCatalogFile(null, csv -> csvImporter.analyzeMissing(csv.path()));
    }

    public ImportPreviewResult previewCatalog(String zipUrl, int page, int size) {
        java.util.function.Supplier<ImportPreviewResult> work =
                () -> {
                    String source = zipUrl == null ? configuredUrl() : zipUrl;
                    return previews.replace(source, zipName(source), () -> fileDownloader.download(source, "epublibre-", ".zip"),
                            (archive,csv) -> previews.preview(archive, evaluate(csv, page, size), "URL"));
                };
        if (events == null) return work.get();
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG, "PREVIEW",
                java.util.Map.of("dryRun", true), work, result -> eventSummary(result.summary()));
    }

    private CatalogImportStore.Evaluation evaluate(ZipExtractor.ExtractedCsv csv, int page, int size) {
        return transactions.execute(tx -> {
            try {
                String version = catalogVersion();
                return new CatalogImportStore.Evaluation(version, csvImporter.previewFile(csv.path(), page, size));
            } catch (IOException ex) { throw new CatalogImportException("Error previsualizando CSV", ex); }
        });
    }

    // Stream the database content into a digest without retaining book synopses in memory.
    // Includes metadata, additions, deletions and cover repairs, not unrelated event/download rows.
    private String catalogVersion() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String query : java.util.List.of("select * from catalog_books order by epl_id", "select * from catalog_metadata order by id")) {
                jdbc.query(query, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                        String value = row.getString(column);
                        byte[] bytes = value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        digest.update(java.nio.ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
                        digest.update(bytes);
                    }
                });
                digest.update((byte) 0xff);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public ImportResult retainedPreview(String token) { return previews.usePreview(token, value -> Objects.requireNonNull(value).summary()); }
    public ImportResult refreshPreview(String token) {
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG, "PREVIEW",
                java.util.Map.of("dryRun", true),
                () -> previews.refresh(token, csv -> evaluate(csv, 0, 50)), this::eventSummary);
    }
    public void discardPreview(String token) { previews.discard(token); }
    public ImportResult applyPreview(String token) {
        long started = System.nanoTime();
        return previews.usePreview(token, saved -> {
            var savedArchive = previews.archive();
            if (savedArchive == null) throw new com.rlibanez.eplsync.exception.CatalogPreviewException("PREVIEW_EXPIRED");
            return previews.useArchive(savedArchive.id(), (archive,csv) -> {
            var result = events.run(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG, "UPDATE",
                java.util.Map.of("retainedZip", true), () -> transactions.execute(tx -> {
                    if (!saved.catalogVersion().equals(catalogVersion()))
                        throw new com.rlibanez.eplsync.exception.CatalogPreviewException("PREVIEW_STALE");
                    try {
                        // Older preview descriptors lack the selection; retain their original file provenance.
                        String sourceType = saved.sourceType() != null ? saved.sourceType()
                                : archive.sourceUrl() != null ? "URL" : "LOCAL_FILE";
                        return importFile(csv, archive, sourceType, false, started);
                    }
                    catch (IOException ex) { throw new CatalogImportException("Error aplicando CSV", ex); }
                }), this::eventSummary);
            previews.discard(token);
            return result;
            });
        });
    }

    public String defaultUrl() { return configuredUrl(); }
    public CatalogImportStore.Archive savedArchive() { return previews.archive(); }
    public enum Mode { PREVIEW, UPDATE }
    public ImportResult runSaved(String archiveId, Mode mode) {
        long started = System.nanoTime();
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG,
                mode == Mode.PREVIEW ? "PREVIEW" : "UPDATE", java.util.Map.of("retainedZip", true, "dryRun", mode == Mode.PREVIEW),
                () -> previews.useArchive(archiveId, (archive,csv) -> processSource(archive, csv, mode, "SAVED_ZIP", started)), this::eventSummary);
    }
    public ImportResult runUpload(org.springframework.web.multipart.MultipartFile file, Mode mode) {
        String originalName = file.getOriginalFilename();
        String name = com.rlibanez.eplsync.importer.CatalogFileNames.display(originalName, "");
        if (file.isEmpty() || originalName == null || !originalName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
            throw new com.rlibanez.eplsync.exception.UserInputException("Selecciona un archivo ZIP no vacío");
        String displayName = name;
        long started = System.nanoTime();
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.CATALOG,
            mode == Mode.PREVIEW ? "PREVIEW" : "UPDATE", java.util.Map.of("dryRun", mode == Mode.PREVIEW),
            () -> previews.replace(null, displayName, () -> {
                Path uploaded = Files.createTempFile("eplsync-upload-", ".zip");
                try {
                    if (file.getSize() > com.rlibanez.eplsync.importer.CatalogImportLimits.ZIP_BYTES)
                        throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera el máximo de 128 MiB");
                    try (var input = file.getInputStream(); var output = Files.newOutputStream(uploaded)) {
                        byte[] buffer = new byte[8192];
                        long total = 0;
                        for (int read; (read = input.read(buffer)) != -1;) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Carga del ZIP interrumpida");
                            total += read;
                            if (total > com.rlibanez.eplsync.importer.CatalogImportLimits.ZIP_BYTES)
                                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El ZIP supera el máximo de 128 MiB");
                            output.write(buffer, 0, read);
                        }
                    }
                    return uploaded;
                }
                catch (Exception ex) { cleanupTempFile(uploaded); throw ex; }
            }, (archive,csv) -> processSource(archive,csv,mode,"LOCAL_FILE",started)), this::eventSummary);
    }
    private ImportResult processSource(CatalogImportStore.Archive archive, ZipExtractor.ExtractedCsv csv, Mode mode, String sourceType, long started) throws IOException {
        if (mode == Mode.PREVIEW) return previews.preview(archive, evaluate(csv,0,50), sourceType).summary();
        var result = importFile(csv, archive, sourceType, false, started);
        previews.discardCurrentPreview();
        return result;
    }
    private String zipName(String source) {
        String path = FileDownloader.validateUrl(source).getPath();
        return com.rlibanez.eplsync.importer.CatalogFileNames.display(path, "catalog.zip");
    }

    @FunctionalInterface
    private interface CatalogFileOperation<T> {
        T apply(ZipExtractor.ExtractedCsv csvFile) throws IOException;
    }

    private <T> T withCatalogFile(String zipUrl, CatalogFileOperation<T> operation) {
        if (zipUrl == null) {
            zipUrl = configuredUrl();
        }
        log.info("Iniciando descarga del catálogo");

        Path zipFile = null;
        Path csvFile = null;

        try {
            // Paso 1: Descargar el ZIP
            zipFile = fileDownloader.download(zipUrl, "epublibre-", ".zip");
            log.info("ZIP descargado: {} ({} bytes)", zipFile, Files.size(zipFile));

            // Paso 2: Extraer el CSV del ZIP
            var extracted = zipExtractor.extractCsvWithMetadata(zipFile);
            csvFile = extracted.path();
            log.info("CSV extraído: {} ({} bytes), fecha del CSV: {}", csvFile, Files.size(csvFile),
                    extracted.modifiedAt() == null ? "desconocida" : extracted.modifiedAt());

            // Paso 3: Procesar el CSV según la operación solicitada: reemplazar, actualizar o previsualizar.
            return operation.apply(extracted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogImportInterruptedException("La operación de importación fue interrumpida", e);
        } catch (com.rlibanez.eplsync.exception.CatalogPreviewException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (CatalogDownloadException e) {
            throw new CatalogImportException(e.getMessage(), e);
        } catch (IOException e) {
            throw new CatalogImportException("Error de entrada/salida durante la importación", e);
        } catch (Exception e) {
            throw new CatalogImportException("Error durante el procesamiento del CSV", e);
        } finally {
            cleanupTempFile(zipFile);
            cleanupTempFile(csvFile);
        }
    }

    /**
     * Elimina un archivo temporal de forma segura.
     *
     * @param file Ruta al archivo temporal a eliminar. Puede ser null.
     * @implNote Si ocurre un error al borrar, se registra en el log pero no se
     *           lanza excepción.
     */
    private void cleanupTempFile(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
                log.debug("Archivo temporal eliminado: {}", file);
            } catch (IOException e) {
                log.warn("No se pudo eliminar archivo temporal: {}", file, e);
            }
        }
    }
}
