package com.rlibanez.eplsync.discovery;

import org.springframework.web.bind.annotation.*;
import com.rlibanez.eplsync.settings.CatalogDiscovery;
@RestController
@RequestMapping("/api/catalog/discovery")
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ')")
public class DiscoveryController {
    private final CatalogDiscovery discovery;
    public DiscoveryController(CatalogDiscovery discovery) {this.discovery=discovery;}
    @GetMapping("/values")
    public Object values(@RequestParam String source,@RequestParam(defaultValue="AUTHOR") String criterion,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size,@RequestParam(defaultValue="false") boolean ignoreAnthologies,@RequestParam(required=false) Integer maxAuthors) {
        return discovery.options(source,criterion,q,page,size,ignoreAnthologies,maxAuthors);
    }
}
