package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Service
public class UpdateCleanupService {
    private final UpdatePlanRepository plans;
    private final UpdateCleanupRepository entries;
    private final DownloadTrackingService tracking;
    private final TorrentClientService client;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    private final CleanupQueue queue;
    private final com.rlibanez.eplsync.events.EventJournal events;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public UpdateCleanupService(UpdatePlanRepository plans,UpdateCleanupRepository entries,
            DownloadTrackingService tracking,TorrentClientService client,CleanupQueue queue,com.rlibanez.eplsync.events.EventJournal events) {
        this.plans=plans;this.entries=entries;this.tracking=tracking;this.client=client;this.queue=queue;this.events=events;
    }

    public record View(String jobId,PreviousVersions previousVersions,List<UpdatePlanner.Candidate> updates,List<UpdateCleanup> items,
            com.rlibanez.eplsync.dto.PageResponse.PageMeta updatesMeta,com.rlibanez.eplsync.dto.PageResponse.PageMeta itemsMeta) {}
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public View view(String jobId) { return view(jobId,0,20); }
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public View view(String jobId,int page,int size) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        var plan=plan(jobId); List<UpdatePlanner.Candidate> updates; long total;
        if (plan.getSnapshot().equals("{\"items\":[]}")) {
            total=em.createQuery("select count(distinct i.eplId) from BulkItem i where i.jobId=:id",Long.class).setParameter("id",jobId).getSingleResult();
            var ids=em.createQuery("select distinct i.eplId from BulkItem i where i.jobId=:id order by i.eplId",Long.class)
                    .setParameter("id",jobId).setFirstResult(page*size).setMaxResults(size).getResultList();
            updates=ids.stream().map(id -> frozenCandidate(plan,id)).toList();
        } else {
            var legacy=mapper.readValue(plan.getSnapshot(),UpdatePlanner.Snapshot.class).items(); total=legacy.size();
            updates=legacy.stream().skip((long)page*size).limit(size).toList();
        }
        var history=entries.findByJobIdOrderByEplIdAsc(jobId,org.springframework.data.domain.PageRequest.of(page,size,
                org.springframework.data.domain.Sort.by("eplId","id")));
        return new View(jobId,plan.getPreviousVersions(),UpdatePlanner.visible(updates),history.getContent(),meta(page,size,total),meta(page,size,history.getTotalElements()));
    }
    private com.rlibanez.eplsync.dto.PageResponse.PageMeta meta(int page,int size,long total) {
        int pages=(int)((total+size-1)/size);
        return new com.rlibanez.eplsync.dto.PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page<pages-1,page>0);
    }
    /** Frozen commands survive later catalogue changes without one giant JSON snapshot. */
    private UpdatePlanner.Candidate frozenCandidate(UpdatePlan plan,long id) {
        var json=em.createQuery("select i.commandJson from BulkItem i where i.jobId=:job and i.eplId=:id order by i.position",String.class)
                .setParameter("job",plan.getJobId()).setParameter("id",id).setMaxResults(1).getSingleResult();
        var command=mapper.readValue(json,com.rlibanez.eplsync.torrent.TorrentDownload.class);
        var hashes=em.createQuery("select i.hash from BulkItem i where i.jobId=:job and i.eplId=:id order by i.position",String.class)
                .setParameter("job",plan.getJobId()).setParameter("id",id).getResultList();
        var old=em.createQuery("select d from DownloadRecord d,UpdateCleanup c where c.downloadId=d.id and c.jobId=:job and c.eplId=:id order by d.revision desc,d.id",DownloadRecord.class)
                .setParameter("job",plan.getJobId()).setParameter("id",id).setMaxResults(20).getResultList();
        long count=em.createQuery("select count(c) from UpdateCleanup c where c.jobId=:job and c.eplId=:id",Long.class)
                .setParameter("job",plan.getJobId()).setParameter("id",id).getSingleResult();
        return new UpdatePlanner.Candidate(id,command.book().getTitle(),command.book().getRevision(),old.stream()
                .map(row -> new UpdatePlanner.Existing(row.getId(),row.getRevision(),row.getHash(),row.getStatus())).toList(),hashes,count);
    }
    private UpdatePlan plan(String jobId) {
        return plans.findById(jobId).orElseThrow(() -> new TorrentOperationException(HttpStatus.NOT_FOUND,
                "Trabajo de actualización inexistente"));
    }

    public View clean(String jobId) { return clean(jobId,false); }
    public View clean(String jobId, boolean retryUnconfirmed) {
        var plan=plan(jobId);
        if (!plan.getClientInstanceId().equals(tracking.instanceId()))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"El destino del trabajo ha cambiado");
        var selected=pending(jobId);
        if (!selected.isEmpty()) {
            authorize(selected);
            client.exclusiveClient(adapter -> queue.execute(selected,adapter,adapter.listTorrents(),retryUnconfirmed,false));
        }
        return view(jobId);
    }
    private List<UpdateCleanup> pending(String jobId) {
        var selected=entries.findByJobIdOrderByEplIdAsc(jobId);
        queue.hydrate(selected);
        return selected.stream().filter(e -> CleanupQueue.PENDING.contains(e.getState())).toList();
    }
    private void authorize(List<UpdateCleanup> selected) {
        com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_CLEANUP);
        if (selected.stream().anyMatch(e -> e.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES))
            com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_FILES_DELETE);
    }
    public DownloadTrackingService.SyncResult synchronize(boolean dryRun,boolean details) {
        var startedAt=java.time.Instant.now();
        // The tracking service audits reconciliation; failures before entering it need their own event.
        var trackingStarted=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            return client.exclusiveClient(adapter -> {
                var remote=adapter.listTorrents();
                trackingStarted.set(true);
                var result=tracking.sync(() -> remote,dryRun,details);
                if (!dryRun && com.rlibanez.eplsync.security.Permission.has(com.rlibanez.eplsync.security.Permission.TORRENT_CLEANUP)) {
                    var selected=queue.manualPending();
                    if (!selected.isEmpty()) result=result.withCleanup(queue.execute(selected,adapter,remote,false,false));
                }
                return result;
            });
        } catch (RuntimeException ex) {
            if (!trackingStarted.get()) events.rejected(com.rlibanez.eplsync.events.EventJournal.Category.TORRENT,
                dryRun ? "SYNC_PREVIEW" : "SYNC",Map.of("dryRun",dryRun),startedAt,ex);
            throw ex;
        }
    }
    public record JobResult(String jobId,long checked,long removed,long waiting,long blocked,long requested,String error) {}
    public record GlobalResult(int selectedJobs,long failedJobs,long checked,long removed,long waiting,long blocked,long requested,List<JobResult> jobs) {}
    public GlobalResult cleanAll(boolean retryUnconfirmed) {
        var selected=plans.pending(PreviousVersions.KEEP,CleanupQueue.PENDING);
        var requests=new ArrayList<UpdateCleanup>();
        var errors=new HashMap<String,String>();
        var checked=new HashMap<String,Set<String>>();
        for (var plan:selected) {
            var rows=pending(plan.getJobId()); authorize(rows);
            checked.put(plan.getJobId(),rows.stream().map((UpdateCleanup entryValue) -> java.util.Objects.requireNonNull(entryValue).getId()).collect(java.util.stream.Collectors.toSet()));
            if (plan.getClientInstanceId().equals(tracking.instanceId())) requests.addAll(rows);
            else errors.put(plan.getJobId(),"El destino del trabajo ha cambiado; restaura su cliente y URL");
        }
        if (!requests.isEmpty()) {
            var results=client.exclusiveClient(adapter -> queue.execute(requests,adapter,adapter.listTorrents(),retryUnconfirmed,false));
            var origins=new HashMap<String,String>();requests.forEach(e -> origins.put(e.getId(),e.getJobId()));
            for (var result:results) if (result.state()==UpdateCleanup.State.REQUESTED && result.message()!=null && result.message().startsWith("No se pudo"))
                errors.put(origins.get(result.id()),result.message());
        }
        var jobs=new ArrayList<JobResult>();
        for (var plan:selected) {
            var rows=entries.findByJobIdOrderByEplIdAsc(plan.getJobId()).stream().filter(e -> checked.get(plan.getJobId()).contains(e.getId())).toList();
            var counts=new EnumMap<UpdateCleanup.State,Long>(UpdateCleanup.State.class);
            rows.forEach(e -> counts.merge(e.getState(),1L,(left, right) -> java.util.Objects.requireNonNull(left) + java.util.Objects.requireNonNull(right)));
            jobs.add(new JobResult(plan.getJobId(),rows.size(),counts.getOrDefault(UpdateCleanup.State.REMOVED,0L),
                counts.getOrDefault(UpdateCleanup.State.WAITING,0L),counts.getOrDefault(UpdateCleanup.State.BLOCKED,0L),
                counts.getOrDefault(UpdateCleanup.State.REQUESTED,0L),errors.get(plan.getJobId())));
        }
        return new GlobalResult(jobs.size(),jobs.stream().filter(j -> j.error()!=null).count(),
            jobs.stream().mapToLong((JobResult entryValue) -> java.util.Objects.requireNonNull(entryValue).checked()).sum(),jobs.stream().mapToLong((JobResult entryValue) -> java.util.Objects.requireNonNull(entryValue).removed()).sum(),
            jobs.stream().mapToLong((JobResult entryValue) -> java.util.Objects.requireNonNull(entryValue).waiting()).sum(),jobs.stream().mapToLong((JobResult entryValue) -> java.util.Objects.requireNonNull(entryValue).blocked()).sum(),
            jobs.stream().mapToLong((JobResult entryValue) -> java.util.Objects.requireNonNull(entryValue).requested()).sum(),List.copyOf(jobs));
    }
}
