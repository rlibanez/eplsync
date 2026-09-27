package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/torrent/client")
public class TorrentClientController {
    private final TorrentClientService client;

    public TorrentClientController(TorrentClientService client) { this.client = client; }

    @GetMapping("/connection")
    public ResponseEntity<TorrentConnectionStatus> connection() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.checkConnection());
    }
}
