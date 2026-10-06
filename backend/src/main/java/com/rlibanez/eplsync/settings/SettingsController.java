package com.rlibanez.eplsync.settings;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final ServerSettings settings;
    public SettingsController(ServerSettings settings) { this.settings = settings; }
    @GetMapping("/{section}") public ResponseEntity<ServerSettings.View> view(@PathVariable String section) { return response(settings.view(section)); }
    @PutMapping("/{section}") public ResponseEntity<ServerSettings.View> save(@PathVariable String section, @RequestBody Map<String,Object> values) { requireConnectionAdmin(section, values.keySet()); return response(settings.save(section, values)); }
    @DeleteMapping("/{section}") public ResponseEntity<ServerSettings.View> restore(@PathVariable String section) { requireConnectionAdmin(section, java.util.Set.of("torrent.base-url")); return response(settings.restore(section)); }
    @PostMapping("/torrent/connection")
    public ResponseEntity<com.rlibanez.eplsync.dto.TorrentConnectionStatus> checkTorrent(
            @RequestBody Map<String,Object> values) {
        requireConnectionAdmin("torrent", values.keySet());
        var candidate = settings.previewTorrent(values);
        try (var client = new com.rlibanez.eplsync.qbittorrent.QBittorrentClient(candidate.torrent(), candidate.qbittorrent())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.checkConnection());
        }
    }
    private void requireConnectionAdmin(String section, java.util.Set<String> keys) {
        if (!section.equals("torrent") || keys.stream().noneMatch(key -> key.equals("torrent.base-url") || key.startsWith("torrent.qbittorrent.auth."))) return;
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")))
            throw new org.springframework.security.access.AccessDeniedException("Solo ADMIN puede modificar el destino o la autenticación de qBittorrent");
    }
    private ResponseEntity<ServerSettings.View> response(ServerSettings.View view) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view); }
}
