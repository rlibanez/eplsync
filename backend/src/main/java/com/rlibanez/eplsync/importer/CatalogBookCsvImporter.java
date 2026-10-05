package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import com.rlibanez.eplsync.dto.ImportResult;
import com.rlibanez.eplsync.dto.ImportPreviewResult;
import com.rlibanez.eplsync.dto.ImportPreviewResult.BookUpdate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.opencsv.bean.CsvToBeanBuilder;
import com.rlibanez.eplsync.dto.CatalogBookCsvRow;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.BookStatus;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.repository.CatalogBookRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Component
public class CatalogBookCsvImporter {

    private static final Logger log = LoggerFactory.getLogger(CatalogBookCsvImporter.class);

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rlibanez.eplsync.service.CatalogSuggestionService suggestions;
    private final CatalogBookRepository repository;

    private static final int BATCH_SIZE = 1000;
    private static final int PROGRESS_EVERY = 10000;

    @PersistenceContext
    private EntityManager em;

    public record ImportStats(int processed, int errors, int updated, int created, int unchanged, Long missingBooks) {
        public ImportStats(int processed, int errors, int updated, int created, int unchanged) {
            this(processed, errors, updated, created, unchanged, null);
        }
    }

    public CatalogBookCsvImporter(CatalogBookRepository repository) {
        this.repository = repository;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportStats importFile(Path csvPath, boolean truncateBeforeImport) throws IOException {

        if (truncateBeforeImport) {
            CatalogCsvValidator.validate(csvPath, System.nanoTime() + CatalogImportLimits.EXTRACTION_TIME.toNanos());
            requireCompleteReplacement(processFile(csvPath, true, 0, 1, true).summary());
            repository.deleteAllInBatch();
            repository.flush();
            em.clear();
        }

        var summary = processFile(csvPath, false, 0, 1).summary();
        // Keep the transactional guard even after preflight: reading or writing may fail on the second pass.
        if (truncateBeforeImport) requireCompleteReplacement(summary);
        if (suggestions != null) suggestions.invalidateAfterCommit();
        return summary;
    }

    @Transactional(readOnly = true)
    public ImportPreviewResult previewFile(Path csvPath, int page, int size) throws IOException {
        if (page < 0 || size < 1) {
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("page debe ser >= 0 y size debe ser > 0");
        }
        var result = processFile(csvPath, true, page, size);
        var stats = result.summary();
        return new ImportPreviewResult(new ImportResult(true, "Previsualización completada",
                stats.processed(), stats.errors(), stats.updated(), stats.created(), stats.unchanged(), stats.missingBooks(), null),
                page, size, result.createdBooks(), result.updatedBooks());
    }

    public record MissingBook(Long eplId, String title, Double revision, java.time.Instant insertDate, java.time.Instant lastModifiedDate) {}
    public record Analysis(ImportStats summary, List<MissingBook> missingBooks) {}
    @Transactional(readOnly = true)
    public Analysis analyzeMissing(Path path) throws IOException {
        var result = processFile(path, true, 0, 1);
        if (result.summary().errors() > 0 || result.summary().processed() == 0)
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV está vacío o contiene errores; no se pueden eliminar ausentes");
        return new Analysis(result.summary(), result.missingBooks());
    }
    private record ProcessResult(ImportStats summary, List<CatalogBook> createdBooks,
                                 List<BookUpdate> updatedBooks, List<MissingBook> missingBooks) {}

    private void requireCompleteReplacement(ImportStats stats) {
        if (stats.errors() > 0)
            throw new com.rlibanez.eplsync.exception.CatalogValidationException(
                "No se puede reemplazar el catálogo: el CSV contiene "
                + (stats.errors() == 1 ? "1 registro con errores" : stats.errors() + " registros con errores")
                + ". El catálogo anterior se ha conservado.");
    }

    private ProcessResult processFile(Path csvPath, boolean preview, int page, int size) throws IOException {
        return processFile(csvPath, preview, page, size, false);
    }

    private ProcessResult processFile(Path csvPath, boolean preview, int page, int size, boolean validateOnly) throws IOException {
        List<CatalogBook> createdBooks = new ArrayList<>();
        List<BookUpdate> updatedBooks = new ArrayList<>();
        var seenIds = new HashSet<Long>();
        long offset = (long) page * size;
        int processed = 0;
        int errors = 0;
        int updated = 0;
        int created = 0;
        int unchanged = 0;
        int rowNumber = 1;

        if (Files.size(csvPath) > CatalogImportLimits.CSV_BYTES)
            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 512 MiB");
        var conversionErrors = new java.util.concurrent.atomic.AtomicInteger();
        try (Reader reader = new CoverCsvReader(Files.newBufferedReader(csvPath))) {

            var parser = new CsvToBeanBuilder<CatalogBookCsvRow>(reader)
                    .withType(CatalogBookCsvRow.class)
                    .withSeparator(',')
                    .withQuoteChar('"')
                    .withIgnoreLeadingWhiteSpace(true)
                    .withMultilineLimit(CatalogImportLimits.RECORD_LINES)
                    .withExceptionHandler(exception -> {
                        if (conversionErrors.incrementAndGet() > CatalogImportLimits.CONVERSION_ERRORS)
                            throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 1000 errores de conversión");
                        // Do not retain attacker-controlled rows/values in OpenCSV's exception queue.
                        var safe = new com.opencsv.exceptions.CsvException("Datos incompatibles con el tipo de una columna");
                        safe.setLineNumber(exception.getLineNumber());
                        return safe;
                    })
                    .build();
            var it = parser.iterator();

            while (it.hasNext()) {
                if (++rowNumber > CatalogImportLimits.RECORDS + 1)
                    throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 1000000 registros");
                CatalogBookCsvRow row = null;
                CatalogBook entity;
                row = it.next(); // Los fallos de lectura abortan; los de conversión los captura OpenCSV.
                try {
                    var pub = PublicationFieldParser.parse(row.getPublishedRaw());

                    entity = CatalogBook.builder()
                            .eplId(row.getEplId())
                            .revision(row.getRevision())
                            .author(row.getAuthor())
                            .title(row.getTitle())
                            .genres(row.getGenres())
                            .collection(row.getCollection())
                            .volume(row.getVolume())
                            .publicationYear(row.getPublicationYear())
                            .synopsis(row.getSynopsis())
                            .pages(row.getPages())
                            .language(Language.fromString(row.getLanguage()))
                            .publicationStatus(pub.status())
                            .publicationDate(pub.date())
                            .status(BookStatus.fromString(row.getStatus()))
                            .rating(row.getRating())
                            .votesCount(row.getVotesCount())
                            .links(row.getLinks())
                            .coverUrl(row.getCoverUrl() == null || row.getCoverUrl().isBlank()
                                    ? null : row.getCoverUrl().strip())
                            .build();

                    if (entity.getEplId() == null || entity.getRevision() == null
                            || entity.getAuthor() == null || entity.getTitle() == null) {
                        throw new com.rlibanez.eplsync.exception.CatalogValidationException("Faltan campos obligatorios del libro");
                    }
                } catch (Exception ex) {
                    errors++;
                    if (errors <= 20) {
                        Long eplId = (row != null) ? row.getEplId() : null;
                        log.warn("Error importando fila {} (eplId={}): {}", rowNumber, eplId, ex.getMessage());
                    }
                    continue;
                }

                // Un ID repetido se omite en ambos modos para que la previsualización sea equivalente.
                if (!seenIds.add(entity.getEplId())) {
                    errors++;
                    continue;
                }

                if (validateOnly) {
                    processed++;
                    continue;
                }

                // Los errores de persistencia abortan la transacción: no se cuentan como filas omitidas.
                CatalogBook existing = repository.findById(entity.getEplId()).orElse(null);
                if (existing == null) {
                    if (!preview) {
                        em.persist(entity);
                    } else if (created >= offset && created < offset + size) {
                        createdBooks.add(entity);
                    }
                    created++;
                } else {
                    if (Objects.equals(existing.getCoverUrl(), entity.getCoverUrl())) {
                        entity.setCoverAvailable(existing.getCoverAvailable());
                    }
                    var changedFields = changedFields(existing, entity);
                    if (changedFields.isEmpty()) {
                        unchanged++;
                    } else {
                        entity.setInsertDate(existing.getInsertDate());
                        entity.setLastModifiedDate(existing.getLastModifiedDate());
                        if (!preview) {
                            em.merge(entity);
                        } else if (updated >= offset && updated < offset + size) {
                            updatedBooks.add(new BookUpdate(existing.toBuilder().build(), entity, changedFields));
                        }
                        updated++;
                    }
                }
                processed++;
                if (processed % BATCH_SIZE == 0) {
                    if (!preview) {
                        repository.flush();
                    }
                    em.clear();
                }
                if (processed % PROGRESS_EVERY == 0) {
                    log.info("Progreso importación: {} filas OK, {} errores", processed, errors);
                }
            }
            for (var error : parser.getCapturedExceptions()) {
                errors++;
                if (errors <= 20) {
                    log.warn("Error convirtiendo fila {}: {}", error.getLineNumber(), error.getMessage());
                }
            }
            if (processed == 0)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV no contiene ningún libro válido");
            if (!preview) {
                repository.flush();
            }
            if (!validateOnly) em.clear();
        }

        var missing = !validateOnly && errors == 0 ? repository.findMissingIdentities().stream()
            .filter(book -> !seenIds.contains(book.getEplId()))
            .map(book -> new MissingBook(book.getEplId(), book.getTitle(), book.getRevision(), book.getInsertDate(), book.getLastModifiedDate()))
            .toList() : List.<MissingBook>of();
        return new ProcessResult(new ImportStats(processed, errors, updated, created, unchanged, !validateOnly && errors == 0 ? (long) missing.size() : null),
                List.copyOf(createdBooks), List.copyOf(updatedBooks), missing);
    }

    /** Compara solo datos del CSV, excluyendo las fechas de auditoría local. */
    private List<String> changedFields(CatalogBook a, CatalogBook b) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(a.getRevision(), b.getRevision())) fields.add("revision");
        if (!Objects.equals(a.getAuthor(), b.getAuthor())) fields.add("author");
        if (!Objects.equals(a.getTitle(), b.getTitle())) fields.add("title");
        if (!Objects.equals(a.getGenres(), b.getGenres())) fields.add("genres");
        if (!Objects.equals(a.getCollection(), b.getCollection())) fields.add("collection");
        if (!Objects.equals(a.getVolume(), b.getVolume())) fields.add("volume");
        if (!Objects.equals(a.getPublicationYear(), b.getPublicationYear())) fields.add("publicationYear");
        if (!Objects.equals(a.getSynopsis(), b.getSynopsis())) fields.add("synopsis");
        if (!Objects.equals(a.getPages(), b.getPages())) fields.add("pages");
        if (!Objects.equals(a.getLanguage(), b.getLanguage())) fields.add("language");
        if (!Objects.equals(a.getPublicationStatus(), b.getPublicationStatus())) fields.add("publicationStatus");
        if (!Objects.equals(a.getPublicationDate(), b.getPublicationDate())) fields.add("publicationDate");
        if (!Objects.equals(a.getStatus(), b.getStatus())) fields.add("status");
        if (!Objects.equals(a.getRating(), b.getRating())) fields.add("rating");
        if (!Objects.equals(a.getVotesCount(), b.getVotesCount())) fields.add("votesCount");
        if (!Objects.equals(a.getLinks(), b.getLinks())) fields.add("links");
        if (!Objects.equals(a.getCoverUrl(), b.getCoverUrl())) fields.add("coverUrl");
        return List.copyOf(fields);
    }
}
