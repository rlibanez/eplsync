package com.rlibanez.eplsync.torrent.updates;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
public class SelectedUpdateSender {
    public record Request(List<Long> ids,List<DownloadStatus> states,PreviousVersions previousVersions,Boolean confirmFiles,
        TorrentDownloadRequest options,Integer concurrency,Integer batchSize,String interval,MultipleHashes multipleHashes) {}
    private final RevisionUpdates updates;
    private final BulkStore bulk;
    private final CatalogBookRepository books;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentDownloadService preparation;
    private final MagnetLinkBuilder magnets;
    private final TorrentProperties properties;
    private final UpdatePlanRepository plans;
    private final UpdateCleanupRepository cleanup;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public SelectedUpdateSender(RevisionUpdates updates,BulkStore bulk,CatalogBookRepository books,DownloadRepository downloads,
        DownloadTrackingService tracking,TorrentDownloadService preparation,MagnetLinkBuilder magnets,TorrentProperties properties,
        UpdatePlanRepository plans,UpdateCleanupRepository cleanup) {
        this.updates=updates;this.bulk=bulk;this.books=books;this.downloads=downloads;this.tracking=tracking;
        this.preparation=preparation;this.magnets=magnets;this.properties=properties;this.plans=plans;this.cleanup=cleanup;
    }
    @Transactional(timeout=120)
    public BulkStore.View create(Request input) {
        if(input==null || input.ids()==null || input.ids().isEmpty() || input.ids().size()>10000
            || input.ids().stream().anyMatch(id -> id==null || id<1) || new HashSet<>(input.ids()).size()!=input.ids().size())
            throw new UserInputException("Selecciona entre 1 y 10000 libros, sin duplicados");
        var selection=new UpdatePreferences.Preferences(input.states());RevisionUpdates.validate(selection,0,1,"eplId,asc");
        var request=new UpdateRequest(input.previousVersions(),input.options(),input.batchSize(),input.concurrency(),input.interval(),input.multipleHashes());
        if(request.options()!=null && request.options().hash()!=null) throw new UserInputException("Las actualizaciones no admiten un hash específico");
        if(request.policy()!=PreviousVersions.KEEP) Permission.require(Permission.TORRENT_CLEANUP);
        if(request.policy()==PreviousVersions.REMOVE_TORRENT_AND_FILES) {
            Permission.require(Permission.TORRENT_FILES_DELETE);
            if(!Boolean.TRUE.equals(input.confirmFiles())) throw new UserInputException("Confirma la eliminación irreversible de los archivos anteriores");
        }
        var policy=input.multipleHashes()==null ? properties.getBulk().getMultipleHashes() : input.multipleHashes();
        var job=bulk.beginPrepared(request.bulk());
        var plan=new UpdatePlan();plan.setJobId(job.getId());plan.setClientInstanceId(tracking.instanceId());plan.setPreviousVersions(request.policy());
        plan.setAutomaticCleanup(request.policy()!=PreviousVersions.KEEP);plan.setSnapshot("{\"items\":[]}");plan.setCreatedAt(Instant.now());plans.saveAndFlush(plan);
        long count=0,position=0,deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
        for(var id:input.ids()) {
            if(System.nanoTime()>=deadline || Thread.currentThread().isInterrupted()) throw new UserInputException("La preparación superó el tiempo máximo; reduce la selección");
            if(updates.search(selection,0,1,"eplId,asc",id).items().isEmpty()) continue;
            var book=books.findById(id).orElseThrow();var hashes=magnets.hashes(book.getLinks());
            if(hashes.isEmpty() || hashes.size()>1 && policy==MultipleHashes.SKIP) continue;
            var targets=policy==MultipleHashes.ALL ? hashes : List.of(hashes.getFirst());
            for(var hash:targets) {
                var options=input.options();
                var command=preparation.prepare(book,options==null ? new TorrentDownloadRequest(hash,null,null,null,null)
                    : new TorrentDownloadRequest(hash,options.start(),options.savePath(),options.rename(),options.qbittorrent()));
                bulk.appendPrepared(job.getId(),command,position++);
            }
            count++;
            int page=0;
            while(true) {
                var history=downloads.findAll((root,q,cb) -> cb.and(cb.equal(root.get("clientInstanceId"),plan.getClientInstanceId()),
                    cb.equal(root.get("eplId"),id),cb.lessThan(root.get("revision"),book.getRevision())),PageRequest.of(page++,100,Sort.by("id")));
                for(var old:history) {
                    var entry=new UpdateCleanup();entry.setJobId(job.getId());entry.setDownloadId(old.getId());entry.setEplId(id);entry.setHash(old.getHash());
                    entry.setState(request.policy()==PreviousVersions.KEEP ? UpdateCleanup.State.KEPT : UpdateCleanup.State.WAITING);entry.setUpdatedAt(Instant.now());cleanup.save(entry);
                }
                em.flush();em.clear();if(!history.hasNext()) break;
            }
        }
        if(count==0) throw new UserInputException("Los libros seleccionados ya no tienen actualizaciones pendientes que se puedan enviar");
        return bulk.finishPrepared(job.getId(),count);
    }
}
