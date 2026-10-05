package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
@RestController
@RequestMapping("/api/torrent/client")
public class TorrentClientController {
    private final TorrentClientService client;

    public TorrentClientController(TorrentClientService client) { this.client = client; }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SEND')")
    @GetMapping("/categories")
    public ResponseEntity<java.util.List<String>> categories() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.listCategories());
    }

    @GetMapping("/connection")
    public ResponseEntity<TorrentConnectionStatus> connection() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.checkConnection());
    }
}
