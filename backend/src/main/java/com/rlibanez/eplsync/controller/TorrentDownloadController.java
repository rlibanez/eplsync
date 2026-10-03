package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/torrent/books")
public class TorrentDownloadController {
    private final TorrentDownloadService service;
    private final BulkStore jobs;
    private final com.rlibanez.eplsync.events.EventJournal events;
    public TorrentDownloadController(TorrentDownloadService service, BulkStore jobs, com.rlibanez.eplsync.events.EventJournal events) {
        this.service = service; this.jobs = jobs; this.events = events;
    }
    public record Request(boolean dryRun, TorrentDownloadRequest options, Integer batchSize, Integer concurrency, String interval) {}
    @PostMapping("/{eplId}")
    public ResponseEntity<?> downloadByEplId(@PathVariable Long eplId,
            @RequestBody java.util.Map<String,Object> body, jakarta.servlet.http.HttpServletRequest request) {
        var input = com.rlibanez.eplsync.api.OperationBody.read(body, Request.class, request);
        var startedAt = java.time.Instant.now();
        com.rlibanez.eplsync.torrent.TorrentDownload command;
        try {
            command = service.prepareById(eplId, input.options());
        } catch (RuntimeException ex) {
            if (!input.dryRun()) events.rejected(com.rlibanez.eplsync.events.EventJournal.Category.TORRENT,
                    "SEND_BOOK", java.util.Map.of("eplId", eplId), startedAt, ex);
            throw ex;
        }
        if (input.dryRun()) return ResponseEntity.ok(new BulkStore.Preview(true,false,1,1,0,java.util.List.of()));
        // Preparation resolves the selected hash and freezes the server defaults for this job.
        var options = new BulkRequest(null,input.batchSize(),input.concurrency(),input.interval());
        synchronized (jobs) {
            var job = jobs.createPrepared(java.util.List.of(command), options);
            return ResponseEntity.accepted().location(java.net.URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }
}
