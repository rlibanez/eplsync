package com.rlibanez.eplsync.security;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/auth/appearance")
@PreAuthorize("isAuthenticated()")
public class AppearancePreferencesController {
    private final AppearancePreferences preferences;
    private final SessionAccess sessions;
    public AppearancePreferencesController(AppearancePreferences preferences,SessionAccess sessions) {
        this.preferences=preferences;this.sessions=sessions;
    }
    @GetMapping public AppearancePreferences.Preferences get() {return preferences.get(sessions.current().id());}
    @PutMapping public AppearancePreferences.Preferences save(@RequestBody AppearancePreferences.Input input) {
        return preferences.save(sessions.current().id(),input);
    }
}
