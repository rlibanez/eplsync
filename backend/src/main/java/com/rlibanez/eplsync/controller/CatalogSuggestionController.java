package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CatalogSuggestionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ')")
@RestController
@RequestMapping("/api/catalog/suggestions")
public class CatalogSuggestionController {
    private final CatalogSuggestionService suggestions;
    public CatalogSuggestionController(CatalogSuggestionService suggestions) { this.suggestions = suggestions; }

    @GetMapping("/{kind}")
    public ResponseEntity<CatalogSuggestionService.Result> suggest(@PathVariable String kind,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(suggestions.suggest(kind, q, offset));
    }
}
