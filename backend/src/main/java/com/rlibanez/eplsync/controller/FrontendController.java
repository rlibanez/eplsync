package com.rlibanez.eplsync.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Only known UI routes fall back to the SPA; API and missing assets retain their status codes. */
@Controller
public class FrontendController {
    private final Resource index;

    public FrontendController(@Value("classpath:/static/index.html") Resource index) {
        this.index = index;
    }

    @GetMapping({"/", "/events", "/catalog", "/catalog/{id:[0-9]+}", "/maintenance/catalog", "/settings", "/settings/general", "/settings/database", "/settings/events", "/settings/torrent", "/settings/covers", "/settings/about", "/settings/account", "/settings/users", "/settings/reset", "/settings/missing", "/downloads/sync", "/directory", "/downloads", "/downloads/jobs", "/downloads/jobs/{id:[a-zA-Z0-9-]+}"})
    public String frontend(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        return "forward:/index.html";
    }

    /** Revalidate the HTML entry point without changing caching for versioned assets. */
    @GetMapping(value="/index.html", produces="text/html")
    public ResponseEntity<Resource> index() throws IOException {
        if (!index.exists()) return ResponseEntity.notFound().cacheControl(CacheControl.noCache()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).lastModified(index.lastModified()).body(index);
    }
}
