package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.importer.CatalogBookCsvImporter.MissingBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.repository.CatalogMetadataRepository;
import com.rlibanez.eplsync.events.EventJournal;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;

/** Bounded, short-lived review snapshots. Confirmation never downloads a different CSV. */
@Service
public class CatalogMissingService {
    public record Book(Long eplId, String title, Double revision) {}
    public record Preview(String token, Instant expiresAt, int total, int page, int size, List<Book> items) {}
    public record Result(int deleted) {}
    private record Snapshot(Instant expires, String version, List<MissingBook> books) {}
    private final Map<String, Snapshot> previews = new LinkedHashMap<>();
    private final CatalogImportService imports;
    private final CatalogBookRepository books;
    private final CatalogMetadataRepository metadata;
    private final BulkStore bulk;
    private final DownloadTrackingService tracking;
    private final EntityManager em;
    private final EventJournal events;
    private final TransactionTemplate transactions;
    public CatalogMissingService(CatalogImportService imports, CatalogBookRepository books,
            CatalogMetadataRepository metadata, BulkStore bulk, DownloadTrackingService tracking,
            EntityManager em, EventJournal events, PlatformTransactionManager manager) {
        this.imports = imports; this.books = books; this.metadata = metadata; this.bulk = bulk;
        this.tracking = tracking; this.em = em; this.events = events;
        transactions = new TransactionTemplate(manager);
    }
    private String version() {
        return metadata.findById(1L).map(m -> m.getImportedAt() + ":" + m.getSourceSha256()).orElse("");
    }
    public Preview preview() {
        String before = version();
        var analysis = imports.analyzeMissing();
        if (!before.equals(version())) throw conflict("El catálogo ha cambiado; repite la previsualización");
        synchronized (previews) {
            previews.entrySet().removeIf(e -> !e.getValue().expires().isAfter(Instant.now()));
            if (previews.size() >= 8) previews.remove(previews.keySet().iterator().next());
            String token = UUID.randomUUID().toString();
            previews.put(token, new Snapshot(Instant.now().plusSeconds(900), before, List.copyOf(analysis.missingBooks())));
            return page(token, 0, 50);
        }
    }
    public Preview page(String token, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginación inválida");
        synchronized (previews) {
            var snapshot = snapshot(token);
            int from = (int) Math.min((long) page * size, snapshot.books().size());
            var items = snapshot.books().subList(from, Math.min(from + size, snapshot.books().size())).stream()
                    .map(b -> new Book(b.eplId(), b.title(), b.revision())).toList();
            return new Preview(token, snapshot.expires(), snapshot.books().size(), page, size, items);
        }
    }
    private Snapshot snapshot(String token) {
        var snapshot = previews.get(token);
        if (snapshot == null || !snapshot.expires().isAfter(Instant.now())) {
            previews.remove(token);
            throw conflict("La previsualización ha caducado; vuelve a comprobar los ausentes");
        }
        return snapshot;
    }
    public Result delete(String token, boolean confirm) {
        if (!confirm) throw new IllegalArgumentException("Es necesario confirmar la eliminación");
        synchronized (bulk) {
            return tracking.exclusive(() -> {
                synchronized (previews) {
                    var snapshot = snapshot(token);
                    var result = events.run(EventJournal.Category.CATALOG, "DELETE_MISSING", () -> transactions.execute(tx -> {
                        if (!snapshot.version().equals(version())) throw conflict("El catálogo ha cambiado; repite la previsualización");
                        var current = new HashMap<Long, MissingBook>();
                        books.findMissingIdentities().forEach(b -> current.put(b.getEplId(), new MissingBook(
                                b.getEplId(), b.getTitle(), b.getRevision(), b.getInsertDate(), b.getLastModifiedDate())));
                        for (var book : snapshot.books()) if (!book.equals(current.get(book.eplId())))
                            throw conflict("Los libros han cambiado; repite la previsualización");
                        var ids = snapshot.books().stream().map(value -> Objects.requireNonNull(value).eplId()).toList();
                        for (int start = 0; start < ids.size(); start += 500) {
                            var batch = ids.subList(start, Math.min(start + 500, ids.size()));
                            if (em.createQuery("select count(i) from BulkItem i where i.eplId in :ids and i.state in :states", Long.class)
                                    .setParameter("ids", batch).setParameter("states", List.of(
                                        com.rlibanez.eplsync.torrent.bulk.BulkItem.State.PENDING,
                                        com.rlibanez.eplsync.torrent.bulk.BulkItem.State.IN_FLIGHT)).getSingleResult() > 0
                                || em.createQuery("select count(c) from UpdateCleanup c where c.eplId in :ids and c.state in :states", Long.class)
                                    .setParameter("ids", batch).setParameter("states", List.of(
                                        com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.WAITING,
                                        com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.BLOCKED,
                                        com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REQUESTED)).getSingleResult() > 0)
                                throw conflict("Hay trabajos pendientes relacionados con estos libros; cancélalos o espera a que finalicen");
                            em.createQuery("delete from CatalogBook b where b.eplId in :ids").setParameter("ids", batch).executeUpdate();
                        }
                        events.completed(EventJournal.Category.CATALOG, "DELETE_MISSING", Map.of("deleted", ids.size()));
                        em.clear();
                        return new Result(ids.size());
                    }), value -> Map.of("deleted", value.deleted()));
                    previews.remove(token);
                    return result;
                }
            });
        }
    }
    public void clear() { synchronized (previews) { previews.clear(); } }
    private static TorrentOperationException conflict(String message) {
        return new TorrentOperationException(HttpStatus.CONFLICT, message);
    }
}
