package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/torrent/books")
public class TorrentDownloadController {
    private final TorrentDownloadService service;

    public TorrentDownloadController(TorrentDownloadService service) { this.service = service; }

    @PostMapping("/{eplId}")
    public ResponseEntity<TorrentDownloadResult> downloadByEplId(@PathVariable Long eplId,
            @RequestBody(required = false) TorrentDownloadRequest request) {
        var result = service.download(eplId, request);
        return ResponseEntity.status(result.status() == TorrentDownloadResult.Status.ALREADY_EXISTS ? 200 : 202)
                .body(result);
    }
}
