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
    public DownloadTrackingService.SyncResult sync(@RequestParam MultiValueMap<String, String> params,
            @RequestBody java.util.Map<String, Object> body) {
        if (!params.isEmpty()) throw new IllegalArgumentException("Las opciones de sincronización deben ir en el cuerpo JSON");
        if (body.keySet().stream().anyMatch(key -> !java.util.Set.of("dryRun", "includeDetails").contains(key)))
            throw new IllegalArgumentException("Opción de sincronización desconocida");
        if (!(body.get("dryRun") instanceof Boolean dryRun))
            throw new IllegalArgumentException("dryRun es obligatorio y debe ser booleano");
        if (body.containsKey("includeDetails") && !(body.get("includeDetails") instanceof Boolean))
            throw new IllegalArgumentException("includeDetails debe ser booleano");
        return client.syncDownloads(dryRun, Boolean.TRUE.equals(body.get("includeDetails")));
    }
    @PostMapping("/link")
    public DownloadRecord link(@RequestBody DownloadTrackingService.LinkRequest request) {
        return client.linkDownload(request);
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
