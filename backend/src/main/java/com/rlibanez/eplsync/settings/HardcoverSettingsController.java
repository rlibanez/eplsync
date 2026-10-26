package com.rlibanez.eplsync.settings;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/settings/hardcover")
@PreAuthorize("hasRole('ADMIN')")
public class HardcoverSettingsController {
    private final HardcoverSettings settings;
    private final HardcoverClient client;
    public HardcoverSettingsController(HardcoverSettings settings,HardcoverClient client) {this.settings=settings;this.client=client;}
    public record Check(boolean connected,HardcoverUsage.Snapshot usage) {}
    public record Configuration(boolean apiKeyConfigured,boolean environmentConfigured,boolean overridden,HardcoverUsage.Snapshot usage) {}
    @GetMapping public ResponseEntity<Configuration> view() {return response(settings.view());}
    @PutMapping public ResponseEntity<Configuration> save(@RequestBody HardcoverSettings.Input input) {return response(settings.save(input));}
    @DeleteMapping public ResponseEntity<Configuration> restore() {return response(settings.restore());}
    @PostMapping("/connection") public ResponseEntity<Check> check(@RequestBody HardcoverSettings.Input input) {
        String key=settings.resolve(input);client.checkKey(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Check(true,client.usageFor(key)));
    }
    private ResponseEntity<Configuration> response(HardcoverSettings.View value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Configuration(value.apiKeyConfigured(),value.environmentConfigured(),value.overridden(),client.usage()));}
}
