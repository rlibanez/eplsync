package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

@Service
public class UpdateCleanupService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UpdateCleanupService.class);
    private final UpdatePlanRepository plans;
    private final UpdateCleanupRepository entries;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentClientService client;
    private final CatalogBookRepository books;
    private final MagnetLinkBuilder magnets;
    private final TransactionTemplate tx;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public UpdateCleanupService(UpdatePlanRepository plans, UpdateCleanupRepository entries, DownloadRepository downloads,
            DownloadTrackingService tracking, TorrentClientService client, CatalogBookRepository books,
            MagnetLinkBuilder magnets, PlatformTransactionManager manager) {
        this.plans = plans; this.entries = entries; this.downloads = downloads; this.tracking = tracking;
        this.client = client; this.books = books; this.magnets = magnets; tx = new TransactionTemplate(manager);
    }

    public record View(String jobId, PreviousVersions previousVersions, List<UpdatePlanner.Candidate> updates, List<UpdateCleanup> items) {}
    public View view(String jobId) {
        var plan = plan(jobId);
        return new View(jobId, plan.getPreviousVersions(), mapper.readValue(plan.getSnapshot(), UpdatePlanner.Snapshot.class).items(),
                entries.findByJobIdOrderByEplIdAsc(jobId));
    }
    private UpdatePlan plan(String jobId) {
        return plans.findById(jobId).orElseThrow(() -> new TorrentOperationException(HttpStatus.NOT_FOUND,
                "Trabajo de actualización inexistente"));
    }

    public View clean(String jobId) { return clean(jobId, false); }

    public View clean(String jobId, boolean retryUnconfirmed) {
        var plan = plan(jobId);
        if (!plan.getClientInstanceId().equals(tracking.instanceId()))
            throw new TorrentOperationException(HttpStatus.CONFLICT, "El destino del trabajo ha cambiado");
        if (plan.getPreviousVersions() == PreviousVersions.KEEP) return view(jobId);
        return client.exclusiveClient(adapter -> {
            var snapshot = mapper.readValue(plan.getSnapshot(), UpdatePlanner.Snapshot.class);
            var byBook = new HashMap<Long, UpdatePlanner.Candidate>();
            snapshot.items().forEach(item -> byBook.put(item.eplId(), item));
            var remote = adapter.listTorrents();
            var byHash = index(remote);
            var owners = new HashMap<String, Set<Long>>();
            for (var row : downloads.findByClientInstanceId(plan.getClientInstanceId()))
                owners.computeIfAbsent(row.getHash(), key -> new HashSet<>()).add(row.getEplId());
            for (var book : books.findTorrentIdentities()) for (var hash : magnets.hashes(book.getLinks()))
                owners.computeIfAbsent(hash, key -> new HashSet<>()).add(book.getEplId());
            var requested = new ArrayList<UpdateCleanup>();
            var dispatched = new HashSet<String>();
            var paths = paths(remote);
            for (var entry : entries.findByJobIdOrderByEplIdAsc(jobId)) {
                if (entry.getState() == UpdateCleanup.State.REMOVED) continue;
                var old = byHash.get(entry.getHash());
                if (old == null) { removed(entry); continue; }
                // Un POST incierto no se repite: una siguiente consulta confirma su ausencia.
                if (entry.getState() == UpdateCleanup.State.REQUESTED && !retryUnconfirmed) continue;
                var candidate = byBook.get(entry.getEplId());
                var targets = candidate.targetHashes().stream().map(byHash::get).toList();
                if (targets.stream().anyMatch(target -> target == null || target.status() != DownloadStatus.DOWNLOADED)) {
                    postpone(entry, UpdateCleanup.State.WAITING, "La nueva revisión todavía no está completa en el cliente"); continue;
                }
                if (targets.stream().anyMatch(target -> target.hash().equals(old.hash()))) {
                    postpone(entry, UpdateCleanup.State.BLOCKED, "La versión anterior y la nueva identifican el mismo torrent"); continue;
                }
                boolean shared = old.aliases().stream().anyMatch(hash -> owners.getOrDefault(hash, Set.of()).stream()
                        .anyMatch(id -> !id.equals(entry.getEplId())));
                if (shared) { postpone(entry, UpdateCleanup.State.BLOCKED, "Torrent compartido con otro libro"); continue; }
                boolean deleteFiles = plan.getPreviousVersions() == PreviousVersions.REMOVE_TORRENT_AND_FILES;
                if (deleteFiles && !exclusivePath(old, paths)) {
                    postpone(entry, UpdateCleanup.State.BLOCKED, "Rutas compartidas o no verificables; no se borran archivos"); continue;
                }
                // Persistir antes de la red permite reconocer un resultado incierto tras reiniciar.
                save(entry, UpdateCleanup.State.REQUESTED, "Eliminación solicitada; pendiente de confirmar ausencia");
                if (!dispatched.add(old.hash())) { requested.add(entry); continue; }
                try {
                    adapter.deleteTorrent(old.hash(), deleteFiles);
                    requested.add(entry);
                    log.info("Limpieza solicitada: jobId={}, eplId={}, deleteFiles={}", jobId, entry.getEplId(), deleteFiles);
                } catch (RuntimeException ex) {
                    log.warn("Limpieza sin confirmar: jobId={}, eplId={}, tipo={}", jobId, entry.getEplId(), ex.getClass().getSimpleName());
                    save(entry, UpdateCleanup.State.REQUESTED,
                            "Respuesta de eliminación no confirmada; consultar de nuevo o usar retryUnconfirmed=true");
                }
            }
            if (!requested.isEmpty()) {
                var after = index(adapter.listTorrents());
                for (var entry : requested) if (!after.containsKey(entry.getHash())) removed(entry);
            }
            return view(jobId);
        });
    }

    private Map<String, RemoteTorrent> index(List<RemoteTorrent> remote) {
        var index = new HashMap<String, RemoteTorrent>();
        for (var torrent : remote) for (var hash : torrent.aliases()) {
            var previous = index.putIfAbsent(hash, torrent);
            if (previous != null && !previous.hash().equals(torrent.hash()))
                throw new TorrentOperationException(HttpStatus.BAD_GATEWAY, "Identidades remotas ambiguas");
        }
        return index;
    }

    private record Paths(java.util.NavigableMap<String, Integer> counts, boolean complete) {}

    private Paths paths(List<RemoteTorrent> remote) {
        var counts = new TreeMap<String, Integer>();
        boolean complete = true;
        for (var torrent : remote) {
            var path = normalizedPath(torrent.contentPath());
            if (path == null) complete = false;
            else counts.merge(path, 1, Integer::sum);
        }
        return new Paths(counts, complete);
    }

    private boolean exclusivePath(RemoteTorrent old, Paths paths) {
        var path = normalizedPath(old.contentPath());
        if (!paths.complete() || path == null || paths.counts().getOrDefault(path, 0) != 1) return false;
        for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
            if (paths.counts().containsKey(path.substring(0, slash))) return false;
        String descendant = paths.counts().ceilingKey(path + "/");
        return descendant == null || !descendant.startsWith(path + "/");
    }

    private String normalizedPath(String path) {
        // Rutas remotas POSIX únicamente: no interpretar rutas del cliente en el host de EPLsync.
        if (path == null || !path.startsWith("/") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return null;
        var parts = new ArrayList<String>();
        for (var part : path.split("/")) {
            if (part.equals("..")) return null;
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return parts.isEmpty() ? null : "/" + String.join("/", parts);
    }

    private void postpone(UpdateCleanup entry, UpdateCleanup.State state, String message) {
        // La incertidumbre de una escritura anterior nunca se pierde por una comprobación fallida.
        save(entry, entry.getState() == UpdateCleanup.State.REQUESTED ? UpdateCleanup.State.REQUESTED : state, message);
    }

    private void save(UpdateCleanup entry, UpdateCleanup.State state, String message) {
        entry.setState(state); entry.setMessage(message); entry.setUpdatedAt(Instant.now());
        entries.saveAndFlush(entry);
    }

    private void removed(UpdateCleanup entry) {
        tx.executeWithoutResult(status -> {
            save(entry, UpdateCleanup.State.REMOVED, "Ausencia confirmada en el cliente; historial conservado");
            downloads.findById(entry.getDownloadId()).ifPresent(row -> {
                row.setStatus(DownloadStatus.NOT_FOUND); row.setLastCheckedAt(Instant.now()); row.setLastError(null);
                downloads.save(row);
            });
        });
    }
}
