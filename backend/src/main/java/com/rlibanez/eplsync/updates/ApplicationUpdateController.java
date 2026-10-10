package com.rlibanez.eplsync.updates;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/application")
@PreAuthorize("hasRole('ADMIN')")
public class ApplicationUpdateController {
    private final ApplicationVersion version;
    private final ApplicationUpdates updates;
    private final ApplicationUpdateSettings settings;
    public ApplicationUpdateController(ApplicationVersion version,ApplicationUpdates updates,ApplicationUpdateSettings settings) {
        this.version=version;this.updates=updates;this.settings=settings;
    }
    @GetMapping("/version") @PreAuthorize("isAuthenticated()") public ApplicationVersion.Info version() {return version.get();}
    @GetMapping("/updates") public ApplicationUpdates.Status updates() {return updates.status();}
    @PostMapping("/updates/check") public ApplicationUpdates.Status check() {return updates.check(false);}
    @PutMapping("/updates/settings") public ApplicationUpdates.Status settings(@RequestBody ApplicationUpdateSettings.Settings input) {
        settings.save(input);return updates.status();
    }
}
