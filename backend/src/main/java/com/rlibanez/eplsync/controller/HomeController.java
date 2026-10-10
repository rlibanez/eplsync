package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.repository.CatalogMetadataRepository;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.torrent.downloads.DownloadQueryService;
import jakarta.persistence.EntityManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import java.util.*;

/** Independent summary blocks; unauthorized blocks are omitted, including their queries. */
@RestController
@RequestMapping("/api/home")
@PreAuthorize("isAuthenticated()")
public class HomeController {
    private final CatalogBookRepository books;
    private final CatalogMetadataRepository metadata;
    private final DownloadQueryService downloads;
    private final EntityManager em;
    public HomeController(CatalogBookRepository books, CatalogMetadataRepository metadata,
            DownloadQueryService downloads, EntityManager em) {
        this.books=books; this.metadata=metadata; this.downloads=downloads; this.em=em;
    }
    @GetMapping("/summary")
    public Map<String,Object> summary(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var blocks = new LinkedHashMap<String,Object>();
        if (Permission.has(Permission.CATALOG_READ)) {
            var catalog = new LinkedHashMap<String,Object>();
            catalog.put("total", books.count());
            catalog.put("sourceModifiedAt", metadata.findById(1L).map(value -> value.getSourceModifiedAt()).orElse(null));
            blocks.put("catalog", catalog);
        }
        if (Permission.has(Permission.TORRENT_SYNC))
            blocks.put("downloads", downloads.summary(new LinkedMultiValueMap<>()));
        if (Permission.has(Permission.TORRENT_JOBS_MANAGE)) {
            var counts = new LinkedHashMap<String,Long>();
            for (var row : em.createQuery("select j.state, count(j) from BulkJob j group by j.state", Object[].class).getResultList())
                counts.put(row[0].toString(), (Long) row[1]);
            blocks.put("jobs", Map.of("byStatus", counts));
        }
        return blocks;
    }
}
