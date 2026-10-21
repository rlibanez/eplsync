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
    public record Item(String id,DownloadStatus status,String cleanupState,String message,String blockingCalibreJobId) {
        public Item(String id,DownloadStatus status,String cleanupState,String message) {this(id,status,cleanupState,message,null);}
    }
    public record Result(List<Item> items) {}
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final java.util.concurrent.Semaphore previews=new java.util.concurrent.Semaphore(1);
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentClientService client;
    private final UpdateCleanupRepository entries;
    private final CleanupQueue queue;
    private final EventJournal events;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    public HistoryActions(DownloadRepository downloads,DownloadTrackingService tracking,TorrentClientService client,
            UpdateCleanupRepository entries,CleanupQueue queue,EventJournal events,org.springframework.transaction.PlatformTransactionManager manager,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.downloads=downloads;this.tracking=tracking;this.client=client;this.entries=entries;this.queue=queue;this.events=events;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    private void requireSelection(Selection selection) {
        if (selection==null || (selection.eplId()!=null && selection.eplId()<1) || selection.ids()==null
                || selection.ids().isEmpty()
                || selection.ids().stream().anyMatch(id -> id==null || id.isBlank() || id.length()>64)
                || new HashSet<>(selection.ids()).size()!=selection.ids().size())
            throw new UserInputException("Selecciona al menos un registro, sin duplicados y con EPL ID válido si se especifica");
    }
    private Map<String,Object> eventScope(Selection selection) {
        var scope=new LinkedHashMap<String,Object>();
        if(selection.eplId()!=null) scope.put("eplId",selection.eplId());
        scope.put("selected",selection.ids().size());return scope;
    }
    private void requireReadAccess() {
        if(!Permission.has(Permission.BOOK_HISTORY_READ)) Permission.require(Permission.TORRENT_SYNC);
    }
    private List<DownloadRecord> validate(Selection selection) { return validate(selection,true); }
    private List<DownloadRecord> validate(Selection selection,boolean requireCurrentClient) {
        requireSelection(selection);
        var rows=new ArrayList<DownloadRecord>();
        for(int offset=0;offset<selection.ids().size();offset+=500)
            rows.addAll(downloads.findAllById(selection.ids().subList(offset,Math.min(offset+500,selection.ids().size()))));
        if (rows.size()!=selection.ids().size() || (selection.eplId()!=null && rows.stream().anyMatch(r -> !r.getEplId().equals(selection.eplId()))))
            throw new UserInputException("Los registros seleccionados no pertenecen al libro");
        if (requireCurrentClient && rows.stream().anyMatch(r -> !r.getClientInstanceId().equals(tracking.instanceId())))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"Los registros pertenecen a otro cliente; restaura su destino antes de actuar");
        return rows;
    }
    public record PreviewRecord(String id,Long eplId,Double revision,String hash,String title) {}
    public List<PreviewRecord> previewRecords(Selection selection) {
        requireReadAccess();
        if(!Permission.has(Permission.DOWNLOADS_DELETE)) Permission.require(Permission.TORRENT_CLEANUP);
        requireSelection(selection);
        if(!previews.tryAcquire()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Hay una previsualización de borrado en curso; espera a que termine");
        try {
            var result=new ArrayList<PreviewRecord>(selection.ids().size());
            // Materialize only display fields, releasing the connection after each bounded query.
            // Never hydrate tens of thousands of JPA entities for this wizard.
            for(int offset=0;offset<selection.ids().size();offset+=500) {
                var ids=selection.ids().subList(offset,Math.min(offset+500,selection.ids().size()));
                var rows=jdbc.query("SELECT d.id,d.epl_id,d.revision,d.hash,b.title FROM torrent_downloads d LEFT JOIN catalog_books b ON b.epl_id=d.epl_id WHERE d.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",
                    (r,n)->new PreviewRecord(r.getString("id"),r.getLong("epl_id"),r.getDouble("revision"),r.getString("hash"),r.getString("title")),ids.toArray());
                if(rows.size()!=ids.size() || selection.eplId()!=null && rows.stream().anyMatch(row->!selection.eplId().equals(row.eplId())))
                    throw new UserInputException("Los registros seleccionados no pertenecen al libro");
                result.addAll(rows);
            }
            return result;
        } finally {previews.release();}
    }
    public record HistoryDeletion(Long eplId,List<String> ids,Boolean confirm) {}
    public record DeletionResult(int deleted,long pendingCleanup) {}
    public DeletionResult deleteHistory(HistoryDeletion request,boolean preview) {return deleteHistory(request,preview,true);}
    public DeletionResult deleteRevisionHistory(Long eplId,String id) {return deleteHistory(new HistoryDeletion(eplId,List.of(id),true),false,false);}
    private DeletionResult deleteHistory(HistoryDeletion request,boolean preview,boolean audited) {
        requireReadAccess();
        Permission.require(Permission.DOWNLOADS_DELETE);
        if(request==null) throw new UserInputException("Selecciona registros del historial");
        if(!preview && !Boolean.TRUE.equals(request.confirm())) throw new UserInputException("Confirma la eliminación irreversible del historial");
        var selection=new Selection(request.eplId(),request.ids(),null,null);
        java.util.function.Supplier<DeletionResult> operation=() -> tx.execute(status -> {
            var rows=validate(selection,false);
            long pending=0;
            for(int offset=0;offset<request.ids().size();offset+=500)
                pending+=entries.countByDownloadIdInAndStateIn(request.ids().subList(offset,Math.min(offset+500,request.ids().size())),CleanupQueue.PENDING);
            if(!preview) { downloads.deleteAll(rows);downloads.flush(); }
            return new DeletionResult(preview ? 0 : rows.size(),pending);
        });
        if(preview || !audited) return operation.get();
        return events.run(EventJournal.Category.TORRENT,"DELETE_DOWNLOAD_HISTORY",eventScope(selection),
            operation,r -> Map.of("historyDeleted",r.deleted(),"pendingCleanup",r.pendingCleanup()));
    }
    public record Removal(Long eplId,List<String> ids,Boolean deleteHistory,String clientAction,Boolean confirm,Boolean confirmFiles) {}
    public record RemovalItem(String id,DownloadStatus status,String cleanupState,String message,boolean historyDeleted,String blockingCalibreJobId) {}
    public record RemovalResult(List<RemovalItem> items) {}
    public RemovalResult removeRecords(Removal request) {
        requireReadAccess();
        if(request==null || !Set.of("keep","torrent","files").contains(request.clientAction()==null ? "" : request.clientAction()))
            throw new UserInputException("Acción de eliminación inválida");
        boolean history=Boolean.TRUE.equals(request.deleteHistory());
        boolean clientRemoval=!request.clientAction().equals("keep");
        boolean files=request.clientAction().equals("files");
        if(!history && !clientRemoval) throw new UserInputException("Selecciona qué deseas eliminar");
        if(history) Permission.require(Permission.DOWNLOADS_DELETE);
        if(clientRemoval) Permission.require(Permission.TORRENT_CLEANUP);
        if(files) {
            Permission.require(Permission.TORRENT_FILES_DELETE);
            if(!Boolean.TRUE.equals(request.confirmFiles())) throw new UserInputException("Confirma la eliminación irreversible de los archivos");
        }
        if(!Boolean.TRUE.equals(request.confirm())) throw new UserInputException("Confirma la eliminación");
        var selection=new Selection(request.eplId(),request.ids(),files,request.confirmFiles());
        var rows=validate(selection,clientRemoval);
        var outcomes=clientRemoval ? remove(selection).items() : rows.stream().map(r -> new Item(r.getId(),r.getStatus(),null,null)).toList();
        var deleted=new HashSet<String>();
        if(history) {
            var confirmed=outcomes.stream().filter(r -> !clientRemoval || "REMOVED".equals(r.cleanupState())).map((Item entryValue) -> java.util.Objects.requireNonNull(entryValue).id()).toList();
            if(!confirmed.isEmpty()) {
                var remaining=confirmed.stream().filter(downloads::existsById).toList();
                if(!remaining.isEmpty()) deleteHistory(new HistoryDeletion(request.eplId(),remaining,true),false);
                deleted.addAll(confirmed);
            }
        }
        return new RemovalResult(outcomes.stream().map(r -> new RemovalItem(r.id(),r.status(),r.cleanupState(),r.message(),deleted.contains(r.id()),r.blockingCalibreJobId())).toList());
    }
    public Result refresh(Selection selection) {return refresh(selection,true);}
    public Result refreshRevision(Selection selection) {return refresh(selection,false);}
    private Result refresh(Selection selection,boolean audited) {
        Permission.require(Permission.TORRENT_SYNC);
        requireSelection(selection);
        java.util.function.Supplier<Result> operation=() ->
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
            });
        return audited?events.run(EventJournal.Category.TORRENT,"REFRESH_DOWNLOADS",eventScope(selection),operation,r -> Map.of("checked",r.items().size())):operation.get();
    }
    /** Caller holds the client coordination lock and supplies a fresh, complete safety snapshot. */
    public void confirmRevisionAbsences(Selection selection,List<RemoteTorrent> remote) {
        Permission.require(Permission.TORRENT_SYNC);
        var rows=validate(selection);var byHash=TorrentRemovalSafety.index(remote);var hashes=new HashSet<String>();
        for(var row:rows) {
            if(byHash.containsKey(row.getHash())) throw new UserInputException("El torrent sigue presente; no se ha repetido el borrado");
            hashes.add(row.getHash());
        }
        tracking.observeSelected(byHash,hashes);
        for(var hash:hashes) if(!entries.findByClientInstanceIdAndHashInAndStateIn(tracking.instanceId(),Set.of(hash),CleanupQueue.PENDING).isEmpty()) queue.resolve(Set.of(hash));
    }
    public Result remove(Selection selection) { return remove(selection,false); }
    public Result remove(Selection selection,boolean retryUnconfirmed) {return remove(selection,retryUnconfirmed,null,remote->{},true);}
    /** Manual cleanup jobs own their single summary; use the existing durable removal and path guards. */
    public Result removeRevision(Selection selection,String jobId,java.util.function.Consumer<List<com.rlibanez.eplsync.torrent.downloads.RemoteTorrent>> guard) {
        if(jobId==null || !jobId.matches("[a-f0-9-]{36}")) throw new UserInputException("Trabajo de limpieza inválido");
        return CleanupBudget.run(()->remove(selection,false,"revision:"+jobId,guard,false));
    }
    public Result removeRevision(Selection selection,String jobId,boolean explicitRetry,java.util.function.Consumer<List<com.rlibanez.eplsync.torrent.downloads.RemoteTorrent>> guard) {
        if(jobId==null || !jobId.matches("[a-f0-9-]{36}")) throw new UserInputException("Trabajo de limpieza inválido");
        return CleanupBudget.run(()->remove(selection,explicitRetry,"revision:"+jobId,guard,false));
    }
    private Result remove(Selection selection,boolean retryUnconfirmed,String provenance,java.util.function.Consumer<List<com.rlibanez.eplsync.torrent.downloads.RemoteTorrent>> guard,boolean audited) {
        Permission.require(Permission.TORRENT_CLEANUP);
        requireSelection(selection);
        java.util.Objects.requireNonNull(selection);
        boolean files=Boolean.TRUE.equals(selection.deleteFiles());
        if (files) {
            Permission.require(Permission.TORRENT_FILES_DELETE);
            if (!Boolean.TRUE.equals(selection.confirmFiles())) throw new UserInputException("Confirma la eliminación irreversible de los archivos");
        }
        var scope=new LinkedHashMap<String,Object>(eventScope(selection));scope.put("deleteFiles",files);
        java.util.function.Supplier<Result> operation=() -> client.exclusiveClient(adapter -> {
                var rows=validate(selection);
                // Validate the complete response before persisting any removal intent.
                var remote=adapter.listTorrents(); TorrentRemovalSafety.index(remote);
                guard.accept(remote);
                var actor=EventContext.actor();
                var requests=tx.execute(status -> {
                var pending=new ArrayList<UpdateCleanup>();
                for (var row:rows) {
                    if(provenance!=null) {
                        var existing=entries.findByJobIdAndDownloadId(provenance,row.getId());
                        if(existing.isPresent()) {
                            var entry=existing.orElseThrow();
                            if(!entry.getClientInstanceId().equals(row.getClientInstanceId()) || !entry.getHash().equals(row.getHash())
                                || entry.getPreviousVersions()!=(files?PreviousVersions.REMOVE_TORRENT_AND_FILES:PreviousVersions.REMOVE_TORRENT)) throw new UserInputException("La identidad o política del borrado cambió");
                            if(!CleanupQueue.PENDING.contains(entry.getState()) && TorrentRemovalSafety.index(remote).containsKey(row.getHash())) throw new UserInputException("La solicitud anterior ya se resolvió; prepara una nueva limpieza");
                            pending.add(entry);continue;
                        }
                    }
                    var entry=new UpdateCleanup();
                    // A provenance token also works with SQLite installations retaining the old NOT NULL job_id constraint.
                    entry.setJobId(provenance==null?"manual:"+entry.getId():provenance); entry.setDownloadId(row.getId()); entry.setEplId(row.getEplId());entry.setHash(row.getHash());
                    entry.setClientInstanceId(row.getClientInstanceId()); entry.setPreviousVersions(files ? PreviousVersions.REMOVE_TORRENT_AND_FILES : PreviousVersions.REMOVE_TORRENT);
                    entry.setTargetHashes("[]");entry.setCreatedAt(Instant.now());entry.setUpdatedAt(entry.getCreatedAt());
                    entry.setAutomatic(false);entry.setImmediate(true);entry.setActorId(actor.id());entry.setActorUsername(actor.username());entry.setActorKind(actor.kind());
                    entry.setState(UpdateCleanup.State.WAITING);entries.save(entry);pending.add(entry);
                }
                entries.flush();return pending;
                });
                var outcomes=queue.execute(requests,adapter,remote,retryUnconfirmed,false);
                var byId=new HashMap<String,CleanupQueue.Result>();outcomes.forEach(r -> byId.put(r.downloadId(),r));
                return new Result(rows.stream().map(r -> {
                    var outcome=byId.get(r.getId());
                    var current=downloads.findById(r.getId()).orElse(null);
                    return new Item(r.getId(),current==null?DownloadStatus.NOT_FOUND:current.getStatus(),outcome.state().name(),outcome.message(),outcome.state()==UpdateCleanup.State.WAITING ? blockingJob(r) : null);
                }).toList());
            });
        return audited?events.run(EventJournal.Category.TORRENT,"DELETE_DOWNLOADS",scope,operation,r -> Map.of("checked",r.items().size(),"removed",r.items().stream().filter(i -> "REMOVED".equals(i.cleanupState())).count())):operation.get();
    }
    public CleanupQueue.CalibreBlocker blockingCalibre(String hash,String instance) {return queue.blockingCalibre(hash,instance);}
    private String blockingJob(DownloadRecord row) {
        var blocker=queue.blockingCalibre(row.getHash(),row.getClientInstanceId());
        return blocker==null || !(Permission.has(Permission.CALIBRE_SEND)||Permission.has(Permission.CALIBRE_JOBS_MANAGE))?null:blocker.jobId();
    }
    private Result result(List<String> ids) {
        return new Result(downloads.findAllById(ids).stream().map(r -> new Item(r.getId(),r.getStatus(),null,null)).toList());
    }
}
