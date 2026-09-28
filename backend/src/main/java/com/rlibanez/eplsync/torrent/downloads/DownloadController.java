package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/torrent/downloads")
public class DownloadController {
    private final TorrentClientService client;
    private final DownloadQueryService queries;
    public DownloadController(TorrentClientService client, DownloadQueryService queries) {
        this.client = client; this.queries = queries;
    }
    @PostMapping("/sync")
    public DownloadTrackingService.SyncResult sync(@RequestParam MultiValueMap<String, String> params) {
        if (!params.isEmpty()) throw new IllegalArgumentException("La sincronización no admite filtros");
        return client.syncDownloads();
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
