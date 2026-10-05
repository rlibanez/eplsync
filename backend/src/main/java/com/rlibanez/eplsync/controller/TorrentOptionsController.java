package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

/** Explicit public projection: never expose connection credentials. */
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SEND')")
@RestController
@RequestMapping("/api/torrent/options")
public class TorrentOptionsController {
    private final TorrentProperties torrent;
    private final QBittorrentProperties qbittorrent;
    public TorrentOptionsController(TorrentProperties torrent, QBittorrentProperties qbittorrent) {
        this.torrent = torrent; this.qbittorrent = qbittorrent;
    }
    public record Rename(boolean enabled, String pattern) {}
    public record Defaults(boolean start, String savePath, boolean autoManagement, Rename rename,
            String category, java.util.List<String> tags, int concurrency, int batchSize,
            String interval, String multipleHashes) {}
    @GetMapping
    public ResponseEntity<Defaults> defaults() {
        var download = torrent.getDownload(); var qb = qbittorrent.getDownload(); var bulk = torrent.getBulk();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Defaults(
            download.isStart(), download.getSavePath(), qb.isAutoManagement(),
            new Rename(torrent.getRename().isEnabled(), torrent.getRename().getPattern()),
            qb.getCategory(), qb.getTags(), bulk.getConcurrency(), bulk.getBatchSize(),
            bulk.getInterval().toMillis() + "ms", bulk.getMultipleHashes().name().toLowerCase(java.util.Locale.ROOT)));
    }
}
