package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.*;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.torrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Service
public class DownloadTrackingService {
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DownloadTrackingService.class);
    private final DownloadRepository downloads;
    private final CatalogBookRepository books;
    private final TorrentProperties properties;
    private final MagnetLinkBuilder magnets;
    private final TransactionTemplate transactions;
    private final jakarta.persistence.EntityManager entityManager;
    // Compartido por envíos y sync: una instantánea no puede pisar un envío posterior.
    private final ReentrantReadWriteLock coordination = new ReentrantReadWriteLock(true);
    private final Object writes = new Object();
    private final ThreadLocal<String> operationInstance = new ThreadLocal<>();

    public DownloadTrackingService(DownloadRepository downloads, CatalogBookRepository books,
            TorrentProperties properties, MagnetLinkBuilder magnets, PlatformTransactionManager manager,
            jakarta.persistence.EntityManager entityManager) {
        this.downloads = downloads; this.books = books; this.properties = properties; this.magnets = magnets;
        transactions = new TransactionTemplate(manager);
        this.entityManager = entityManager;
    }

    public String instanceId() {
        if (operationInstance.get()!=null) return operationInstance.get();
        return identity(properties.getClient(),properties.getBaseUrl());
    }
    private String identity(String client,String baseUrl) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (client + "\n" + baseUrl.replaceAll("/+$", ""))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    /** Scoped to the pinned HTTP adapter, including reconciliation and deletion confirmation. */
    public <T> T withInstance(String client,String baseUrl,Supplier<T> action) {
        var previous=operationInstance.get();operationInstance.set(identity(client,baseUrl));
        try {return action.get();}
        finally {if(previous==null) operationInstance.remove();else operationInstance.set(previous);}
    }

    public TorrentDownloadResult.Status submit(TorrentDownload command, Supplier<TorrentDownloadResult.Status> action) {
        coordination.readLock().lock();
        try {
            // Persistir antes de la red: una interrupción del proceso deja un resultado incierto reconciliable.
            updateSubmission(command, DownloadStatus.UNKNOWN, true, null);
            TorrentDownloadResult.Status result;
            try { result = action.get(); }
            catch (RuntimeException ex) {
                boolean uncertain = !(ex instanceof IllegalArgumentException || ex instanceof TorrentOperationException)
                        && !(ex instanceof TorrentConnectionException connection
                            && connection.getReason() == TorrentConnectionException.Reason.AUTHENTICATION);
                updateSubmission(command, uncertain ? DownloadStatus.UNKNOWN : DownloadStatus.ERROR, false,
                        uncertain ? "Resultado del envío no confirmado; ejecutar sincronización"
                                : "El envío fue rechazado; revisar opciones y conexión");
                throw ex;
            }
            updateSubmission(command, result == TorrentDownloadResult.Status.ACCEPTED
                    ? DownloadStatus.SUBMITTED : DownloadStatus.ALREADY_EXISTS, false, null);
            return result;
        } finally { coordination.readLock().unlock(); }
    }

    private void updateSubmission(TorrentDownload command, DownloadStatus status, boolean requested, String error) {
        synchronized (writes) {
            transactions.executeWithoutResult(tx -> {
                var now = Instant.now();
                var row = downloads.findByClientInstanceIdAndEplIdAndHash(instanceId(), command.book().getEplId(), command.hash())
                        .orElseGet(() -> create(command.book().getEplId(), command.book().getRevision(), command.hash(),
                                DownloadRecord.Origin.EPLSYNC, now));
                if (requested && row.getRequestedAt() == null) row.setRequestedAt(now);
                if (status == DownloadStatus.SUBMITTED && row.getSubmittedAt() == null) row.setSubmittedAt(now);
                if (status == DownloadStatus.ALREADY_EXISTS) row.setLastSeenAt(now);
                row.setStatus(status); row.setLastError(error);
                if (!entityManager.contains(row)) entityManager.persist(row);
            });
        }
    }

    public record RemoteCounts(int total, int matched, int ignored) {}
    public record RecordCounts(int checked, int created, int updated, int unchanged) {}
    public record Outcomes(int newlyCompleted, int notFound, int newlyNotFound) {}
    public record SyncItem(String downloadId, Long eplId, String title, String hash, String action,
            DownloadStatus previousStatus, DownloadStatus resultingStatus, boolean foundInClient,
            List<String> changedFields, boolean newlyCompleted, boolean newlyNotFound,
            Instant previousCompletedAt, Instant resultingCompletedAt, String previousError, String resultingError) {}
    public record IgnoredTorrent(String hash, String name, String reason) {}
    public record SyncResult(String client, String clientInstanceId, boolean dryRun, boolean applied,
            Instant checkedAt, RemoteCounts remote, RecordCounts records, Outcomes outcomes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<SyncItem> items,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<IgnoredTorrent> ignoredTorrents) {}

    public <T> T exclusive(Supplier<T> action) {
        if (!coordination.writeLock().tryLock()) throw new TorrentOperationException(HttpStatus.CONFLICT,
                "Hay envíos o una sincronización en curso; vuelve a intentarlo al terminar");
        try { return action.get(); }
        finally { coordination.writeLock().unlock(); }
    }

    public SyncResult sync(Supplier<List<RemoteTorrent>> remoteReader, boolean dryRun, boolean includeDetails) {
        if (events == null) return performSync(remoteReader, dryRun, includeDetails);
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.TORRENT, dryRun ? "SYNC_PREVIEW" : "SYNC",
            java.util.Map.of("dryRun", dryRun), () -> performSync(remoteReader, dryRun, includeDetails),
            result -> java.util.Map.of("checked", result.records().checked(), "created", result.records().created(),
                "updated", result.records().updated(), "ignored", result.remote().ignored()));
    }
    private SyncResult performSync(Supplier<List<RemoteTorrent>> remoteReader, boolean dryRun, boolean includeDetails) {
        if (!coordination.writeLock().tryLock()) throw new TorrentOperationException(HttpStatus.CONFLICT,
                "Hay envíos o una sincronización en curso; vuelve a intentarlo al terminar");
        try {
            log.info("Sincronización manual de descargas: client={}, dryRun={}", properties.getClient(), dryRun);
            // Sin transacción/connection SQL durante la consulta de red. Si falla no se modifica ningún registro.
            var remote = remoteReader.get();
            var now = Instant.now();
            synchronized (writes) {
                var result = transactions.execute(tx -> {
                    var summary = reconcile(remote, now, dryRun, includeDetails);
                    if (events != null && !dryRun) events.completed(com.rlibanez.eplsync.events.EventJournal.Category.TORRENT, "SYNC",
                        java.util.Map.of("checked", summary.records().checked(), "created", summary.records().created(),
                            "updated", summary.records().updated(), "ignored", summary.remote().ignored()));
                    return summary;
                });
                log.info("Sincronización finalizada: dryRun={}, remote={}, records={}, outcomes={}", dryRun, result.remote(), result.records(), result.outcomes());
                return result;
            }
        } finally { coordination.writeLock().unlock(); }
    }

    public record LinkRequest(String clientInstanceId, String hash, Long eplId, Double revision) {}

    public DownloadRecord link(LinkRequest request, Supplier<List<RemoteTorrent>> remoteReader) {
        if (request == null || request.eplId() == null || request.eplId() <= 0
                || request.revision() == null || !Double.isFinite(request.revision()) || request.revision() <= 0
                || request.hash() == null || !request.hash().matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})"))
            throw new com.rlibanez.eplsync.exception.UserInputException("EPL ID, revisión y hash válidos son obligatorios");
        return exclusive(() -> {
            if (!instanceId().equals(request.clientInstanceId()))
                throw new TorrentOperationException(HttpStatus.CONFLICT, "El cliente ha cambiado; vuelve a sincronizar");
            var hash = request.hash().toUpperCase(Locale.ROOT);
            var matches = remoteReader.get().stream().filter(t -> t.aliases().contains(hash)).toList();
            if (matches.size() != 1)
                throw new TorrentOperationException(HttpStatus.CONFLICT, "El torrent ya no está disponible o su identidad es ambigua");
            var torrent = matches.getFirst();
            synchronized (writes) {
                return transactions.execute(tx -> {
                    if (!books.existsById(request.eplId()))
                        throw new TorrentOperationException(HttpStatus.NOT_FOUND, "El libro no existe en el catálogo");
                    var existing = downloads.findByClientInstanceId(instanceId()).stream()
                            .filter(row -> torrent.aliases().contains(row.getHash())).toList();
                    if (!existing.isEmpty()) {
                        if (existing.size() == 1 && existing.getFirst().getEplId().equals(request.eplId())
                                && existing.getFirst().getRevision().equals(request.revision()))
                            return existing.getFirst();
                        throw new TorrentOperationException(HttpStatus.CONFLICT, "El torrent ya está vinculado a otro libro o revisión");
                    }
                    for (var book : books.findTorrentIdentities()) {
                        if (magnets.hashes(book.getLinks()).stream().anyMatch(torrent.aliases()::contains)
                                && (!book.getEplId().equals(request.eplId()) || !book.getRevision().equals(request.revision())))
                            throw new TorrentOperationException(HttpStatus.CONFLICT, "El hash corresponde a otro libro o revisión del catálogo");
                    }
                    var now = Instant.now();
                    var row = create(request.eplId(), request.revision(), torrent.hash(), DownloadRecord.Origin.DISCOVERED, now);
                    row.setDiscoveredAt(now);
                    observe(row, torrent, now);
                    entityManager.persist(row);
                    log.info("Torrent vinculado: eplId={}, revision={}, hash={}", row.getEplId(), row.getRevision(), row.getHash());
                    return row;
                });
            }
        });
    }

    private SyncResult reconcile(List<RemoteTorrent> remote, Instant now, boolean dryRun, boolean includeDetails) {
        var byHash = new HashMap<String, RemoteTorrent>();
        var uniqueRemote = new LinkedHashMap<String, RemoteTorrent>();
        for (var torrent : remote) {
            uniqueRemote.putIfAbsent(torrent.hash(), torrent);
            for (var alias : torrent.aliases()) {
                var previous = byHash.putIfAbsent(alias, torrent);
                if (previous != null && !previous.hash().equals(torrent.hash()))
                    throw new TorrentOperationException(HttpStatus.BAD_GATEWAY, "El cliente devuelve identidades de torrent ambiguas");
            }
        }
        var catalog = books.findTorrentIdentities();
        var titles = new HashMap<Long, String>();
        if (includeDetails) catalog.forEach(book -> titles.put(book.getEplId(), book.getTitle()));
        var known = downloads.findByClientInstanceId(instanceId());
        var identities = new HashSet<String>();
        var matched = new HashSet<String>();
        var items = includeDetails ? new ArrayList<SyncItem>() : null;
        int updated = 0, completed = 0, missing = 0, newlyMissing = 0, created = 0;
        for (var original : known) {
            // Work on detached copies: a dry-run must not trigger Hibernate dirty checking.
            var row = new DownloadRecord();
            org.springframework.beans.BeanUtils.copyProperties(original, row);
            identities.add(row.getEplId() + ":" + row.getHash());
            var observed = byHash.get(row.getHash());
            if (observed == null) {
                if (row.getStatus() != DownloadStatus.ERROR && row.getStatus() != DownloadStatus.UNKNOWN
                        || row.getLastSeenAt() != null || row.getSubmittedAt() != null) {
                    row.setStatus(DownloadStatus.NOT_FOUND); row.setLastError(null);
                }
            } else { observe(row, observed, now); matched.add(observed.hash()); }
            row.setLastCheckedAt(now);
            var changed = new ArrayList<String>();
            if (original.getStatus() != row.getStatus()) changed.add("status");
            if (!Objects.equals(original.getCompletedAt(), row.getCompletedAt())) changed.add("completedAt");
            if (!Objects.equals(original.getLastError(), row.getLastError())) changed.add("lastError");
            boolean newlyCompleted = original.getCompletedAt() == null && row.getCompletedAt() != null;
            boolean newlyNotFound = original.getStatus() != DownloadStatus.NOT_FOUND && row.getStatus() == DownloadStatus.NOT_FOUND;
            if (!changed.isEmpty()) updated++;
            if (newlyCompleted) completed++;
            if (row.getStatus() == DownloadStatus.NOT_FOUND) missing++;
            if (newlyNotFound) newlyMissing++;
            if (items != null) items.add(new SyncItem(row.getId(), row.getEplId(), titles.get(row.getEplId()), row.getHash(),
                    changed.isEmpty() ? "UNCHANGED" : "UPDATE", original.getStatus(), row.getStatus(), observed != null,
                    List.copyOf(changed), newlyCompleted, newlyNotFound, original.getCompletedAt(), row.getCompletedAt(),
                    original.getLastError(), row.getLastError()));
            if (!dryRun) org.springframework.beans.BeanUtils.copyProperties(row, original);
        }
        for (var book : catalog) {
            for (var hash : magnets.hashes(book.getLinks())) {
                var observed = byHash.get(hash);
                if (observed == null) continue;
                matched.add(observed.hash());
                if (!identities.add(book.getEplId() + ":" + hash)) continue;
                var row = create(book.getEplId(), book.getRevision(), hash, DownloadRecord.Origin.DISCOVERED, now);
                row.setDiscoveredAt(now); observe(row, observed, now);
                if (!dryRun) entityManager.persist(row);
                created++;
                boolean newlyCompleted = row.getCompletedAt() != null;
                if (newlyCompleted) completed++;
                if (items != null) items.add(new SyncItem(dryRun ? null : row.getId(), row.getEplId(), book.getTitle(), hash,
                        "CREATE", null, row.getStatus(), true, List.of(), newlyCompleted, false,
                        null, row.getCompletedAt(), null, row.getLastError()));
            }
        }
        var ignored = includeDetails ? uniqueRemote.values().stream().filter(t -> !matched.contains(t.hash()))
                .map(t -> new IgnoredTorrent(t.hash(), t.name(), "NO_CATALOG_MATCH")).toList() : null;
        return new SyncResult(properties.getClient(), instanceId(), dryRun, !dryRun, now,
                new RemoteCounts(uniqueRemote.size(), matched.size(), uniqueRemote.size() - matched.size()),
                new RecordCounts(known.size() + created, created, updated, known.size() - updated),
                new Outcomes(completed, missing, newlyMissing), items, ignored);
    }

    /** Observe existing cleanup-related records only; never discover unrelated catalog entries. */
    public void observeSelected(Map<String, RemoteTorrent> remote, Set<String> hashes) {
        synchronized (writes) {
            transactions.executeWithoutResult(tx -> {
                var now = Instant.now();
                var selected = new ArrayList<>(hashes);
                for (int start = 0; start < selected.size(); start += 250) {
                    for (var row : downloads.findByClientInstanceIdAndHashIn(instanceId(),
                            selected.subList(start, Math.min(start + 250, selected.size())))) {
                        var torrent = remote.get(row.getHash());
                        if (torrent != null) observe(row, torrent, now);
                        else {
                            if (row.getStatus() != DownloadStatus.ERROR && row.getStatus() != DownloadStatus.UNKNOWN
                                    || row.getLastSeenAt() != null || row.getSubmittedAt() != null) {
                                row.setStatus(DownloadStatus.NOT_FOUND); row.setLastError(null);
                            }
                            row.setLastCheckedAt(now);
                        }
                    }
                }
            });
        }
    }

    private void observe(DownloadRecord row, RemoteTorrent observed, Instant now) {
        row.setStatus(observed.status()); row.setLastCheckedAt(now); row.setLastSeenAt(now);
        row.setLastError(observed.status() == DownloadStatus.ERROR ? "El cliente informa de un error del torrent" : null);
        if (row.getCompletedAt() == null) {
            if (observed.completedAt() != null) row.setCompletedAt(observed.completedAt());
            else if (observed.status() == DownloadStatus.DOWNLOADED) row.setCompletedAt(now);
        }
    }

    private DownloadRecord create(Long eplId, Double revision, String hash, DownloadRecord.Origin origin, Instant now) {
        var row = new DownloadRecord();
        row.setEplId(eplId); row.setRevision(revision); row.setHash(hash);
        row.setClient(properties.getClient()); row.setClientInstanceId(instanceId());
        row.setOrigin(origin); row.setCreatedAt(now);
        return row;
    }
}
