package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.*;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;
import java.util.*;

@Service
public class UpdatePlanner {
    private final DownloadRepository downloads;
    private final CatalogBookRepository books;
    private final DownloadTrackingService tracking;
    private final TorrentProperties properties;
    private final MagnetLinkBuilder magnets;
    private final TorrentDownloadService preparation;
    private final BulkStore bulk;
    private final BulkItemRepository items;
    private final UpdatePlanRepository plans;
    private final UpdateCleanupRepository cleanup;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public UpdatePlanner(DownloadRepository downloads, CatalogBookRepository books, DownloadTrackingService tracking,
            TorrentProperties properties, MagnetLinkBuilder magnets, TorrentDownloadService preparation, BulkStore bulk,
            BulkItemRepository items, UpdatePlanRepository plans, UpdateCleanupRepository cleanup) {
        this.downloads=downloads; this.books=books; this.tracking=tracking; this.properties=properties;
        this.magnets=magnets; this.preparation=preparation; this.bulk=bulk; this.items=items; this.plans=plans; this.cleanup=cleanup;
    }
    public record Existing(String id,double revision,String hash,DownloadStatus status) {}
    public record Candidate(Long eplId,String title,double catalogRevision,List<Existing> existingDownloads,
            List<String> targetHashes,long existingDownloadsTotal) {
        public Candidate(Long id,String title,double revision,List<Existing> existing,List<String> hashes) {
            this(id,title,revision,existing,hashes,existing.size());
        }
    }
    /** Legacy plans remain readable; new plans derive snapshots from their frozen job commands. */
    public record Snapshot(List<Candidate> items) {}
    public static List<Candidate> visible(List<Candidate> candidates) {
        if (com.rlibanez.eplsync.security.Permission.has(com.rlibanez.eplsync.security.Permission.BOOK_HISTORY_READ)) return candidates;
        return candidates.stream().map(c -> new Candidate(c.eplId(),c.title(),c.catalogRevision(),List.of(),c.targetHashes(),0)).toList();
    }
    public enum Selection { NEW,UPDATES,BOTH }

