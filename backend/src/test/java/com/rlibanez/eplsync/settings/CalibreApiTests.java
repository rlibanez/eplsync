package com.rlibanez.eplsync.settings;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.security.require-https=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false","eplsync.security.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CalibreApiTests {
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean CalibreClient client;
    MockMvc mvc;
    @BeforeEach void initialize() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM app_settings WHERE setting_key='calibre.connection'");
    }
    @Test @WithMockUser(authorities={"CATALOG_READ"}) void restrictsSettingsChecksAndBooks() throws Exception {
        mvc.perform(get("/api/settings/calibre")).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/calibre/books")).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/calibre/connection").with(csrf()).contentType("application/json")
            .content("{\"url\":\"http://localhost:8080\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/calibre/snapshot")).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/calibre/sync").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(client);
    }
    @Test @WithMockUser(authorities={"SETTINGS_MANAGE", "ROLE_ADMIN"}) void masksSavedCredentialsAndRequiresCsrf() throws Exception {
        when(client.check(any())).thenReturn(new CalibreClient.Check(true, java.util.List.of(new CalibreClient.Library("main","Library")), "main"));
        String input="{\"url\":\"http://localhost:8080\",\"username\":\"reader\",\"password\":\"private-password\",\"library\":\"main\"}";
        mvc.perform(put("/api/settings/calibre").contentType("application/json").content(input)).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/calibre/connection").with(csrf()).contentType("application/json").content(input))
            .andExpect(status().isOk()).andExpect(jsonPath("$.defaultLibrary").value("main"));
        mvc.perform(put("/api/settings/calibre").with(csrf()).contentType("application/json").content(input))
            .andExpect(status().isOk()).andExpect(jsonPath("$.passwordConfigured").value(true)).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/settings/calibre")).andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist());
    }
}
