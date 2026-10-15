package com.rlibanez.eplsync.security;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/auth/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationPreferencesController {
    private final NotificationPreferences preferences;
    private final SessionAccess sessions;
    public NotificationPreferencesController(NotificationPreferences preferences,SessionAccess sessions) {
        this.preferences=preferences;this.sessions=sessions;
    }
    @GetMapping public NotificationPreferences.Preferences get() { return preferences.get(sessions.current().id()); }
    @PutMapping public NotificationPreferences.Preferences save(@RequestBody NotificationPreferences.Preferences input) {
        return preferences.save(sessions.current().id(),input);
    }
}