    private Specification<CatalogBook> eligible(String instance,boolean includeNotFound,Selection selection) {
        return (root,query,cb) -> {
            var history=query.subquery(String.class); var h=history.from(DownloadRecord.class);
            history.select(h.get("id")).where(cb.equal(h.get("eplId"),root.get("eplId")),cb.equal(h.get("clientInstanceId"),instance));
            var eligible=query.subquery(String.class); var e=eligible.from(DownloadRecord.class);
            var bad=includeNotFound ? List.of(DownloadStatus.ERROR,DownloadStatus.UNKNOWN)
                    : List.of(DownloadStatus.ERROR,DownloadStatus.UNKNOWN,DownloadStatus.NOT_FOUND);
            eligible.select(e.get("id")).where(cb.equal(e.get("eplId"),root.get("eplId")),cb.equal(e.get("clientInstanceId"),instance),cb.not(e.get("status").in(bad)));
            var newer=query.subquery(String.class); var n=newer.from(DownloadRecord.class);
            newer.select(n.get("id")).where(cb.equal(n.get("eplId"),root.get("eplId")),cb.equal(n.get("clientInstanceId"),instance),
                    cb.not(n.get("status").in(bad)),cb.greaterThanOrEqualTo(n.get("revision"),root.get("revision")));
            var active=query.subquery(String.class); var i=active.from(BulkItem.class); var j=active.from(BulkJob.class);
            active.select(i.get("id")).where(cb.equal(i.get("jobId"),j.get("id")),cb.equal(i.get("eplId"),root.get("eplId")),
                    cb.equal(j.get("targetFingerprint"),instance),i.get("state").in(BulkItem.State.PENDING,BulkItem.State.IN_FLIGHT),
                    cb.not(j.get("state").in(BulkJob.State.COMPLETED,BulkJob.State.CANCELLED)));
            var isNew=cb.not(cb.exists(history)); var isUpdate=cb.and(cb.exists(eligible),cb.not(cb.exists(newer)));
            return cb.and(cb.not(cb.exists(active)),selection==Selection.NEW ? isNew
                    : selection==Selection.UPDATES ? isUpdate : cb.or(isNew,isUpdate));
        };
    }
    private static void budget(long deadline) {
        if (System.nanoTime()>=deadline || Thread.currentThread().isInterrupted())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.REQUEST_TIMEOUT,
                    "La selección de torrents ha superado el tiempo máximo de 2 minutos. Reduce los filtros e inténtalo de nuevo.");
    }
    private void scan(CatalogBookFilter filter,boolean includeNotFound,MultipleHashes policy,Selection selection,
            java.util.function.Consumer<Candidate> consume) {
        String instance=tracking.instanceId(); long after=0;
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
        var base=CatalogBookSpecifications.fromFilter(filter).and(eligible(instance,includeNotFound,selection));
        while (true) {
            budget(deadline); long cursor=after;
            var spec=base.and((root,q,cb) -> cb.greaterThan(root.get("eplId"),cursor));
            var batch=books.findBy(spec,q -> q.as(CatalogBookRepository.UpdateIdentity.class)
                    .sortBy(Sort.by("eplId")).slice(PageRequest.of(0,100))).getContent();
            if (batch.isEmpty()) break;
            for (var book:batch) {
                budget(deadline); after=book.getEplId();
                if (book.getRevision()==null || !Double.isFinite(book.getRevision())) continue;
                var hashes=magnets.hashes(book.getLinks());
                if (hashes.isEmpty() || hashes.size()>1 && policy==MultipleHashes.SKIP) continue;
                var targets=policy==MultipleHashes.ALL ? hashes : List.of(hashes.getFirst());
                boolean blocked=false;
                for(int start=0;start<targets.size();start+=250) {
                    var chunk=targets.subList(start,Math.min(start+250,targets.size()));
                    if(downloads.count((root,q,cb) -> cb.and(cb.equal(root.get("clientInstanceId"),instance),
                            cb.equal(root.get("eplId"),book.getEplId()),root.get("hash").in(chunk),
                            cb.or(cb.not(root.get("status").in(DownloadStatus.ERROR,DownloadStatus.NOT_FOUND)),
                                    cb.notEqual(root.get("revision"),book.getRevision()))))>0) { blocked=true; break; }
                }
                if(!blocked) consume.accept(new Candidate(book.getEplId(),book.getTitle(),book.getRevision(),List.of(),targets));
            }
            if(batch.size()<100) break;
        }
    }
    private Specification<DownloadRecord> older(Candidate candidate) {
        String instance=tracking.instanceId();
        return (root,q,cb) -> cb.and(cb.equal(root.get("clientInstanceId"),instance),cb.equal(root.get("eplId"),candidate.eplId()),
                cb.lessThan(root.get("revision"),candidate.catalogRevision()));
    }
    @Transactional(readOnly=true,timeout=120)
    public List<Candidate> preview(Long id,boolean includeNotFound,MultipleHashes hashes) {
        var filter=new CatalogBookFilter(); if(id!=null) filter.setEplId(id);
        return preview(filter,includeNotFound,hashes,Selection.UPDATES);
    }
    @Transactional(readOnly=true,timeout=120)
    public List<Candidate> preview(CatalogBookFilter filter,boolean includeNotFound,MultipleHashes hashes,Selection selection) {
        return previewPage(filter,includeNotFound,hashes,selection,0,50).items();
    }
    @Transactional(readOnly=true,timeout=120)
    public PageResponse<Candidate> previewPage(CatalogBookFilter filter,boolean includeNotFound,MultipleHashes hashes,
            Selection selection,int page,int size) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        var policy=hashes==null ? properties.getBulk().getMultipleHashes() : hashes;
        var result=new ArrayList<Candidate>(); long[] total={0}; long offset=(long)page*size;
        scan(filter,includeNotFound,policy,selection,c -> {
            long position=total[0]++;
            if(position<offset || position>=offset+size) return;
            var history=downloads.findAll(older(c),PageRequest.of(0,20,Sort.by(Sort.Order.desc("revision"),Sort.Order.asc("id"))));
            result.add(new Candidate(c.eplId(),c.title(),c.catalogRevision(),history.getContent().stream()
                    .map(row -> new Existing(row.getId(),row.getRevision(),row.getHash(),row.getStatus())).toList(),c.targetHashes(),history.getTotalElements()));
            em.clear();
        });
        int pages=(int)((total[0]+size-1)/size);
        return new PageResponse<>(visible(result),new PageResponse.PageMeta(page,size,total[0],pages,page==0,page>=pages-1,page<pages-1,page>0));
    }
    @Transactional(timeout=120)
    public BulkStore.View create(Long id,boolean includeNotFound,UpdateRequest request) {
        var filter=new CatalogBookFilter(); if(id!=null) filter.setEplId(id);
        return create(filter,includeNotFound,request,Selection.UPDATES);
    }
    @Transactional(timeout=120)
    public BulkStore.View create(CatalogBookFilter filter,boolean includeNotFound,UpdateRequest request,Selection selection) {
        var input=request==null ? new UpdateRequest(null,null,null,null,null,null) : request;
        if(input.options()!=null && input.options().hash()!=null) throw new com.rlibanez.eplsync.exception.UserInputException("Las actualizaciones no admiten options.hash");
        if(input.policy()!=PreviousVersions.KEEP) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_CLEANUP);
        if(input.policy()==PreviousVersions.REMOVE_TORRENT_AND_FILES) com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_FILES_DELETE);
        var job=bulk.beginPrepared(input.bulk()); job.setType(BulkJob.Type.UPDATE);job.setPreviousVersions(input.policy()); String jobId=job.getId();
        var plan=new UpdatePlan(); plan.setJobId(jobId); plan.setClientInstanceId(tracking.instanceId());
        plan.setPreviousVersions(input.policy()); plan.setCleanupTiming(input.timing()); plan.setAutomaticCleanup(input.policy()!=PreviousVersions.KEEP && input.timing()==CleanupTiming.IMMEDIATE); plan.setCreatedAt(Instant.now()); plan.setSnapshot("{\"items\":[]}"); plans.saveAndFlush(plan);
        long[] counts={0,0};
        scan(filter,includeNotFound,input.multipleHashes()==null ? properties.getBulk().getMultipleHashes() : input.multipleHashes(),selection,c -> {
            var book=books.findById(c.eplId()).orElseThrow(); counts[0]++;
            for(var hash:c.targetHashes()) {
                var options=input.options();
                var command=preparation.prepare(book,options==null ? new TorrentDownloadRequest(hash,null,null,null,null)
                        : new TorrentDownloadRequest(hash,options.start(),options.savePath(),options.rename(),options.qbittorrent()));
                bulk.appendPrepared(jobId,command,counts[1]++);
                if(counts[1]%25==0) { em.flush(); em.clear(); }
            }
            int offset=0;
            while(true) {
                var history=downloads.findAll(older(c),PageRequest.of(offset++,100,Sort.by("id")));
                for(var old:history) {
                    var entry=new UpdateCleanup(); entry.setJobId(jobId); entry.setDownloadId(old.getId()); entry.setEplId(c.eplId());
                    entry.setHash(old.getHash()); entry.setUpdatedAt(Instant.now());
                    entry.setState(input.policy()==PreviousVersions.KEEP ? UpdateCleanup.State.KEPT : UpdateCleanup.State.WAITING);
                    entry.initialize(plan, c.targetHashes(), com.rlibanez.eplsync.events.EventContext.actor()); cleanup.save(entry);
                }
                em.flush(); em.clear(); if(!history.hasNext()) break;
            }
        });
        return bulk.finishPrepared(jobId,counts[0]);
    }
}
