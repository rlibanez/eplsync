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
        authorizeFiles(List.of(plan));
        var work = pendingEntries(List.of(plan));
        if (work.get(jobId).isEmpty()) return view(jobId);
        client.exclusiveClient(adapter -> execute(List.of(plan), work, adapter, retryUnconfirmed));
        return view(jobId);
    }

    private void authorizeFiles(List<UpdatePlan> selected) {
        if (selected.stream().anyMatch(plan -> plan.getPreviousVersions() == PreviousVersions.REMOVE_TORRENT_AND_FILES))
            com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_FILES_DELETE);
    }
    private static final List<UpdateCleanup.State> PENDING = List.of(UpdateCleanup.State.WAITING,
            UpdateCleanup.State.BLOCKED, UpdateCleanup.State.REQUESTED);
    public record JobResult(String jobId, long checked, long removed, long waiting, long blocked,
            long requested, String error) {}
    public record GlobalResult(int selectedJobs, long failedJobs, long checked, long removed,
            long waiting, long blocked, long requested, List<JobResult> jobs) {}

    public GlobalResult cleanAll(boolean retryUnconfirmed) {
        var selected = plans.pending(PreviousVersions.KEEP, PENDING);
        authorizeFiles(selected);
        var work = pendingEntries(selected);
        var errors = new HashMap<String, String>();
        var current = new ArrayList<UpdatePlan>();
        for (var plan : selected) {
            if (plan.getClientInstanceId().equals(tracking.instanceId())) current.add(plan);
            else errors.put(plan.getJobId(), "El destino del trabajo ha cambiado; restaura su cliente y URL");
        }
        if (!current.isEmpty()) errors.putAll(client.exclusiveClient(adapter -> execute(current, work, adapter, retryUnconfirmed)));
        var results = new ArrayList<JobResult>();
        for (var plan : selected) {
            var ids = new HashSet<String>();
            work.get(plan.getJobId()).forEach(entry -> ids.add(entry.getId()));
            var states = new EnumMap<UpdateCleanup.State, Long>(UpdateCleanup.State.class);
            for (var entry : entries.findByJobIdOrderByEplIdAsc(plan.getJobId()))
                if (ids.contains(entry.getId())) states.merge(entry.getState(), 1L, (left, right) -> Long.sum(Objects.requireNonNull(left), Objects.requireNonNull(right)));
            results.add(new JobResult(plan.getJobId(), ids.size(), states.getOrDefault(UpdateCleanup.State.REMOVED, 0L),
                    states.getOrDefault(UpdateCleanup.State.WAITING, 0L), states.getOrDefault(UpdateCleanup.State.BLOCKED, 0L),
                    states.getOrDefault(UpdateCleanup.State.REQUESTED, 0L), errors.get(plan.getJobId())));
        }
        var result = new GlobalResult(results.size(), results.stream().filter(job -> job.error() != null).count(),
                results.stream().mapToLong(value -> Objects.requireNonNull(value).checked()).sum(), results.stream().mapToLong(value -> Objects.requireNonNull(value).removed()).sum(),
                results.stream().mapToLong(value -> Objects.requireNonNull(value).waiting()).sum(), results.stream().mapToLong(value -> Objects.requireNonNull(value).blocked()).sum(),
                results.stream().mapToLong(value -> Objects.requireNonNull(value).requested()).sum(), List.copyOf(results));
        log.info("Limpieza global finalizada: jobs={}, errores={}, registros={}, eliminados={}, pendientes={}, bloqueados={}, sinConfirmar={}",
                result.selectedJobs(), result.failedJobs(), result.checked(), result.removed(), result.waiting(), result.blocked(), result.requested());
        return result;
    }

    private Map<String, List<UpdateCleanup>> pendingEntries(List<UpdatePlan> selected) {
        var work = new LinkedHashMap<String, List<UpdateCleanup>>();
        for (var plan : selected) work.put(plan.getJobId(), entries.findByJobIdOrderByEplIdAsc(plan.getJobId()).stream()
                .filter(entry -> PENDING.contains(entry.getState())).toList());
        return work;
    }

    private Map<String, String> execute(List<UpdatePlan> selected, Map<String, List<UpdateCleanup>> work,
            com.rlibanez.eplsync.torrent.TorrentClient adapter, boolean retryUnconfirmed) {
        var errors = new HashMap<String, String>();
        var remote = adapter.listTorrents();
        var byHash = index(remote);
        var snapshots = new HashMap<String, Map<Long, UpdatePlanner.Candidate>>();
        var protectedTargets = new HashSet<String>();
        var policies = new HashMap<String, PreviousVersions>();
        var conflictingPolicies = new HashSet<String>();
        var unconfirmed = new HashSet<String>();
        for (var plan : selected) {
            var byBook = new HashMap<Long, UpdatePlanner.Candidate>();
            mapper.readValue(plan.getSnapshot(), UpdatePlanner.Snapshot.class).items().forEach(item -> {
                byBook.put(item.eplId(), item);
                for (var hash : item.targetHashes()) {
                    var target = byHash.get(hash);
                    if (target != null) protectedTargets.add(target.hash());
                }
            });
            snapshots.put(plan.getJobId(), byBook);
            for (var entry : work.get(plan.getJobId())) {
                var old = byHash.get(entry.getHash());
                if (old == null) continue;
                var previous = policies.putIfAbsent(old.hash(), plan.getPreviousVersions());
                if (previous != null && previous != plan.getPreviousVersions()) conflictingPolicies.add(old.hash());
                if (entry.getState() == UpdateCleanup.State.REQUESTED) unconfirmed.add(old.hash());
            }
        }
        var owners = new HashMap<String, Set<Long>>();
        for (var row : downloads.findByClientInstanceId(tracking.instanceId()))
            owners.computeIfAbsent(row.getHash(), key -> new HashSet<>()).add(row.getEplId());
        for (var book : books.findTorrentIdentities()) for (var hash : magnets.hashes(book.getLinks()))
            owners.computeIfAbsent(hash, key -> new HashSet<>()).add(book.getEplId());
        var dispatched = new HashSet<String>();
        var paths = paths(remote);
        for (var plan : selected) {
            String jobId = plan.getJobId();
            try {
                for (var entry : work.get(jobId)) {

                    var old = byHash.get(entry.getHash());
                    if (old == null) { removed(entry); continue; }
                    // Un POST incierto no se repite: una siguiente consulta confirma su ausencia.
                    if (entry.getState() == UpdateCleanup.State.REQUESTED && !retryUnconfirmed) continue;
                    var candidate = snapshots.get(jobId).get(entry.getEplId());
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
                    if (deleteFiles) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_FILES_DELETE);
                    if (deleteFiles && !exclusivePath(old, paths)) {
                        postpone(entry, UpdateCleanup.State.BLOCKED, "Rutas compartidas o no verificables; no se borran archivos"); continue;
                    }
                    if (protectedTargets.contains(old.hash()) || conflictingPolicies.contains(old.hash())) {
                        postpone(entry, UpdateCleanup.State.BLOCKED,
                                "Torrent necesario para otro plan o con políticas de borrado incompatibles"); continue;
                    }
                    if (unconfirmed.contains(old.hash()) && !retryUnconfirmed) {
                        save(entry, UpdateCleanup.State.REQUESTED, "Otro job tiene una eliminación sin confirmar para este torrent");
                        continue;
                    }
                    // Persistir antes de la red permite reconocer un resultado incierto tras reiniciar.
                    save(entry, UpdateCleanup.State.REQUESTED, "Eliminación solicitada; pendiente de confirmar ausencia");
                    if (!dispatched.add(old.hash())) continue;
                    try {
                        adapter.deleteTorrent(old.hash(), deleteFiles);
                        log.info("Limpieza solicitada: jobId={}, eplId={}, deleteFiles={}", jobId, entry.getEplId(), deleteFiles);
                    } catch (RuntimeException ex) {
                        log.warn("Limpieza sin confirmar: jobId={}, eplId={}, tipo={}", jobId, entry.getEplId(), ex.getClass().getSimpleName());
                        save(entry, UpdateCleanup.State.REQUESTED,
                                "Respuesta de eliminación no confirmada; consultar de nuevo o usar retryUnconfirmed=true");
                        errors.put(jobId, "Alguna eliminación no pudo confirmarse; consultar los estados de limpieza");
                    }
                }
            } catch (RuntimeException ex) {
                errors.put(jobId, "No se pudo completar la limpieza del job; consultar sus estados");
                log.warn("Fallo de limpieza: jobId={}, tipo={}", jobId, ex.getClass().getSimpleName());
            }
        }
        if (!dispatched.isEmpty()) {
            try {
                var after = index(adapter.listTorrents());
                // También confirmar los intentos cuya respuesta se perdió.
                for (var plan : selected) for (var entry : work.get(plan.getJobId()))
                    if (entry.getState() == UpdateCleanup.State.REQUESTED && !after.containsKey(entry.getHash())) removed(entry);
            } catch (RuntimeException ex) {
                for (var plan : selected)
                    if (work.get(plan.getJobId()).stream().anyMatch(entry -> entry.getState() == UpdateCleanup.State.REQUESTED))
                        errors.put(plan.getJobId(), "No se pudo confirmar la ausencia de los torrents; repetir la consulta de limpieza");
                log.warn("No se pudo confirmar la limpieza: tipo={}", ex.getClass().getSimpleName());
            }
        }
        return errors;
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
            else counts.merge(path, 1, (left, right) -> Integer.sum(Objects.requireNonNull(left), Objects.requireNonNull(right)));
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
