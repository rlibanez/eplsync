package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

/** Persistent per-revision queue. Jobs are provenance only; scheduling is outside this executor. */
@Service
public class CleanupQueue {
    public static final List<UpdateCleanup.State> PENDING = List.of(UpdateCleanup.State.WAITING, UpdateCleanup.State.BLOCKED, UpdateCleanup.State.REQUESTED);
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CleanupQueue.class);
    private final UpdateCleanupRepository entries;
    private final UpdatePlanRepository plans;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentClientService client;
    private final CatalogBookRepository books;
    private final MagnetLinkBuilder magnets;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public record Result(String id, String downloadId, UpdateCleanup.State state, String message) {}

    public CleanupQueue(UpdateCleanupRepository entries, UpdatePlanRepository plans, DownloadRepository downloads,
            DownloadTrackingService tracking, TorrentClientService client, CatalogBookRepository books,
            MagnetLinkBuilder magnets, PlatformTransactionManager manager) {
        this.entries=entries; this.plans=plans; this.downloads=downloads; this.tracking=tracking;
        this.client=client; this.books=books; this.magnets=magnets;
        tx=new TransactionTemplate(manager);
    }

    /** One-time bounded backfill: freeze dependencies from old plans before using the new queue. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void migrate() {
        tx.executeWithoutResult(status -> em.createQuery("update BulkJob j set j.type=:type where exists (select p.jobId from UpdatePlan p where p.jobId=j.id)")
            .setParameter("type",com.rlibanez.eplsync.torrent.bulk.BulkJob.Type.UPDATE).executeUpdate());
        while (true) {
            var legacy=entries.findByClientInstanceIdIsNull(org.springframework.data.domain.PageRequest.of(0,100));
            if (legacy.isEmpty()) return;
            hydrate(legacy);
        }
    }
    public void hydrate(List<UpdateCleanup> selected) {
        tx.executeWithoutResult(status -> {
            for (var entry:selected) if (entry.getClientInstanceId()==null) {
                var plan=plans.findById(entry.getJobId()).orElseThrow();
                List<String> targets;
                if (plan.getSnapshot().equals("{\"items\":[]}")) targets=em.createQuery(
                    "select i.hash from BulkItem i where i.jobId=:job and i.eplId=:id order by i.position",String.class)
                    .setParameter("job",entry.getJobId()).setParameter("id",entry.getEplId()).getResultList();
                else targets=mapper.readValue(plan.getSnapshot(),UpdatePlanner.Snapshot.class).items().stream()
                    .filter(c -> c.eplId().equals(entry.getEplId())).findFirst().orElseThrow().targetHashes();
                var job=em.find(com.rlibanez.eplsync.torrent.bulk.BulkJob.class,entry.getJobId());
                entry.initialize(plan,targets,job==null ? EventContext.Actor.unknown() : job.eventActor());
                if (job!=null && job.getState()==com.rlibanez.eplsync.torrent.bulk.BulkJob.State.CANCELLED
                        && entry.getState()!=UpdateCleanup.State.REQUESTED && PENDING.contains(entry.getState()))
                    entry.setState(UpdateCleanup.State.CANCELLED);
                entries.save(entry);
            }
        });
    }
    /** A bounded batch of accepted updates; blocked requests are attempted at most once per wake-up. */
    public int runImmediate(int limit,Instant cycle) {
        var selected=entries.readyImmediate(PENDING,cycle,org.springframework.data.domain.PageRequest.of(0,limit));
        if(selected.isEmpty()) return 0;
        var eligible=new ArrayList<UpdateCleanup>();
        for(var entry:selected) {
            touch(entry);
            if(entry.getClientInstanceId().equals(tracking.instanceId())) eligible.add(entry);
        }
        if(!eligible.isEmpty()) client.exclusiveClient(adapter -> perform(eligible,adapter,adapter.listTorrents(),false,true));
        return selected.size();
    }
    public void runAutomatic(int limit) {
        var selected=entries.pendingAutomatic(PENDING,org.springframework.data.domain.PageRequest.of(0,limit));
        if (selected.isEmpty()) return;
        // Advance fairness even when the destination can no longer be used.
        var eligible=new ArrayList<UpdateCleanup>();
        for (var entry:selected) {
            if (entry.getClientInstanceId().equals(tracking.instanceId())) eligible.add(entry);
            else touch(entry);
        }
        if (!eligible.isEmpty()) client.exclusiveClient(adapter -> perform(eligible,adapter,adapter.listTorrents(),false,true));
    }
    public List<UpdateCleanup> manualPending() {
        return entries.findByClientInstanceIdAndStateIn(tracking.instanceId(),PENDING).stream()
            .filter(e -> !Boolean.TRUE.equals(e.getImmediate()) || Boolean.TRUE.equals(e.getAutomatic()) || e.getState()==UpdateCleanup.State.REQUESTED)
            .filter(e -> e.getPreviousVersions()!=PreviousVersions.REMOVE_TORRENT_AND_FILES || Permission.has(Permission.TORRENT_FILES_DELETE))
            .sorted(Comparator.comparing(UpdateCleanup::getCreatedAt).thenComparing(UpdateCleanup::getId)).toList();
    }
    public List<Result> execute(List<UpdateCleanup> selected, TorrentClient adapter, List<RemoteTorrent> remote,
            boolean retryUnconfirmed, boolean automatic) {
        return perform(selected,adapter,remote,retryUnconfirmed,automatic);
    }
    private List<Result> perform(List<UpdateCleanup> selected, TorrentClient adapter, List<RemoteTorrent> remote,
            boolean retryUnconfirmed, boolean automatic) {
        hydrate(selected);
        var byHash=TorrentRemovalSafety.index(remote);
        var paths=TorrentRemovalSafety.paths(remote);
        var owners=new HashMap<String,Set<Long>>();
        for (var row:downloads.findByClientInstanceId(tracking.instanceId()))
            owners.computeIfAbsent(row.getHash(),key -> new HashSet<>()).add(row.getEplId());
        for (var book:books.findTorrentIdentities()) for (var hash:magnets.hashes(book.getLinks()))
            owners.computeIfAbsent(hash,key -> new HashSet<>()).add(book.getEplId());
        var pending=entries.findByClientInstanceIdAndStateIn(tracking.instanceId(),PENDING);
        var protectedTargets=new HashSet<String>();
        var policies=new HashMap<String,Set<PreviousVersions>>();
        var uncertain=new HashSet<String>();
        for (var e:pending) {
            if (Boolean.TRUE.equals(e.getImmediate()) && !Boolean.TRUE.equals(e.getAutomatic()) && e.getState()!=UpdateCleanup.State.REQUESTED) continue;
            for (var hash:targets(e)) {
                var torrent=byHash.get(hash);
                protectedTargets.add(torrent==null ? hash : torrent.hash());
            }
            var torrent=byHash.get(e.getHash());
            var hash=torrent==null ? e.getHash() : torrent.hash();
            policies.computeIfAbsent(hash,k -> new HashSet<>()).add(e.getPreviousVersions());
            if (e.getState()==UpdateCleanup.State.REQUESTED) uncertain.add(hash);
        }
        var observedHashes=new HashSet<String>();
        for (var entry:selected) if (entry.getClientInstanceId().equals(tracking.instanceId())) {
            observedHashes.add(entry.getHash()); observedHashes.addAll(targets(entry));
        }
        tracking.observeSelected(byHash,observedHashes);
        var dispatched=new HashSet<String>();
        for (var entry:selected) {
            // Reload: a duplicate hash earlier in this batch may already have resolved this request.
            var fresh=entries.findById(entry.getId()).orElseThrow();
            entry.setState(fresh.getState());
            if (!PENDING.contains(entry.getState())) continue;
            if (!entry.getClientInstanceId().equals(tracking.instanceId())) continue;
            // Automatic requests were authorized when persisted. Manual actions still require current permissions.
            if (!automatic) {
                Permission.require(Permission.TORRENT_CLEANUP);
                if (entry.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES) Permission.require(Permission.TORRENT_FILES_DELETE);
            }
            touch(entry);
            try {
                var old=byHash.get(entry.getHash());
                if (old==null) { resolve(Set.of(entry.getHash())); continue; }
                if (uncertain.contains(old.hash()) && !retryUnconfirmed) {
                    save(entry,UpdateCleanup.State.REQUESTED,"Eliminación sin confirmar; no se repetirá automáticamente"); continue;
                }
                var targetHashes=targets(entry);
                if (Boolean.TRUE.equals(entry.getImmediate()) && Boolean.TRUE.equals(entry.getAutomatic())) {
                    if(targetHashes.isEmpty() || !Boolean.TRUE.equals(entry.getReplacementAccepted())) {
                        save(entry,UpdateCleanup.State.WAITING,"El cliente todavía no ha aceptado todos los torrents de la nueva revisión");continue;
                    }
                }
                if (!Boolean.TRUE.equals(entry.getImmediate()) && (targetHashes.isEmpty()
                        || targetHashes.stream().anyMatch(h -> byHash.get(h)==null || byHash.get(h).status()!=DownloadStatus.DOWNLOADED))) {
                    save(entry,UpdateCleanup.State.WAITING,"La nueva revisión todavía no está completa en el cliente"); continue;
                }
                if (protectedTargets.contains(old.hash()) || targetHashes.stream().anyMatch(old.aliases()::contains)) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Torrent necesario para una revisión de sustitución pendiente"); continue;
                }
                if (old.aliases().stream().anyMatch(h -> owners.getOrDefault(h,Set.of()).stream().anyMatch(id -> !id.equals(entry.getEplId())))) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Torrent compartido con otro libro"); continue;
                }
                if ((!Boolean.TRUE.equals(entry.getImmediate()) || Boolean.TRUE.equals(entry.getAutomatic())) && policies.getOrDefault(old.hash(),Set.of()).size()>1) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Políticas de borrado incompatibles para el mismo torrent"); continue;
                }
                boolean files=entry.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES;
                if (files && !TorrentRemovalSafety.exclusivePath(old,paths)) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Rutas compartidas o no verificables; no se borran archivos"); continue;
                }
                save(entry,UpdateCleanup.State.REQUESTED,"Eliminación solicitada; pendiente de confirmar ausencia");
                if (!dispatched.add(old.hash())) continue;
                try {
                    if (automatic) EventContext.withActor(new EventContext.Actor(entry.getActorId(),entry.getActorUsername(),entry.getActorKind()),() ->
                        events.run(com.rlibanez.eplsync.events.EventJournal.Category.TORRENT,"CLEANUP",
                            Map.of("eplId",entry.getEplId(),"cleanupId",entry.getId(),"deleteFiles",files),
                            () -> {adapter.deleteTorrent(old.hash(),files); return true;},result -> Map.of("requested",true)));
                    else adapter.deleteTorrent(old.hash(),files);
                }
                catch (RuntimeException ex) {
                    save(entry,UpdateCleanup.State.REQUESTED,"No se pudo confirmar la eliminación; no se repetirá automáticamente");
                    log.warn("Eliminación sin confirmar: solicitud={}, tipo={}",entry.getId(),ex.getClass().getSimpleName());
                }
            } catch (RuntimeException ex) {
                log.warn("Limpieza pendiente: solicitud={}, tipo={}",entry.getId(),ex.getClass().getSimpleName());
                if (entry.getState()!=UpdateCleanup.State.REQUESTED) save(entry,UpdateCleanup.State.BLOCKED,"No se pudo completar la comprobación de seguridad");
            }
        }
        if (!dispatched.isEmpty()) {
            try {
                var after=TorrentRemovalSafety.index(adapter.listTorrents());
                for (var hash:dispatched) {
                    var old=byHash.get(hash);
                    if (old.aliases().stream().noneMatch(after::containsKey)) resolve(old.aliases());
                }
            } catch (RuntimeException ex) {
                for (var entry:selected) if (entry.getState()==UpdateCleanup.State.REQUESTED)
                    save(entry,UpdateCleanup.State.REQUESTED,"No se pudo confirmar la ausencia; repetir la comprobación");
                log.warn("Confirmación de borrado pendiente: tipo={}",ex.getClass().getSimpleName());
            }
        }
        return entries.findAllById(selected.stream().map(UpdateCleanup::getId).toList()).stream()
            .map(e -> new Result(e.getId(),e.getDownloadId(),e.getState(),e.getMessage())).toList();
    }
    private List<String> targets(UpdateCleanup entry) {
        if (entry.getTargetHashes()==null) throw new IllegalStateException("Faltan dependencias de limpieza");
        return Arrays.asList(mapper.readValue(entry.getTargetHashes(),String[].class));
    }
    private void touch(UpdateCleanup entry) { entry.setLastCheckedAt(Instant.now()); entries.saveAndFlush(entry); }
    private void save(UpdateCleanup entry,UpdateCleanup.State state,String message) {
        // Never forget an uncertain write until absence is confirmed.
        if (entry.getState()==UpdateCleanup.State.REQUESTED && state!=UpdateCleanup.State.REMOVED) state=UpdateCleanup.State.REQUESTED;
        boolean newlyBlocked=state==UpdateCleanup.State.BLOCKED && (entry.getState()!=state || !Objects.equals(entry.getMessage(),message));
        entry.setState(state); entry.setMessage(message); entry.setUpdatedAt(Instant.now()); entries.saveAndFlush(entry);
        if(newlyBlocked && Boolean.TRUE.equals(entry.getAutomatic()))
            EventContext.withActor(new EventContext.Actor(entry.getActorId(),entry.getActorUsername(),entry.getActorKind()),() -> {
                events.rejected(com.rlibanez.eplsync.events.EventJournal.Category.TORRENT,"CLEANUP",
                Map.of("eplId",entry.getEplId(),"cleanupId",entry.getId(),"deleteFiles",entry.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES),
                entry.getLastCheckedAt()==null ? Instant.now() : entry.getLastCheckedAt(),new com.rlibanez.eplsync.exception.UserInputException(message));
                return null;
            });
    }
    public void resolve(Set<String> aliases) {
        tx.executeWithoutResult(status -> {
            for (var entry:entries.findByClientInstanceIdAndHashInAndStateIn(tracking.instanceId(),aliases,PENDING))
                save(entry,UpdateCleanup.State.REMOVED,"Ausencia confirmada en el cliente; historial conservado");
            for (var row:downloads.findByClientInstanceIdAndHashIn(tracking.instanceId(),aliases)) {
                row.setStatus(DownloadStatus.NOT_FOUND); row.setLastCheckedAt(Instant.now()); row.setLastError(null); downloads.save(row);
            }
        });
    }
}
