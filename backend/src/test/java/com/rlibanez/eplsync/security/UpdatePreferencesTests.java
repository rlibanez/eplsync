package com.rlibanez.eplsync.security;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class UpdatePreferencesTests {
    @Autowired AccountStore accounts;
    @Autowired UpdatePreferences preferences;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired LoginThrottle throttle;
    MockMvc mvc;
    static final String PASSWORD="permanent password for testing";
    String adminId,userId;
    tools.jackson.databind.json.JsonMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    @BeforeEach void setup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        accounts.clear();
        jdbc.update("DELETE FROM revision_update_settings");
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(throttle,"attempts")).clear();
        adminId=accounts.initialize("administrator","admin@example.org",PASSWORD,PASSWORD).id();
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        accounts.register("reader","reader@example.org",PASSWORD);
        userId=accounts.users().stream().filter(user -> user.username().equals("reader")).findFirst().orElseThrow().id();
        accounts.update(userId,"USER","ACTIVE",Map.of("TORRENT_SYNC","ALLOW"),adminId);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    MockHttpSession login(String username) throws Exception {
        var response=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("username",username,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)response.getRequest().getSession(false);
    }
    @Test void persistsSharedSettingsAndRejectsInvalidValues() throws Exception {
        assertThat(preferences.get().states()).containsExactly(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.values());
        var session=login("administrator");
        mvc.perform(put("/api/settings/downloads/updates").session(session).with(csrf()).contentType("application/json")
            .content("{\"states\":[\"NOT_FOUND\"],\"userId\":\""+userId+"\"}"))
            .andExpect(status().isOk());
        assertThat(preferences.get().states()).containsExactly(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.NOT_FOUND);
        mvc.perform(get("/api/settings/downloads/updates").session(login("reader")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.states[0]").value("NOT_FOUND"));
        mvc.perform(put("/api/settings/downloads/updates").session(login("reader")).with(csrf()).contentType("application/json")
            .content("{\"states\":[\"ERROR\"]}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/downloads/updates").session(login("administrator"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.states[0]").value("NOT_FOUND"));
        for(var body:List.of("{\"states\":[]}","{\"states\":[null]}","{\"states\":[\"ERROR\",\"ERROR\"]}","{\"states\":[\"INVALID\"]}"))
            mvc.perform(put("/api/settings/downloads/updates").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/downloads/updates").session(session).contentType("application/json").content("{\"states\":[\"ERROR\"]}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/downloads/updates")).andExpect(status().isUnauthorized());
    }
}
