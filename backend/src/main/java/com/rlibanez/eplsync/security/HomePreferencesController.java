package com.rlibanez.eplsync.security;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/auth/home")
@PreAuthorize("isAuthenticated()")
public class HomePreferencesController {
    private final HomePreferences preferences;
    private final SessionAccess sessions;
    public HomePreferencesController(HomePreferences preferences,SessionAccess sessions) {
        this.preferences=preferences;this.sessions=sessions;
    }
    @GetMapping public HomePreferences.Preferences get() {return preferences.get(sessions.current().id());}
    @PutMapping public HomePreferences.Preferences save(@RequestBody HomePreferences.Preferences input) {
        return preferences.save(sessions.current().id(),input);
    }
}
