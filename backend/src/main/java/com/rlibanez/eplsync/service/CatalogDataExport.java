package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.ordering.TextOrdering;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.specification.CatalogLibraryFilters;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CatalogDataExport {
    private static final Set<String> COLUMNS = Set.of("eplId", "revision", "title", "author", "collection",
            "genres", "pages", "publicationYear", "language", "status", "publicationStatus", "publicationDate", "insertDate");
    private static final int PREVIEW_ROWS = 5;
    private static final Pattern FORMULA_PREFIX = Pattern.compile("^[\\s\\p{Z}]*[=+@-]");
    private final EntityManager em;
    private final CatalogLibraryFilters libraryFilters;
    private final TransactionTemplate read;
    private final Semaphore active;
    private final JsonMapper json = JsonMapper.builder().build();

    public CatalogDataExport(EntityManager em, CatalogLibraryFilters libraryFilters, PlatformTransactionManager manager,
            MagnetExportService magnetExports) {
        this.em = em;
        this.libraryFilters = libraryFilters;
        active = magnetExports.exportSlot();
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setTimeout((int) MagnetExportService.PREPARATION_TIME.toSeconds());
    }

    public enum Format {
        CSV("csv", "text/csv"), JSON("json", "application/json");

        private final String extension;
        private final String mediaType;

        Format(String extension, String mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        public String defaultExtension() { return extension; }
        public String contentType() { return mediaType + ";charset=UTF-8"; }
    }

    public enum Separator {
        COMMA(','), SEMICOLON(';'), TAB('\t'), PIPE('|');

        private final char value;

        Separator(char value) { this.value = value; }
    }

    public record Request(@NotNull @Valid CatalogBookFilter filters, @NotNull Format format,
            Separator separator, @NotNull @Size(min = 1, max = 13) List<@NotNull String> columns,
            @Size(max = 256) String ordering,
            @Size(max = 32) @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9]+(?:[._-][a-zA-Z0-9]+)*")
            String extension) {
        public String filename() {
            return "catalogo." + (extension == null ? format.defaultExtension() : extension);
        }
    }

    public record Preview(String text, int rows) {}

    private record Selection(Specification<CatalogBook> filter, Sort sort) {}

    private Selection selection(Request request) {
        if (new HashSet<>(request.columns()).size() != request.columns().size()
                || request.columns().stream().anyMatch(column -> !COLUMNS.contains(column)))
            throw new UserInputException("Columnas de exportación inválidas o repetidas");
        var filter = CatalogBookSpecifications.fromFilter(request.filters());
        if (request.filters().hasLibraryFilters()) filter = filter.and(libraryFilters.fromFilter(request.filters()));
        return new Selection(filter, CatalogOrdering.parse(request.ordering() == null ? "eplId,asc" : request.ordering()));
    }

    public Preview preview(Request request) {
        var selection = selection(request);
        return read.execute(status -> {
            var rows = rows(request, selection, 0, PREVIEW_ROWS, (int) MagnetExportService.PREPARATION_TIME.toMillis());
            var text = new StringBuilder(start(request));
            for (int index = 0; index < rows.size(); index++)
                text.append(line(request, rows.get(index), index == 0));
            return new Preview(text.append(end(request)).toString(), rows.size());
        });
    }

    public MagnetExportService.Prepared prepare(Request request) {
        var selection = selection(request);
        if (!active.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Ya hay una exportación en curso");
        Path directory = null;
        boolean prepared = false;
        try {
            directory = Files.createTempDirectory("eplsync-catalog-export-");
            Path output = directory.resolve("export");
            try (var target = new java.io.BufferedOutputStream(Files.newOutputStream(output))) {
                read.executeWithoutResult(status -> write(request, selection, target));
            }
            var file = new MagnetExportService.Prepared(directory, output, active, MagnetExportService.TRANSFER_TIME);
            prepared = true;
            return file;
        } catch (IOException | UncheckedIOException ex) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                    "No se pudo preparar la exportación. Comprueba el espacio temporal disponible.", ex);
        } catch (jakarta.persistence.QueryTimeoutException | org.springframework.dao.QueryTimeoutException
                | org.springframework.transaction.TransactionTimedOutException ex) {
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "La exportación superó el tiempo máximo permitido", ex);
        } finally {
            if (!prepared) {
                MagnetExportService.clean(directory);
                active.release();
            }
        }
    }

    private void write(Request request, Selection selection, OutputStream target) {
        long deadline = System.nanoTime() + MagnetExportService.PREPARATION_TIME.toNanos();
        try {
            long bytes = append(target, start(request), 0);
            for (int offset = 0; ; offset += MagnetExportService.BATCH_SIZE) {
                var batch = rows(request, selection, offset, MagnetExportService.BATCH_SIZE, remainingMillis(deadline));
                for (int index = 0; index < batch.size(); index++) {
                    remainingMillis(deadline);
                    bytes = append(target, line(request, batch.get(index), offset == 0 && index == 0), bytes);
                }
                if (batch.size() < MagnetExportService.BATCH_SIZE) break;
            }
            append(target, end(request), bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private List<Tuple> rows(Request request, Selection selection, int offset, int limit, int timeout) {
        var cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(CatalogBook.class);
        query.select(cb.tuple(request.columns().stream().map(root::get)
                .toArray(jakarta.persistence.criteria.Selection<?>[]::new)));
        query.where(selection.filter().toPredicate(root, query, cb));
        query.orderBy(selection.sort().stream().map(order -> TextOrdering.order(cb, root, order)).toList());
        return em.createQuery(query).setHint("jakarta.persistence.query.timeout", timeout)
                .setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    private String start(Request request) {
        return request.format() == Format.JSON ? "[" : delimited(request, request.columns());
    }

    private static String end(Request request) {
        return request.format() == Format.JSON ? "\n]\n" : "";
    }

    private String line(Request request, Tuple row, boolean first) {
        if (request.format() != Format.JSON)
            return delimited(request, Arrays.asList(row.toArray()));
        var values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < request.columns().size(); index++) {
            String column = request.columns().get(index);
            Object value = row.get(index);
            if (column.equals("genres")) {
                var genres = new LinkedHashSet<String>();
                if (value instanceof String text) {
                    for (String part : text.split(",")) {
                        String genre = part.strip();
                        if (!genre.isEmpty()) genres.add(genre);
                    }
                }
                value = List.copyOf(genres);
            } else if (value instanceof TemporalAccessor) {
                value = value.toString();
            }
            values.put(column, value);
        }
        return (first ? "\n" : ",\n") + json.writeValueAsString(values);
    }

    private String delimited(Request request, List<?> values) {
        char separator = request.separator() != null ? request.separator().value
                : ',';
        var row = new StringJoiner(String.valueOf(separator), "", "\r\n");
        for (Object value : values) {
            String cell = value == null ? "" : value.toString();
            // Spreadsheet apps can evaluate formulas even in quoted cells.
            if (value instanceof String && (FORMULA_PREFIX.matcher(cell).find()
                    || cell.startsWith("\t") || cell.startsWith("\r") || cell.startsWith("\n")))
                cell = "'" + cell;
            if (cell.indexOf(separator) >= 0 || cell.contains("\"") || cell.contains("\r") || cell.contains("\n"))
                cell = "\"" + cell.replace("\"", "\"\"") + "\"";
            row.add(cell);
        }
        return row.toString();
    }

    private static long append(OutputStream target, String text, long written) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MagnetExportService.MAX_BYTES - written)
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "La exportación supera el máximo de 128 MiB");
        target.write(bytes);
        return written + bytes.length;
    }

    private static int remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted())
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "La exportación superó el tiempo máximo permitido");
        return (int) Math.max(1, remaining / 1_000_000);
    }
}
