package com.rlibanez.eplsync.security;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/settings/downloads/updates")
@PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('DOWNLOADS_READ')")
public class UpdatePreferencesController {
    private final UpdatePreferences preferences;
    public UpdatePreferencesController(UpdatePreferences preferences) {
        this.preferences=preferences;
    }
    @GetMapping public UpdatePreferences.Preferences get() {return preferences.get();}
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    @PutMapping public UpdatePreferences.Preferences save(@RequestBody UpdatePreferences.Preferences input) {
        return preferences.save(input);
    }
}
