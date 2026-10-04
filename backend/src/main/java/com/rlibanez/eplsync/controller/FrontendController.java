package com.rlibanez.eplsync.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Only known UI routes fall back to the SPA; API and missing assets retain their status codes. */
@Controller
public class FrontendController {
    @GetMapping({"/", "/events", "/catalog", "/catalog/{id:[0-9]+}", "/maintenance/catalog", "/settings", "/settings/general", "/settings/database", "/settings/events", "/settings/torrent", "/settings/covers", "/settings/about", "/directory", "/downloads", "/downloads/jobs", "/downloads/jobs/{id:[a-zA-Z0-9-]+}"})
    public String frontend() {
        return "forward:/index.html";
    }
}
