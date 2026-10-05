package com.rlibanez.eplsync.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive configuration read at runtime by the frontend. */
@org.springframework.security.access.prepost.PreAuthorize("permitAll()")
@RestController
public class UiConfigController {
    private final String language;

    public UiConfigController(@Value("${eplsync.ui.language:auto}") String language) {
        this.language = language;
    }

    public record UiConfig(String defaultLanguage) {}

    @GetMapping("/api/ui/config")
    public ResponseEntity<UiConfig> config() {
        // The frontend owns the registry of available translations.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new UiConfig(language));
    }
}
