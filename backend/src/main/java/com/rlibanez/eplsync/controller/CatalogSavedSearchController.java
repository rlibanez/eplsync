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
    @GetMapping public java.util.List<CatalogSavedSearches.Search> list(@RequestParam(defaultValue="CATALOG") CatalogSavedSearches.Scope scope) {return searches.list(sessions.current().id(),scope);}
    @PostMapping public CatalogSavedSearches.Search create(@RequestBody CatalogSavedSearches.Input input,@RequestParam(defaultValue="CATALOG") CatalogSavedSearches.Scope scope) {return searches.save(sessions.current().id(),null,input,scope);}
    @PutMapping("/{id}") public CatalogSavedSearches.Search update(@PathVariable String id,@RequestBody CatalogSavedSearches.Input input,@RequestParam(defaultValue="CATALOG") CatalogSavedSearches.Scope scope) {return searches.save(sessions.current().id(),id,input,scope);}
    @DeleteMapping("/{id}") public void delete(@PathVariable String id,@RequestParam(defaultValue="CATALOG") CatalogSavedSearches.Scope scope) {searches.delete(sessions.current().id(),id,scope);}
}
