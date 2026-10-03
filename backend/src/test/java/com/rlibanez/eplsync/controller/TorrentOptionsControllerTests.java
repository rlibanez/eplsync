package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TorrentOptionsControllerTests {
    @Test void exposesEffectiveDefaultsWithoutCredentialsOrConnectionDetails() throws Exception {
        var torrent = new TorrentProperties(); var qb = new QBittorrentProperties();
        torrent.getDownload().setStart(false); torrent.getDownload().setSavePath("/books");
        torrent.getRename().setPattern("{title} [{eplId}]");
        qb.getAuth().setPassword("secret"); qb.getAuth().setApiKey("secret-key");
        qb.getDownload().setAutoManagement(false); qb.getDownload().setCategory("");
        qb.getDownload().setTags(java.util.List.of("{language}"));
        MockMvcBuilders.standaloneSetup(new TorrentOptionsController(torrent,qb)).build()
            .perform(get("/api/torrent/options")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.start").value(false)).andExpect(jsonPath("$.savePath").value("/books"))
            .andExpect(jsonPath("$.autoManagement").value(false)).andExpect(jsonPath("$.category").value(""))
            .andExpect(jsonPath("$.tags[0]").value("{language}"))
            .andExpect(jsonPath("$.rename.pattern").value("{title} [{eplId}]"))
            .andExpect(jsonPath("$.interval").value("500ms"))
            .andExpect(jsonPath("$.auth").doesNotExist()).andExpect(jsonPath("$.baseUrl").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
}
