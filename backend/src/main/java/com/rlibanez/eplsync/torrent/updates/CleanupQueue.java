package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.events.EventJournal;
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
    @org.springframework.beans.factory.annotation.Autowired private EventJournal events;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.jobs.RetryAuthorization retryAuthorization;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public record Result(String id, String downloadId, Long eplId, String hash, PreviousVersions policy, UpdateCleanup.State state, String message) {}
    private static final String NO_DATA = "Sin datos descargados que borrar";

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
            .filter(e -> e.getPreviousVersions()==PreviousVersions.KEEP ? Permission.has(Permission.DOWNLOADS_DELETE) : Permission.has(Permission.TORRENT_CLEANUP))
            .filter(e -> !Boolean.TRUE.equals(e.getDeleteHistory()) || Permission.has(Permission.DOWNLOADS_DELETE))
            .filter(e -> e.getPreviousVersions()!=PreviousVersions.REMOVE_TORRENT_AND_FILES || Permission.has(Permission.TORRENT_FILES_DELETE))
            .sorted(Comparator.comparing((UpdateCleanup entryValue) -> java.util.Objects.requireNonNull(entryValue).getCreatedAt()).thenComparing((UpdateCleanup entryValue) -> java.util.Objects.requireNonNull(entryValue).getId())).toList();
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
            if(!CleanupBudget.available()) break;
            // Reload: a duplicate hash earlier in this batch may already have resolved this request.
            var fresh=entries.findById(entry.getId()).orElseThrow();
            entry.setState(fresh.getState());
            if (!PENDING.contains(entry.getState())) continue;
            if (!entry.getClientInstanceId().equals(tracking.instanceId())) continue;
            // Automatic requests were authorized when persisted. Manual actions still require current permissions.
            if (!automatic) {
                if(entry.getPreviousVersions()!=PreviousVersions.KEEP) Permission.require(Permission.TORRENT_CLEANUP);
                if(Boolean.TRUE.equals(entry.getDeleteHistory())) Permission.require(Permission.DOWNLOADS_DELETE);
                if (entry.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES) Permission.require(Permission.TORRENT_FILES_DELETE);
            }
            touch(entry);
            try {
                var old=byHash.get(entry.getHash());
                if (old==null && entry.getPreviousVersions()!=PreviousVersions.KEEP) { resolve(Set.of(entry.getHash())); continue; }
                var job=entry.getJobId()==null ? null : em.find(com.rlibanez.eplsync.torrent.bulk.BulkJob.class,entry.getJobId());
                if (job!=null && (job.getState()==com.rlibanez.eplsync.torrent.bulk.BulkJob.State.PAUSED
                        || job.getState()==com.rlibanez.eplsync.torrent.bulk.BulkJob.State.CANCELLED)) continue;
                if (old!=null && uncertain.contains(old.hash()) && !retryUnconfirmed) {
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
                if(entry.getPreviousVersions()==PreviousVersions.KEEP) {
                    if(!Boolean.TRUE.equals(entry.getDeleteHistory())) continue;
                    if(protectedTargets.contains(old==null?entry.getHash():old.hash()) || targetHashes.contains(entry.getHash())) {
                        save(entry,UpdateCleanup.State.BLOCKED,"Registro necesario para una revisión de sustitución pendiente");continue;
                    }
                    synchronized(bulk) {tx.executeWithoutResult(status -> {
                        if(!claim(entry,"Eliminación del registro en curso")) return;
                        var row=downloads.findById(entry.getDownloadId()).orElse(null);
                        if(row!=null) {
                            if(!row.getEplId().equals(entry.getEplId()) || !row.getHash().equals(entry.getHash()) || !row.getClientInstanceId().equals(entry.getClientInstanceId()))
                                throw new IllegalStateException("La identidad del registro cambió");
                            downloads.delete(row);downloads.flush();
                        }
                        save(entry,UpdateCleanup.State.REMOVED,"Registro eliminado; torrent y archivos conservados");
                    });}
                    continue;
                }
                // KEEP can proceed without a remote torrent; client deletions require one.
                if (old==null) { resolve(Set.of(entry.getHash())); continue; }
                if (protectedTargets.contains(old.hash()) || targetHashes.stream().anyMatch(old.aliases()::contains)) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Torrent necesario para una revisión de sustitución pendiente"); continue;
                }
                if (old.aliases().stream().anyMatch(h -> owners.getOrDefault(h,Set.of()).stream().anyMatch(id -> !id.equals(entry.getEplId())))) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Torrent compartido con otro libro"); continue;
                }
                if ((!Boolean.TRUE.equals(entry.getImmediate()) || Boolean.TRUE.equals(entry.getAutomatic())) && policies.getOrDefault(old.hash(),Set.of()).size()>1) {
                    save(entry,UpdateCleanup.State.BLOCKED,"Políticas de borrado incompatibles para el mismo torrent"); continue;
                }
                boolean noData=old.awaitingMetadataWithoutData() && (old.contentPath()==null || old.contentPath().isBlank());
                boolean files=entry.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES && !noData;
                if (files) {
                    var safety=TorrentRemovalSafety.assess(old,paths);
                    if(safety==TorrentRemovalSafety.PathSafety.UNKNOWN) {
                        save(entry,UpdateCleanup.State.WAITING,"Comprobación de rutas pendiente; todavía no se puede verificar que los archivos sean exclusivos");continue;
                    }
                    if(safety==TorrentRemovalSafety.PathSafety.SHARED) {
                        save(entry,UpdateCleanup.State.BLOCKED,"Archivos o carpetas compartidos con otro torrent; no se borran archivos");continue;
                    }
                }
                if(!claim(entry,"Eliminación solicitada; pendiente de confirmar ausencia" + (noData ? ". " + NO_DATA : ""))) continue;
                if (!dispatched.add(old.hash())) continue;
                try {
                    adapter.deleteTorrent(old.hash(),files);
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
        if (!dispatched.isEmpty() && CleanupBudget.available()) {
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
        reportProblems(selected,automatic);
        return entries.findAllById(selected.stream().map((UpdateCleanup entryValue) -> java.util.Objects.requireNonNull(entryValue).getId()).toList()).stream()
            .map(e -> new Result(e.getId(),e.getDownloadId(),e.getEplId(),e.getHash(),e.getPreviousVersions(),e.getState(),e.getMessage())).toList();
    }
    /** Persist notification markers with the journal, so polling and restarts cannot repeat a block. */
    private void reportProblems(List<UpdateCleanup> selected,boolean automatic) {
        synchronized(bulk) {tx.executeWithoutResult(status -> {
            var changed=new LinkedHashMap<String,UpdateCleanup>();
            for(var candidate:selected) {
                if(candidate.getJobId()!=null && candidate.getJobId().startsWith("revision:")) continue;
                var entry=entries.findById(candidate.getId()).orElseThrow();em.refresh(entry);
                String signature=switch(entry.getState()) {
                    case BLOCKED -> "BLOCKED:"+entry.getMessage();
                    case REQUESTED -> "REQUESTED";
                    default -> null;
                };
                if(Objects.equals(signature,entry.getEventSignature())) continue;
                entry.setEventSignature(signature);entries.saveAndFlush(entry);
                if(signature!=null) changed.put(entry.getJobId()==null?entry.getId():entry.getJobId(),entry);
            }
            for(var entry:changed.values()) {
                var counts=entry.getJobId()==null ? Collections.singletonList(new Object[]{entry.getState(),1L}) :
                    em.createQuery("select c.state,count(c) from UpdateCleanup c where c.jobId=:job group by c.state",Object[].class)
                        .setParameter("job",entry.getJobId()).getResultList();
                var details=new LinkedHashMap<String,Object>();
                if(entry.getJobId()!=null) details.put("jobId",entry.getJobId());
                long removed=0,requested=0,total=0;
                for(var count:counts) {
                    var state=(UpdateCleanup.State)count[0];long number=((Number)count[1]).longValue();total+=number;
                    details.put(switch(state) {
                        case REMOVED -> "removed";
                        case REQUESTED -> "unconfirmed";
                        case BLOCKED -> "blocked";
                        case WAITING -> "waiting";
                        case KEPT -> "kept";
                        case CANCELLED -> "cancelled";
                    },number);
                    if(state==UpdateCleanup.State.REMOVED) removed=number;
                    if(state==UpdateCleanup.State.REQUESTED) requested=number;
                }
                details.put("total",total);
                var actor=automatic ? new EventContext.Actor(entry.getActorId(),entry.getActorUsername(),entry.getActorKind()) : EventContext.actor();
                var operationId=UUID.randomUUID().toString();
                events.recordAs(actor,EventJournal.Category.TORRENT,"CLEANUP",EventJournal.Outcome.STARTED,EventContext.origin(),operationId,
                    entry.getJobId()==null?Map.of():Map.of("jobId",entry.getJobId()));
                events.recordAs(actor,EventJournal.Category.TORRENT,"CLEANUP",
                    removed>0 || requested>0 ? EventJournal.Outcome.PARTIAL : EventJournal.Outcome.FAILED,
                    EventContext.origin(),operationId,details);
            }
        });}
    }
    private List<String> targets(UpdateCleanup entry) {
        if (entry.getTargetHashes()==null) throw new IllegalStateException("Faltan dependencias de limpieza");
        return Arrays.asList(mapper.readValue(entry.getTargetHashes(),String[].class));
    }
    private void touch(UpdateCleanup entry) {
        synchronized(bulk) {tx.executeWithoutResult(status -> {
            var fresh=entries.findById(entry.getId()).orElseThrow();
            em.refresh(fresh);
            fresh.setLastCheckedAt(Instant.now());entries.saveAndFlush(fresh);
            entry.setState(fresh.getState());entry.setMessage(fresh.getMessage());
            entry.setReplacementAccepted(fresh.getReplacementAccepted());
        });}
    }
    /** Commit the dispatch before releasing the monitor. Pause/cancel never waits for HTTP. */
    private boolean claim(UpdateCleanup entry,String message) {
        synchronized(bulk) {return Boolean.TRUE.equals(tx.execute(status -> {
            if(!CleanupBudget.available()) return false;
            var fresh=entries.findById(entry.getId()).orElseThrow();em.refresh(fresh);
            if(!PENDING.contains(fresh.getState())) return false;
            var job=entry.getJobId()==null?null:em.find(com.rlibanez.eplsync.torrent.bulk.BulkJob.class,entry.getJobId());
            if(job!=null) {
                em.refresh(job);
                if(job.getState()==com.rlibanez.eplsync.torrent.bulk.BulkJob.State.PAUSED
                    || job.getState()==com.rlibanez.eplsync.torrent.bulk.BulkJob.State.CANCELLED) return false;
            }
            long needed=((Number)em.createNativeQuery("SELECT count(*) FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id JOIN torrent_downloads d ON (d.id=i.record_id OR (d.epl_id=i.epl_id AND d.revision=CAST(i.revision AS REAL))) WHERE d.hash=:hash AND d.client_instance_id=:instance AND i.state IN ('PENDING','SENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','UNCERTAIN','CLEANUP_PENDING') AND (j.state NOT IN ('COMPLETED','CANCELLED') OR i.state='UNCERTAIN') AND NOT EXISTS(SELECT 1 FROM job_retry_items r WHERE r.kind='calibre' AND r.source_item_id=i.id)")
                .setParameter("hash",entry.getHash()).setParameter("instance",entry.getClientInstanceId()).getSingleResult()).longValue();
            if(needed>0) {save(entry,UpdateCleanup.State.WAITING,"EPUB necesario para un trabajo pendiente de Calibre");return false;}
            if(retryAuthorization!=null && !retryAuthorization.torrent(entry.getJobId(),entry.getPreviousVersions()!=PreviousVersions.KEEP,entry.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES,Boolean.TRUE.equals(entry.getDeleteHistory()))) {
                save(entry,UpdateCleanup.State.BLOCKED,"El usuario del reintento ya no tiene permisos para eliminar estas revisiones");return false;
            }
            save(entry,UpdateCleanup.State.REQUESTED,message);return true;
        }));}
    }
    private void save(UpdateCleanup entry,UpdateCleanup.State state,String message) {
        synchronized(bulk) {
            var next=state;var detail=message;
            tx.executeWithoutResult(status -> {
                var fresh=entries.findById(entry.getId()).orElseThrow();em.refresh(fresh);
                entry.setState(fresh.getState());entry.setMessage(fresh.getMessage());
                if(!PENDING.contains(fresh.getState())) return;
                saveCurrent(fresh,next,detail);
                entry.setState(fresh.getState());entry.setMessage(fresh.getMessage());
            });
        }
    }
    private void saveCurrent(UpdateCleanup entry,UpdateCleanup.State state,String message) {
        // Never forget an uncertain write until absence is confirmed.
        if (entry.getState()==UpdateCleanup.State.REQUESTED && state!=UpdateCleanup.State.REMOVED) state=UpdateCleanup.State.REQUESTED;
        if (entry.getState()==UpdateCleanup.State.REQUESTED && entry.getMessage()!=null && entry.getMessage().contains(NO_DATA))
            message=state==UpdateCleanup.State.REMOVED ? "Torrent eliminado; " + NO_DATA.toLowerCase(java.util.Locale.ROOT) : message.contains(NO_DATA) ? message : message + ". " + NO_DATA;
        if(state!=UpdateCleanup.State.BLOCKED && state!=UpdateCleanup.State.REQUESTED) entry.setEventSignature(null);
        entry.setState(state); entry.setMessage(message); entry.setUpdatedAt(Instant.now()); entries.saveAndFlush(entry);
        bulk.cleanupChanged(entry.getJobId());
    }
    public void resolve(Set<String> aliases) {
        synchronized(bulk) {tx.executeWithoutResult(status -> {
            var resolved=entries.findByClientInstanceIdAndHashInAndStateIn(tracking.instanceId(),aliases,PENDING);
            for (var entry:resolved) {
                if(entry.getPreviousVersions()==PreviousVersions.KEEP) continue;
                if(Boolean.TRUE.equals(entry.getDeleteHistory())) {
                    var row=downloads.findById(entry.getDownloadId()).orElse(null);
                    if(row!=null && row.getEplId().equals(entry.getEplId()) && row.getClientInstanceId().equals(entry.getClientInstanceId()) && row.getHash().equals(entry.getHash())) {
                        downloads.delete(row);downloads.flush();
                    }
                }
                save(entry,UpdateCleanup.State.REMOVED,Boolean.TRUE.equals(entry.getDeleteHistory())?"Ausencia confirmada en el cliente; registro eliminado":"Ausencia confirmada en el cliente; historial conservado");
            }
            for (var row:downloads.findByClientInstanceIdAndHashIn(tracking.instanceId(),aliases)) {
                row.setStatus(DownloadStatus.NOT_FOUND); row.setLastCheckedAt(Instant.now()); row.setLastError(null); downloads.save(row);
            }
        });}
    }
}
