package com.rlibanez.eplsync.settings;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final ServerSettings settings;
    public SettingsController(ServerSettings settings) { this.settings = settings; }
    @GetMapping("/{section}") public ResponseEntity<ServerSettings.View> view(@PathVariable String section) { return response(settings.view(section)); }
    @PutMapping("/{section}") public ResponseEntity<ServerSettings.View> save(@PathVariable String section, @RequestBody Map<String,Object> values) { return response(settings.save(section, values)); }
    @DeleteMapping("/{section}") public ResponseEntity<ServerSettings.View> restore(@PathVariable String section) { return response(settings.restore(section)); }
    private ResponseEntity<ServerSettings.View> response(ServerSettings.View view) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view); }
}
