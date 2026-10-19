package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CatalogSavedSearches;
import com.rlibanez.eplsync.security.SessionAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog/saved-searches")
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ')")
public class CatalogSavedSearchController {
    private final CatalogSavedSearches searches;
    private final SessionAccess sessions;
    public CatalogSavedSearchController(CatalogSavedSearches searches,SessionAccess sessions) {this.searches=searches;this.sessions=sessions;}
    @GetMapping public java.util.List<CatalogSavedSearches.Search> list() {return searches.list(sessions.current().id());}
    @PostMapping public CatalogSavedSearches.Search create(@RequestBody CatalogSavedSearches.Input input) {return searches.save(sessions.current().id(),null,input);}
    @PutMapping("/{id}") public CatalogSavedSearches.Search update(@PathVariable String id,@RequestBody CatalogSavedSearches.Input input) {return searches.save(sessions.current().id(),id,input);}
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) {searches.delete(sessions.current().id(),id);}
}
