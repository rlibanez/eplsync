package com.rlibanez.eplsync.torrent.bulk;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/torrent/preparations")
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SEND')")
public class TorrentPreparationController {
    private final TorrentPreparationProgress progress;
    public TorrentPreparationController(TorrentPreparationProgress progress) {this.progress=progress;}
    @PostMapping("/{id}/cancel") public TorrentPreparationProgress.Progress cancel(@PathVariable String id) {return progress.cancel(id);}
    @GetMapping("/{id}") public org.springframework.http.ResponseEntity<TorrentPreparationProgress.Progress> status(@PathVariable String id) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(progress.status(id));
    }
}
