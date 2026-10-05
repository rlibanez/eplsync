package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.config.*;
import com.rlibanez.eplsync.qbittorrent.*;
import com.rlibanez.eplsync.events.EventSettings;
import com.rlibanez.eplsync.service.CatalogImportService;
import com.rlibanez.eplsync.maintenance.DatabaseResetService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","DOWNLOADS_READ","TORRENT_SEND","TORRENT_SYNC","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class ServerSettingsTests {
    @Autowired ServerSettings settings;
    @Autowired SettingsController controller;
    @Autowired TorrentProperties torrent;
    @Autowired CoverCheckProperties covers;
    @Autowired CatalogImportService imports;
    @Autowired DatabaseResetService reset;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired PlatformTransactionManager manager;
    @AfterEach void restore() { for (String section : new String[]{"torrent","covers","catalog","events"}) settings.restore(section); }
    @Test void preservesConfiguredAndSavedDurationText() {
        assertThat(settings.view("catalog").fields().stream().filter(f -> f.key().equals("catalog.import.retention")).findFirst().orElseThrow().value()).isEqualTo("24h");
        settings.save("catalog", Map.of("catalog.import.retention", "600s"));
        assertThat(settings.view("catalog").fields().stream().filter(f -> f.key().equals("catalog.import.retention")).findFirst().orElseThrow().value()).isEqualTo("600s");
        assertThat(settings.snapshot().catalogImport().getRetention()).isEqualTo(java.time.Duration.ofMinutes(10));
        settings.restore("catalog");
        assertThat(settings.view("catalog").fields().stream().filter(f -> f.key().equals("catalog.import.retention")).findFirst().orElseThrow().value()).isEqualTo("24h");
    }

    @Test void appliesAtomicallyPinsRunningOperationsAndRestores() throws Exception {
        int before = torrent.getBulk().getConcurrency();
        var pin = settings.pin();
        try (pin) {
            settings.save("torrent", Map.of("torrent.bulk.concurrency", 3));
            assertThat(torrent.getBulk().getConcurrency()).isEqualTo(before);
        }
        assertThat(torrent.getBulk().getConcurrency()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from app_settings", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> settings.save("torrent", Map.of("torrent.bulk.concurrency", 99))).isInstanceOf(IllegalArgumentException.class);
        assertThat(torrent.getBulk().getConcurrency()).isEqualTo(3);
        settings.restore("torrent");
        assertThat(torrent.getBulk().getConcurrency()).isEqualTo(before);
    }
    @Test void updatesCatalogAndCoverDefaultsAndRejectsInvalidCombinations() {
        settings.save("catalog", Map.of("catalog.zip-url", "https://example.org/books.zip"));
        assertThat(imports.defaultUrl()).isEqualTo("https://example.org/books.zip");
        settings.save("covers", Map.of("catalog.cover-check.concurrency", 8));
        assertThat(covers.getConcurrency()).isEqualTo(8);
        assertThatThrownBy(() -> settings.save("covers", Map.of("catalog.cover-check.connect-timeout", "10s"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(covers.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
    }
    @Test void persistsAcrossReloadAndNeverReturnsSecrets() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/settings/torrent").contentType("application/json").content("{\"torrent.qbittorrent.auth.password\":\"private-secret\",\"torrent.bulk.concurrency\":4}"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-secret"))));
        var freshTorrent = new TorrentProperties();
        var reloaded = new ServerSettings(jdbc, manager, env, freshTorrent, new QBittorrentProperties(), new CatalogImportProperties(), new CoverCheckProperties(), new EventSettings());
        assertThat(freshTorrent.getBulk().getConcurrency()).isEqualTo(4);
        assertThat(reloaded.snapshot().qbittorrent().getAuth().getPassword()).isEqualTo("private-secret");
        assertThat(reloaded.view("torrent").fields().stream().filter(f -> f.key().endsWith("password")).findFirst().orElseThrow().configured()).isTrue();
        mvc.perform(put("/api/settings/torrent").contentType("application/json").content("{\"unknown\":\"value\"}" )).andExpect(status().isBadRequest());
    }
    @Test void databaseFailureDoesNotPublishNewValues() {
        settings.save("events", Map.of("events.retention.max-count", 50));
        jdbc.execute("CREATE TRIGGER reject_settings BEFORE INSERT ON app_settings BEGIN SELECT RAISE(ABORT, 'test'); END");
        try {
            assertThatThrownBy(() -> settings.save("events", Map.of("events.retention.max-count", 25))).isInstanceOf(RuntimeException.class);
            assertThat(settings.snapshot().events().getRetention().getMaxCount()).isEqualTo(50);
            assertThat(jdbc.queryForObject("select setting_value from app_settings where setting_key='events.retention.max-count'", String.class)).isEqualTo("50");
        } finally { jdbc.execute("DROP TRIGGER reject_settings"); }
    }
    @Test void externalApiReadsSavedDefaults() throws Exception {
        var defaults = new com.rlibanez.eplsync.controller.TorrentOptionsController(torrent, settings.snapshot().qbittorrent());
        var mvc = MockMvcBuilders.standaloneSetup(controller, defaults).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/settings/torrent").contentType("application/json").content("{\"torrent.bulk.concurrency\":6}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/torrent/options")).andExpect(status().isOk()).andExpect(jsonPath("$.concurrency").value(6));
    }

    @Test void fullResetDeletesOverridesAndRestoresEffectiveConfiguration() {
        settings.save("events", Map.of("events.retention.max-count", 25));
        reset.reset(true);
        assertThat(jdbc.queryForObject("select count(*) from app_settings", Integer.class)).isZero();
        assertThat(settings.snapshot().events().getRetention().getMaxCount()).isEqualTo(10000);
    }
}
