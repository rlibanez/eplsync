package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CatalogImportProperties;
import com.rlibanez.eplsync.dto.*;
import com.rlibanez.eplsync.exception.CatalogPreviewException;
import com.rlibanez.eplsync.importer.ZipExtractor;
import com.rlibanez.eplsync.importer.ZipExtractor.ExtractedCsv;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** One retained ZIP and one optional preview. CSVs exist only while an operation runs. */
@Service
public class CatalogImportStore {
    public record Archive(String id, String name, String sourceUrl, Instant storedAt, Instant expiresAt,
                          long size, String sha256, String csvName, String csvModifiedAt) {}
    public record Evaluation(String catalogVersion, ImportPreviewResult result) {}
    public record Snapshot(String token, String catalogVersion, ImportResult summary, String sourceType) {}
    public record State(Archive archive, Snapshot preview) {}
    @FunctionalInterface public interface Incoming { Path obtain() throws Exception; }
    @FunctionalInterface public interface Work<T> { T run(Archive archive, ExtractedCsv csv) throws IOException; }
    private final CatalogImportProperties properties;
    private final ZipExtractor extractor;
    private final Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private State state;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CatalogImportStore.class);
    @org.springframework.beans.factory.annotation.Autowired
    public CatalogImportStore(CatalogImportProperties properties, ZipExtractor extractor) throws IOException {
        this(properties, extractor, Path.of(System.getProperty("java.io.tmpdir"), "eplsync-catalog-import"));
    }
    public CatalogImportStore(CatalogImportProperties properties, ZipExtractor extractor, Path directory) throws IOException {
        this.properties = properties; this.extractor = extractor; this.directory = directory;
        Files.createDirectories(directory);
        if (Files.exists(descriptor())) {
            try {
                state = json.readValue(Files.readString(descriptor()), State.class);
                if (state.archive() == null || !Files.isRegularFile(zipPath())) clear();
            } catch (Exception ex) { log.warn("Descriptor de importación inválido; se descarta el ZIP", ex); clear(); }
        } else Files.deleteIfExists(zipPath());
        Files.deleteIfExists(directory.resolve("state.tmp"));
        prune();
    }
    private Path zipPath() { return directory.resolve("catalog.zip"); }
    private Path descriptor() { return directory.resolve("state.json"); }
    public static String digest(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) != -1) {
                com.rlibanez.eplsync.importer.CatalogOperationBudget.check();
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public synchronized Archive archive() { prune(); return state == null ? null : state.archive(); }
    public synchronized <T> T replace(String sourceUrl, String name, Incoming incoming, Work<T> work) {
        // Starting a new source invalidates the previous ZIP even if the new download fails.
        clear();
        Path received = null;
        ExtractedCsv csv = null;
        boolean published = false;
        try {
            received = incoming.obtain();
            Files.move(received, zipPath(), StandardCopyOption.REPLACE_EXISTING);
            csv = extractor.extractCsvWithMetadata(zipPath());
            var now = Instant.now();
            var archive = new Archive(UUID.randomUUID().toString(), name, sourceUrl, now,
                    now.plus(properties.getRetention()), Files.size(zipPath()), digest(zipPath()), csv.name(), csv.modifiedAt());
            save(new State(archive, null)); published = true;
            log.info("ZIP de importación guardado: nombre={}, bytes={}, SHA-256={}, fecha CSV={}, caduca={}",
                    name, archive.size(), archive.sha256(), csv.modifiedAt(), archive.expiresAt());
            com.rlibanez.eplsync.importer.CatalogOperationBudget.check();
            return work.run(archive, csv);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.rlibanez.eplsync.exception.CatalogImportInterruptedException("Importación interrumpida", ex);
        } catch (RuntimeException ex) { throw ex; }
        catch (com.rlibanez.eplsync.exception.CatalogDownloadException ex) {
            throw new com.rlibanez.eplsync.exception.CatalogImportException(ex.getMessage(),ex);
        }
        catch (Exception ex) { throw new com.rlibanez.eplsync.exception.CatalogImportException("No se pudo cargar el ZIP", ex); }
        finally {
            remove(csv == null ? null : csv.path()); remove(received);
            if (!published) clear();
        }
    }
    public synchronized <T> T useArchive(String id, Work<T> work) {
        prune();
        if (state == null || !state.archive().id().equals(id) || !state.archive().expiresAt().isAfter(Instant.now()))
            throw new CatalogPreviewException("ARCHIVE_EXPIRED");
        ExtractedCsv csv = null;
        try {
            com.rlibanez.eplsync.importer.CatalogImportLimits.checkZip(zipPath());
            if (!state.archive().sha256().equals(digest(zipPath()))) throw new CatalogPreviewException("PREVIEW_FILE_CHANGED");
            csv = extractor.extractCsvWithMetadata(zipPath());
            log.info("CSV extraído del ZIP guardado: nombre={}, bytes={}, fecha del CSV={}", csv.name(), Files.size(csv.path()), csv.modifiedAt());
            com.rlibanez.eplsync.importer.CatalogOperationBudget.check();
            return work.run(state.archive(), csv);
        } catch (IOException ex) { throw new com.rlibanez.eplsync.exception.CatalogImportException("No se pudo leer el ZIP guardado", ex); }
        finally { remove(csv == null ? null : csv.path()); }
    }
    public synchronized ImportPreviewResult preview(Archive archive, Evaluation evaluation, String sourceType) {
        com.rlibanez.eplsync.importer.CatalogOperationBudget.check();
        String token = UUID.randomUUID().toString();
        var summary = evaluation.result().summary().withPreview(new ImportResult.PreviewFile(token, archive.expiresAt(), archive.csvModifiedAt()));
        save(new State(archive, new Snapshot(token, evaluation.catalogVersion(), summary, sourceType)));
        var result = evaluation.result();
        return new ImportPreviewResult(summary, result.page(), result.size(), result.createdBooks(), result.updatedBooks());
    }
    public synchronized <T> T usePreview(String token, Function<Snapshot,T> action) {
        prune();
        if (state == null || state.preview() == null || !state.preview().token().equals(token)
                || !state.archive().expiresAt().isAfter(Instant.now())) throw new CatalogPreviewException("PREVIEW_EXPIRED");
        return action.apply(state.preview());
    }
    public synchronized ImportResult refresh(String token, Function<ExtractedCsv, Evaluation> evaluate) {
        return usePreview(token, old -> useArchive(state.archive().id(), (archive,csv) -> {
            var evaluation = evaluate.apply(csv);
            var summary = evaluation.result().summary().withPreview(old.summary().preview());
            save(new State(archive, new Snapshot(token, evaluation.catalogVersion(), summary, old.sourceType())));
            return summary;
        }));
    }
    public synchronized void discard(String token) {
        if (state != null && state.preview() != null && state.preview().token().equals(token))
            save(new State(state.archive(), null));
    }
    public synchronized void discardCurrentPreview() { if (state != null && state.preview() != null) save(new State(state.archive(), null)); }
    private void save(State next) {
        try {
            Path temp = directory.resolve("state.tmp");
            Files.writeString(temp, json.writeValueAsString(next));
            Files.move(temp, descriptor(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = next;
        } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    public synchronized void clear() {
        try { Files.deleteIfExists(descriptor()); Files.deleteIfExists(zipPath()); Files.deleteIfExists(directory.resolve("state.tmp")); state = null; }
        catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public synchronized void prune() {
        if (state != null && !state.archive().expiresAt().isAfter(Instant.now())) {
            clear(); log.info("ZIP de importación eliminado por caducidad");
        }
    }
    private void remove(Path file) {
        if (file != null) try { Files.deleteIfExists(file); }
        catch (IOException ex) { log.warn("No se pudo eliminar archivo temporal {}", file, ex); }
    }
}
