package com.rlibanez.eplsync.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendControllerTests {
    @Test
    void forwardsOnlyKnownFrontendRoutes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FrontendController(new org.springframework.core.io.ByteArrayResource(new byte[0]))).build();
        for (var path : new String[]{"/", "/events", "/catalog", "/catalog/32", "/maintenance/catalog", "/settings", "/settings/general", "/settings/home", "/settings/catalog", "/settings/database", "/settings/events", "/settings/torrent", "/settings/covers", "/settings/about", "/directory", "/downloads", "/downloads/jobs", "/downloads/jobs/abc-123"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"))
                    .andExpect(header().string("Cache-Control","no-cache"));
        }
        for (var path : new String[]{"/notifications", "/api/unknown", "/api/events", "/api/events/stream", "/events/unknown", "/assets/missing.js", "/catalog/not-an-id"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void htmlIsRevalidatedAndStaticAssetCacheHeadersArePreserved() throws Exception {
        var file = directory.resolve("index.html");
        java.nio.file.Files.writeString(file,"<!doctype html><title>EPL Sync</title>");
        var resources = new org.springframework.web.servlet.resource.ResourceHttpRequestHandler();
        java.nio.file.Files.writeString(directory.resolve("test.js"),"console.log('asset');");
        resources.setLocations(java.util.List.of(new org.springframework.core.io.FileSystemResource(directory.toString()+"/")));
        resources.setCacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)));
        resources.afterPropertiesSet();
        var mvc = MockMvcBuilders.standaloneSetup(new FrontendController(new org.springframework.core.io.FileSystemResource(file)))
            .build();
        var first = mvc.perform(get("/index.html")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(header().string("Cache-Control","no-cache"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("EPL Sync"))).andReturn();
        mvc.perform(get("/index.html").header("If-Modified-Since",first.getResponse().getHeader("Last-Modified")))
            .andExpect(status().isNotModified()).andExpect(header().string("Cache-Control","no-cache"));
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET","/assets/test.js");
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,"test.js");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        resources.handleRequest(request,response);
        org.junit.jupiter.api.Assertions.assertEquals("max-age=3600",response.getHeader("Cache-Control"));
        org.junit.jupiter.api.Assertions.assertEquals("console.log('asset');",response.getContentAsString());
    }

}
