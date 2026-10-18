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
    private final com.rlibanez.eplsync.settings.CalibreSettings calibre;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public HomeController(CatalogBookRepository books, CatalogMetadataRepository metadata,
            DownloadQueryService downloads, EntityManager em,
            com.rlibanez.eplsync.settings.CalibreSettings calibre, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.books=books; this.metadata=metadata; this.downloads=downloads; this.em=em; this.calibre=calibre; this.jdbc=jdbc;
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
        if ((Permission.has(Permission.CALIBRE_READ) || Permission.has(Permission.CALIBRE_JOBS_MANAGE))
                && !calibre.view().url().isBlank()) {
            if (Permission.has(Permission.CALIBRE_READ))
                blocks.put("calibre", Map.of("total", jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Long.class)));
            if (Permission.has(Permission.CALIBRE_JOBS_MANAGE)) {
                var counts=new LinkedHashMap<String,Long>();
                jdbc.query("SELECT state,count(*) AS total FROM calibre_jobs GROUP BY state", row -> {
                    counts.put(row.getString("state"),row.getLong("total"));
                });
                blocks.put("calibreJobs",Map.of("byStatus",counts));
            }
        }
        return blocks;
    }
}
