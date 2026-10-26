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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.security.require-https=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false","eplsync.security.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class HardcoverSettingsApiTests {
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean HardcoverClient client;
    MockMvc mvc;
    @BeforeEach void setup() {mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();jdbc.update("DELETE FROM app_settings WHERE setting_key=?",HardcoverSettings.KEY);}
    @Test @WithMockUser(authorities={"SETTINGS_MANAGE","CALIBRE_READ","CALIBRE_SEND"}) void settingsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/settings/hardcover")).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/hardcover").with(csrf()).contentType("application/json").content("{\"apiKey\":\"private-test-key\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/settings/hardcover").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/hardcover/connection").with(csrf()).contentType("application/json").content("{\"apiKey\":\"private-test-key\"}")).andExpect(status().isForbidden());verifyNoInteractions(client);
    }
    @Test @WithMockUser(roles="ADMIN") void secretsAreNeverReturnedAndChecksDoNotSave() throws Exception {
        mvc.perform(post("/api/settings/hardcover/connection").with(csrf()).contentType("application/json").content("{\"apiKey\":\"private-test-key\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true)).andExpect(header().string("Cache-Control","no-store"));
        verify(client).checkKey("private-test-key");assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings WHERE setting_key=?",Integer.class,HardcoverSettings.KEY)).isZero();
        var result=mvc.perform(put("/api/settings/hardcover").with(csrf()).contentType("application/json").content("{\"apiKey\":\"private-test-key\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.apiKeyConfigured").value(true)).andExpect(jsonPath("$.apiKey").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-test-key");
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,HardcoverSettings.KEY)).startsWith(CredentialCipher.PREFIX);
        mvc.perform(get("/api/settings/hardcover")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(put("/api/settings/hardcover").with(csrf()).contentType("application/json").content("{\"clearApiKey\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.apiKeyConfigured").value(false));
        mvc.perform(delete("/api/settings/hardcover").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.overridden").value(false));
    }
    @Test @WithMockUser(roles="ADMIN") void exposesOnlyQuotaMetadataWithoutMakingApiRequests() throws Exception {
        var now=java.time.Instant.now();
        when(client.usage()).thenReturn(new HardcoverUsage.Snapshot(5000,4840,160,now,now.plusSeconds(600)));
        mvc.perform(get("/api/settings/hardcover")).andExpect(status().isOk()).andExpect(jsonPath("$.usage.limit").value(5000))
            .andExpect(jsonPath("$.usage.used").value(160)).andExpect(jsonPath("$.usage.remaining").value(4840));
        verify(client).usage();verifyNoMoreInteractions(client);
    }
    @Test @WithMockUser(roles="ADMIN") void exposesExpiryWithoutReturningTheTokenOrOtherClaims() throws Exception {
        long expires=java.time.Instant.now().plusSeconds(86400).getEpochSecond();String token=HardcoverSettingsTests.token("{\"exp\":"+expires+",\"private\":\"secret-claim-only\"}");
        mvc.perform(put("/api/settings/hardcover").with(csrf()).contentType("application/json").content("{\"apiKey\":\""+token+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.expiration.expired").value(false)).andExpect(jsonPath("$.expiration.expiresSoon").value(true));
        var result=mvc.perform(get("/api/settings/hardcover")).andExpect(status().isOk()).andExpect(jsonPath("$.expiration.expiresAt").isString()).andExpect(jsonPath("$.apiKey").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token,"secret-claim-only");
        verify(client,never()).checkKey(anyString());
    }
    @Test @WithMockUser(roles="ADMIN") void csrfIsRequired() throws Exception {
        mvc.perform(put("/api/settings/hardcover").contentType("application/json").content("{\"apiKey\":\"private-test-key\"}")).andExpect(status().isForbidden());verifyNoInteractions(client);
    }
}
