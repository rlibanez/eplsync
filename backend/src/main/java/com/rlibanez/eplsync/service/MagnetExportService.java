package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogMetadataRepository;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Builds a bounded disk snapshot using short catalog transactions, before sending any bytes. */
@Service
public class MagnetExportService {
    static final int BATCH_SIZE = 250;
    static final long MAX_BYTES = 128L * 1024 * 1024;
    static final long MAX_INDEX_BYTES = 128L * 1024 * 1024;
    static final Duration PREPARATION_TIME = Duration.ofMinutes(2);
    public static final Duration TRANSFER_TIME = Duration.ofMinutes(2);
    record Limits(long bytes, long indexBytes, Duration preparation, Duration transfer) {}
    private final Limits limits;
    private final java.util.function.LongSupplier nanos;
    private final Semaphore active = new Semaphore(1);
    private final EntityManager em;
    private final MagnetLinkBuilder builder;
    private final CatalogMetadataRepository metadata;
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public MagnetExportService(EntityManager em, MagnetLinkBuilder builder, CatalogMetadataRepository metadata,
            PlatformTransactionManager manager) {
        this(em, builder, metadata, manager, new Limits(MAX_BYTES, MAX_INDEX_BYTES, PREPARATION_TIME, TRANSFER_TIME), System::nanoTime);
    }
    MagnetExportService(EntityManager em, MagnetLinkBuilder builder, CatalogMetadataRepository metadata,
            PlatformTransactionManager manager, Limits limits, java.util.function.LongSupplier nanos) {
        this.em = em; this.builder = builder; this.metadata = metadata; this.limits = limits; this.nanos = nanos;
        transactions = new TransactionTemplate(manager);
        transactions.setReadOnly(true);
        transactions.setTimeout((int) Math.max(1, limits.preparation().toSeconds()));
    }
    private record Book(long id, String title, String links) {}
    private String version() {
        return transactions.execute(status -> metadata.findById(1L)
                .map(value -> value.getImportedAt() + ":" + value.getSourceSha256()).orElse(""));
    }
    public Prepared prepare(CatalogBookFilter filter, Sort requested) {
        // Reject invalid input before occupying the installation's only export slot.
        var spec = CatalogBookSpecifications.fromFilter(filter);
        Sort sort = CatalogOrdering.normalize(requested);
        if (!active.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Ya hay una exportación en curso. Espera a que termine e inténtalo de nuevo.");
        Path directory = null;
        try {
            long deadline = nanos.getAsLong() + limits.preparation().toNanos();
            String version = version();
            directory = Files.createTempDirectory("eplsync-export-");
            Path output = directory.resolve("magnets.txt"), index = directory.resolve("hashes.sqlite");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + index);
                 var ddl = connection.createStatement();
                 var writer = new BufferedOutputStream(Files.newOutputStream(output))) {
                ddl.execute("PRAGMA page_size=4096");
                ddl.execute("PRAGMA max_page_count=" + limits.indexBytes() / 4096);
                ddl.execute("PRAGMA cache_size=-2048");
                ddl.execute("PRAGMA journal_mode=OFF");
                ddl.execute("CREATE TABLE hashes(hash TEXT PRIMARY KEY) WITHOUT ROWID");
                try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO hashes VALUES(?)")) {
                    long bytes = 0;
                    int offset = 0;
                    while (true) {
                        checkDeadline(deadline);
                        int start = offset;
                        var batch = transactions.execute(status -> {
                            var cb = em.getCriteriaBuilder();
                            var query = cb.createTupleQuery();
                            var root = query.from(CatalogBook.class);
                            query.select(cb.tuple(root.get("eplId"), root.get("title"), root.get("links")));
                            query.where(spec.toPredicate(root, query, cb));
                            query.orderBy(sort.stream().map(order -> com.rlibanez.eplsync.ordering.TextOrdering.order(cb,root,order)).toList());
                            return em.createQuery(query).setHint("jakarta.persistence.query.timeout", remainingMillis(deadline))
                                    .setFirstResult(start).setMaxResults(BATCH_SIZE).getResultList().stream()
                                    .map(row -> new Book(row.get(0, Long.class), row.get(1, String.class), row.get(2, String.class))).toList();
                        });
                        for (var book : Objects.requireNonNull(batch)) {
                            checkDeadline(deadline);
                            for (String hash : builder.hashes(book.links())) {
                                checkDeadline(deadline);
                                insert.setString(1, hash);
                                if (insert.executeUpdate() == 0) continue;
                                byte[] line = (builder.build(hash, book.id(), book.title()) + "\n").getBytes(StandardCharsets.UTF_8);
                                if (line.length > limits.bytes() - bytes) throw limit("La exportación supera el máximo de 128 MiB.");
                                writer.write(line); bytes += line.length;
                            }
                        }
                        if (!version.equals(version())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "El catálogo ha cambiado durante la exportación. Inténtalo de nuevo.");
                        if (batch.size() < BATCH_SIZE) break;
                        offset = Math.addExact(offset, BATCH_SIZE);
                    }
                }
            }
            Files.delete(index);
            return new Prepared(directory, output, active, limits.transfer());
        } catch (IOException | SQLException ex) {
            clean(directory); active.release();
            if (ex instanceof SQLException sql && sql.getErrorCode() == 13)
                throw limit("El índice temporal de la exportación supera el máximo de 128 MiB.");
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                    "No se pudo preparar la exportación. Comprueba el espacio temporal disponible.", ex);
        } catch (jakarta.persistence.QueryTimeoutException | org.springframework.dao.QueryTimeoutException
                | org.springframework.transaction.TransactionTimedOutException ex) {
            clean(directory); active.release();
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,
                    "La consulta de exportación ha superado el tiempo máximo permitido. Inténtalo de nuevo.", ex);
        } catch (RuntimeException | Error ex) {
            clean(directory); active.release(); throw ex;
        }
    }
    private static ResponseStatusException limit(String reason) { return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, reason); }
    private int remainingMillis(long deadline) {
        checkDeadline(deadline);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (deadline - nanos.getAsLong()) / 1_000_000));
    }
    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || nanos.getAsLong() >= deadline)
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,
                    "La exportación ha superado el tiempo máximo de preparación de 2 minutos.");
    }
    private static void clean(Path directory) {
        if (directory == null) return;
        try {
            try (var files = Files.list(directory)) {
                for (Path path : files.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        } catch (NoSuchFileException ignored) {
            // Completion, timeout and writer cleanup may all close the same snapshot.
        } catch (IOException ex) {
            org.slf4j.LoggerFactory.getLogger(MagnetExportService.class).warn("No se pudo limpiar un archivo temporal de exportación", ex);
        }
    }
    public static final class Prepared implements AutoCloseable {
        private final Path directory, output;
        private final Semaphore active;
        private final Duration transfer;
        private boolean running, cancelled, released;
        private InputStream input;
        Prepared(Path directory, Path output, Semaphore active, Duration transfer) { this.directory = directory; this.output = output; this.active = active; this.transfer = transfer; }
        public long size() throws IOException { return Files.size(output); }
        public void writeTo(OutputStream target) throws IOException {
            synchronized (this) {
                if (cancelled || running) throw new IOException("Exportación cancelada o ya iniciada");
                running = true;
            }
            long deadline = System.nanoTime() + transfer.toNanos();
            try {
                synchronized (this) {
                    if (cancelled) throw new IOException("Exportación cancelada");
                    input = Files.newInputStream(output);
                }
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (this) { if (cancelled) throw new IOException("Exportación cancelada"); }
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                        throw new IOException("Tiempo máximo de transferencia superado");
                    target.write(buffer, 0, count);
                }
                target.flush();
                if (System.nanoTime() >= deadline) throw new IOException("Tiempo máximo de transferencia superado");
            } finally {
                synchronized (this) { running = false; close(); }
            }
        }
        @Override public synchronized void close() {
            cancelled = true;
            if (input != null) { try { input.close(); } catch (IOException ignored) {} }
            clean(directory);
            // Keep the slot while a blocked network writer is still running.
            if (!running && !released) { released = true; active.release(); }
        }
    }
}
