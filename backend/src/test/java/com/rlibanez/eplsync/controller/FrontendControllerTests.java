package com.rlibanez.eplsync.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendControllerTests {
    @Test
    void forwardsOnlyKnownFrontendRoutes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FrontendController()).build();
        for (var path : new String[]{"/", "/events", "/catalog", "/catalog/32", "/maintenance/catalog", "/settings", "/settings/general", "/settings/database", "/settings/events", "/settings/torrent", "/settings/covers", "/settings/about", "/directory", "/downloads", "/downloads/jobs", "/downloads/jobs/abc-123"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
        for (var path : new String[]{"/notifications", "/api/unknown", "/api/events", "/api/events/stream", "/events/unknown", "/assets/missing.js", "/catalog/not-an-id"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
