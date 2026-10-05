package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.TorrentRenameResult;
import com.rlibanez.eplsync.service.TorrentRenameService;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SEND')")
@RestController
@RequestMapping("/api/catalog/books")
public class TorrentRenameController {
    private final TorrentRenameService service;

    public TorrentRenameController(TorrentRenameService service) { this.service = service; }

    @PostMapping("/{eplId}/torrents/{hash}/rename")
    public TorrentRenameResult rename(@PathVariable Long eplId, @PathVariable String hash) {
        return service.rename(eplId, hash);
    }
}
