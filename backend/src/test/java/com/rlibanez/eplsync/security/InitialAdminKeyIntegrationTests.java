package com.rlibanez.eplsync.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"eplsync.security.initial-admin-key=initial-test-secret", "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "eplsync.torrent.enabled=false",
    "eplsync.torrent.bulk.worker-enabled=false"})
class InitialAdminKeyIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired AccountStore accounts;
    @Test void configuredKeyProtectsSetupAndIsNeverReturnedByStatus() throws Exception {
        accounts.clear();
        var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response=mvc.perform(get("/api/auth/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.initialAdminKeyRequired").value(true)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("initial-test-secret");
        String base="\"username\":\"admin\",\"email\":\"admin@example.org\",\"password\":\"password12345\",\"passwordConfirmation\":\"password12345\"";
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json").content("{"+base+"}"))
            .andExpect(status().isForbidden());
        assertThat(accounts.initialized()).isFalse();
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json")
            .content("{"+base+",\"initialAdminKey\":\"initial-test-secret\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.initialAdminKeyRequired").value(false));
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json").content("{"+base+"}"))
            .andExpect(status().isConflict());
    }
}
