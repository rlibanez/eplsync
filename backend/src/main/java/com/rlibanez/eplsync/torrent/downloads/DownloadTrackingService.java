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

    public DownloadTrackingService(DownloadRepository downloads, CatalogBookRepository books,
            TorrentProperties properties, MagnetLinkBuilder magnets, PlatformTransactionManager manager,
            jakarta.persistence.EntityManager entityManager) {
        this.downloads = downloads; this.books = books; this.properties = properties; this.magnets = magnets;
        transactions = new TransactionTemplate(manager);
        this.entityManager = entityManager;
    }

    public String instanceId() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (properties.getClient() + "\n" + properties.getBaseUrl().replaceAll("/+$", ""))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
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

    public record SyncResult(String client, String clientInstanceId, int remoteTorrents, int checked,
            int created, int updated, int completed, int notFound, int ignored, Instant checkedAt) {}

    public SyncResult sync(Supplier<List<RemoteTorrent>> remoteReader) {
        if (!coordination.writeLock().tryLock()) throw new TorrentOperationException(HttpStatus.CONFLICT,
                "Hay envíos o una sincronización en curso; vuelve a intentarlo al terminar");
        try {
            log.info("Sincronización manual de descargas: client={}", properties.getClient());
            // Sin transacción/connection SQL durante la consulta de red. Si falla no se modifica ningún registro.
            var remote = remoteReader.get();
            var now = Instant.now();
            synchronized (writes) {
                var result = transactions.execute(tx -> reconcile(remote, now));
                log.info("Sincronización finalizada: {}", result);
                return result;
            }
        } finally { coordination.writeLock().unlock(); }
    }

    private SyncResult reconcile(List<RemoteTorrent> remote, Instant now) {
        var byHash = new HashMap<String, RemoteTorrent>();
        for (var torrent : remote) {
            for (var alias : torrent.aliases()) {
                var previous = byHash.putIfAbsent(alias, torrent);
                if (previous != null && !previous.hash().equals(torrent.hash()))
                    throw new TorrentOperationException(HttpStatus.BAD_GATEWAY,
                            "El cliente devuelve identidades de torrent ambiguas");
            }
        }
        var known = downloads.findByClientInstanceId(instanceId());
        var identities = new HashSet<String>();
        var matched = new HashSet<String>();
        int updated = 0, completed = 0, missing = 0, created = 0;
        for (var row : known) {
            identities.add(row.getEplId() + ":" + row.getHash());
            var observed = byHash.get(row.getHash());
            var previous = row.getStatus();
            var wasComplete = row.getCompletedAt() != null;
            if (observed == null) {
                // Un intento rechazado/incierto nunca observado no demuestra una desaparición.
                if (row.getStatus() != DownloadStatus.ERROR && row.getStatus() != DownloadStatus.UNKNOWN
                        || row.getLastSeenAt() != null || row.getSubmittedAt() != null) {
                    row.setStatus(DownloadStatus.NOT_FOUND); row.setLastError(null); missing++;
                }
            } else { observe(row, observed, now); matched.add(observed.hash()); }
            row.setLastCheckedAt(now);
            if (previous != row.getStatus() || wasComplete != (row.getCompletedAt() != null)) updated++;
            if (!wasComplete && row.getCompletedAt() != null) completed++;
        }
        // Proyección ligera: no carga sinopsis ni entidades completas para descubrir hashes.
        for (var book : books.findTorrentIdentities()) {
            for (var hash : magnets.hashes(book.getLinks())) {
                var observed = byHash.get(hash);
                if (observed == null) continue;
                matched.add(observed.hash());
                if (!identities.add(book.getEplId() + ":" + hash)) continue;
                var row = create(book.getEplId(), book.getRevision(), hash, DownloadRecord.Origin.DISCOVERED, now);
                row.setDiscoveredAt(now); observe(row, observed, now);
                entityManager.persist(row); created++;
                if (row.getCompletedAt() != null) completed++;
            }
        }
        return new SyncResult(properties.getClient(), instanceId(), remote.size(), known.size() + created,
                created, updated, completed, missing, remote.size() - matched.size(), now);
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
