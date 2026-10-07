package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.exception.*;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class HistoryActions {
    public record Selection(Long eplId,List<String> ids,Boolean deleteFiles,Boolean confirmFiles) {}
    public record Item(String id,DownloadStatus status,String cleanupState,String message) {}
    public record Result(List<Item> items) {}
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentClientService client;
    private final UpdateCleanupRepository entries;
    private final CleanupQueue queue;
    private final EventJournal events;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    public HistoryActions(DownloadRepository downloads,DownloadTrackingService tracking,TorrentClientService client,
            UpdateCleanupRepository entries,CleanupQueue queue,EventJournal events,org.springframework.transaction.PlatformTransactionManager manager) {
        this.downloads=downloads;this.tracking=tracking;this.client=client;this.entries=entries;this.queue=queue;this.events=events;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    private void requireBook(Selection selection) {
        if (selection==null || selection.eplId()==null || selection.eplId()<1) throw new UserInputException("EPL ID inválido");
    }
    private List<DownloadRecord> validate(Selection selection) {
        if (selection==null || selection.eplId()==null || selection.eplId()<1 || selection.ids()==null
                || selection.ids().isEmpty() || selection.ids().size()>1000
                || selection.ids().stream().anyMatch(id -> id==null || id.isBlank() || id.length()>64)
                || new HashSet<>(selection.ids()).size()!=selection.ids().size())
            throw new UserInputException("Selecciona entre 1 y 1000 registros del mismo libro, sin duplicados");
        var rows=downloads.findAllById(selection.ids());
        if (rows.size()!=selection.ids().size() || rows.stream().anyMatch(r -> !r.getEplId().equals(selection.eplId())))
            throw new UserInputException("Los registros seleccionados no pertenecen al libro");
        if (rows.stream().anyMatch(r -> !r.getClientInstanceId().equals(tracking.instanceId())))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"Los registros pertenecen a otro cliente; restaura su destino antes de actuar");
        return rows;
    }
    public Result refresh(Selection selection) {
        Permission.require(Permission.TORRENT_SYNC);
        requireBook(selection);
        return events.run(EventJournal.Category.TORRENT,"REFRESH_DOWNLOADS",Map.of("eplId",selection.eplId()),() ->
            client.exclusiveClient(adapter -> {
                var rows=validate(selection);
                var hashes=new HashSet<String>(); rows.forEach(r -> hashes.add(r.getHash()));
                var remote=TorrentRemovalSafety.index(adapter.listTorrents(hashes));
                tracking.observeSelected(remote,hashes);
                // Refresh confirms previous removals too, but never initiates a deletion.
                for (var hash:hashes) if (!remote.containsKey(hash)
                    && !entries.findByClientInstanceIdAndHashInAndStateIn(tracking.instanceId(),Set.of(hash),CleanupQueue.PENDING).isEmpty())
                    queue.resolve(Set.of(hash));
                return result(selection.ids());
            }),r -> Map.of("checked",r.items().size()));
    }
    public Result remove(Selection selection) { return remove(selection,false); }
    public Result remove(Selection selection,boolean retryUnconfirmed) {
        Permission.require(Permission.TORRENT_CLEANUP);
        requireBook(selection);
        boolean files=selection!=null && Boolean.TRUE.equals(selection.deleteFiles());
        if (files) {
            Permission.require(Permission.TORRENT_FILES_DELETE);
            if (!Boolean.TRUE.equals(selection.confirmFiles())) throw new UserInputException("Confirma la eliminación irreversible de los archivos");
        }
        return events.run(EventJournal.Category.TORRENT,"DELETE_DOWNLOADS",Map.of("eplId",selection.eplId(),"deleteFiles",files),() ->
            client.exclusiveClient(adapter -> {
                var rows=validate(selection);
                // Validate the complete response before persisting any removal intent.
                var remote=adapter.listTorrents(); TorrentRemovalSafety.index(remote);
                var actor=EventContext.actor();
                var requests=tx.execute(status -> {
                var pending=new ArrayList<UpdateCleanup>();
                for (var row:rows) {
                    var entry=new UpdateCleanup();
                    // A provenance token also works with SQLite installations retaining the old NOT NULL job_id constraint.
                    entry.setJobId("manual:"+entry.getId()); entry.setDownloadId(row.getId()); entry.setEplId(row.getEplId());entry.setHash(row.getHash());
                    entry.setClientInstanceId(row.getClientInstanceId()); entry.setPreviousVersions(files ? PreviousVersions.REMOVE_TORRENT_AND_FILES : PreviousVersions.REMOVE_TORRENT);
                    entry.setTargetHashes("[]");entry.setCreatedAt(Instant.now());entry.setUpdatedAt(entry.getCreatedAt());
                    entry.setAutomatic(false);entry.setImmediate(true);entry.setActorId(actor.id());entry.setActorUsername(actor.username());entry.setActorKind(actor.kind());
                    entry.setState(UpdateCleanup.State.WAITING);entries.save(entry);pending.add(entry);
                }
                entries.flush();return pending;
                });
                var outcomes=queue.execute(requests,adapter,remote,retryUnconfirmed,false);
                var byId=new HashMap<String,CleanupQueue.Result>();outcomes.forEach(r -> byId.put(r.downloadId(),r));
                return new Result(downloads.findAllById(selection.ids()).stream().map(r -> {
                    var outcome=byId.get(r.getId());
                    return new Item(r.getId(),r.getStatus(),outcome.state().name(),outcome.message());
                }).toList());
            }),r -> Map.of("checked",r.items().size(),"removed",r.items().stream().filter(i -> "REMOVED".equals(i.cleanupState())).count()));
    }
    private Result result(List<String> ids) {
        return new Result(downloads.findAllById(ids).stream().map(r -> new Item(r.getId(),r.getStatus(),null,null)).toList());
    }
}
