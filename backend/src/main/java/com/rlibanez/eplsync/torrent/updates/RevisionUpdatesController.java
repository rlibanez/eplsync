package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.security.UpdatePreferences;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/torrent/revision-updates")
@PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('TORRENT_SYNC')")
public class RevisionUpdatesController {
    private final RevisionUpdates updates;
    private final TorrentClientService client;
    private final SelectedUpdateSender sender;
    private final com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    public RevisionUpdatesController(RevisionUpdates updates,TorrentClientService client,SelectedUpdateSender sender,com.rlibanez.eplsync.torrent.bulk.BulkStore bulk) {this.updates=updates;this.client=client;this.sender=sender;this.bulk=bulk;}
    @PostMapping("/search")
    public PageResponse<RevisionUpdates.Row> search(@RequestBody UpdatePreferences.Preferences input,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="title,asc") String sort,
        @RequestParam(required=false) com.rlibanez.eplsync.torrent.downloads.DownloadStatus status,
        @RequestParam(defaultValue="false") boolean synchronize, jakarta.servlet.http.HttpServletRequest request) {
        if(!java.util.Set.of("page","size","sort","synchronize","status").containsAll(request.getParameterMap().keySet()))
            throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro de búsqueda desconocido");
        com.rlibanez.eplsync.config.TableOrdering.validateParameters(request);
        sort = com.rlibanez.eplsync.config.TableOrdering.request(request, "title,asc");
        RevisionUpdates.validate(input,page,size,sort);
        if(synchronize) {
            com.rlibanez.eplsync.security.Permission.require(com.rlibanez.eplsync.security.Permission.TORRENT_SYNC);
            client.syncDownloads(false,false);
        }
        return updates.search(input,page,size,sort,null,status);
    }
    @PostMapping("/send")
    @PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('TORRENT_SYNC') and hasAuthority('TORRENT_SEND')")
    public org.springframework.http.ResponseEntity<com.rlibanez.eplsync.torrent.bulk.BulkStore.View> send(@RequestBody SelectedUpdateSender.Request input) {
        synchronized(bulk) {
            var job=sender.create(input);
            return org.springframework.http.ResponseEntity.accepted().location(java.net.URI.create("/api/torrent/jobs/"+job.jobId())).body(job);
        }
    }

}
