package com.rlibanez.eplsync.updates;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import com.rlibanez.eplsync.security.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class ApplicationUpdateApiTests {
    @Autowired WebApplicationContext context;
    @Autowired AccountStore accounts;
    @Autowired LoginThrottle throttle;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean GitHubReleaseClient releases;
    MockMvc mvc;
    static final String PASSWORD="permanent password for tests";
    @BeforeEach void initialize() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ((java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(throttle,"attempts")).clear();
        accounts.clear();accounts.initialize("admin","admin@example.org",PASSWORD,PASSWORD);
        var user=accounts.create("reader","reader@example.org","USER",null);
        accounts.changePassword(accounts.find(user.user().id()),user.password(),PASSWORD);
        jdbc.update("DELETE FROM app_settings WHERE setting_key='application.updates.automatic'");
    }
    MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"username\":\""+username+"\",\"password\":\""+PASSWORD+"\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void versionRequiresLoginAndUpdateAdministrationRequiresAdmin() throws Exception {
        mvc.perform(get("/api/application/version")).andExpect(status().isUnauthorized());
        var user=login("reader");
        mvc.perform(get("/api/application/version").session(user)).andExpect(status().isOk()).andExpect(jsonPath("$.version").exists());
        mvc.perform(get("/api/application/updates").session(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/application/updates/check").session(user).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/application/updates/settings").session(user).with(csrf()).contentType("application/json").content("{\"automatic\":false}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(releases);
    }
    @Test void adminCanPersistPolicyButMutationsRequireCsrf() throws Exception {
        var admin=login("admin");
        mvc.perform(put("/api/application/updates/settings").session(admin).contentType("application/json").content("{\"automatic\":false}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/application/updates/settings").session(admin).with(csrf()).contentType("application/json").content("{\"automatic\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.automatic").value(false));
        mvc.perform(get("/api/application/updates").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.automatic").value(false));
        verifyNoInteractions(releases);
    }
}
