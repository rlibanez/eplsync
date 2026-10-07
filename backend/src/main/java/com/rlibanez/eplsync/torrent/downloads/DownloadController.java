package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('DOWNLOADS_READ')")
@RestController
@RequestMapping("/api/torrent/downloads")
public class DownloadController {
    private final TorrentClientService client;
    private final com.rlibanez.eplsync.torrent.updates.HistoryActions actions;
    private final DownloadQueryService queries;
    private final com.rlibanez.eplsync.torrent.updates.UpdateCleanupService cleanup;
    public DownloadController(TorrentClientService client, DownloadQueryService queries, com.rlibanez.eplsync.torrent.updates.UpdateCleanupService cleanup, com.rlibanez.eplsync.torrent.updates.HistoryActions actions) {
        this.client = client; this.queries = queries; this.cleanup = cleanup; this.actions=actions;
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SYNC')")
    @PostMapping("/sync")
    public DownloadTrackingService.SyncResult sync(@RequestParam MultiValueMap<String, String> params,
            @RequestBody java.util.Map<String, Object> body) {
        if (!params.isEmpty()) throw new com.rlibanez.eplsync.exception.UserInputException("Las opciones de sincronización deben ir en el cuerpo JSON");
        if (body.keySet().stream().anyMatch(key -> !java.util.Set.of("dryRun", "includeDetails").contains(key)))
            throw new com.rlibanez.eplsync.exception.UserInputException("Opción de sincronización desconocida");
        if (!(body.get("dryRun") instanceof Boolean dryRun))
            throw new com.rlibanez.eplsync.exception.UserInputException("dryRun es obligatorio y debe ser booleano");
        if (body.containsKey("includeDetails") && !(body.get("includeDetails") instanceof Boolean))
            throw new com.rlibanez.eplsync.exception.UserInputException("includeDetails debe ser booleano");
        return cleanup.synchronize(dryRun, Boolean.TRUE.equals(body.get("includeDetails")));
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SYNC')")
    @PostMapping("/link")
    public DownloadRecord link(@RequestBody DownloadTrackingService.LinkRequest request) {
        return client.linkDownload(request);
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('BOOK_HISTORY_READ') and hasAuthority('TORRENT_SYNC')")
    @PostMapping("/refresh-selected")
    public com.rlibanez.eplsync.torrent.updates.HistoryActions.Result refreshSelected(
            @RequestBody com.rlibanez.eplsync.torrent.updates.HistoryActions.Selection selection) {
        return actions.refresh(selection);
    }
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('BOOK_HISTORY_READ') and hasAuthority('TORRENT_CLEANUP')")
    @PostMapping("/remove-selected")
    public com.rlibanez.eplsync.torrent.updates.HistoryActions.Result removeSelected(
            @RequestParam(defaultValue="false") boolean retryUnconfirmed,
            @RequestBody com.rlibanez.eplsync.torrent.updates.HistoryActions.Selection selection) {
        return actions.remove(selection,retryUnconfirmed);
    }
    @GetMapping("/summary")
    public DownloadQueryService.Summary summary(@RequestParam MultiValueMap<String, String> params) {
        return queries.summary(params);
    }
    @GetMapping
    public PageResponse<DownloadRecord> search(@RequestParam MultiValueMap<String, String> params) {
        return queries.search(params);
    }
}
