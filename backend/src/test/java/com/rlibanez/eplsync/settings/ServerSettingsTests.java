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
        freshTorrent.setBaseUrl(settings.snapshot().torrent().getBaseUrl());
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
    private ServerSettings withInstallationCredentials(String url) {
        var t = new TorrentProperties(); t.setBaseUrl(url); t.setEnabled(true);
        var q = new QBittorrentProperties();
        q.getAuth().setApiKey("installation-key");
        q.getAuth().setUsername("installation-user");
        q.getAuth().setPassword("installation-password");
        return new ServerSettings(jdbc,manager,env,t,q,new CatalogImportProperties(),new CoverCheckProperties(),new EventSettings());
    }
    private void assertCredentialsCleared(ServerSettings target) {
        assertThat(target.snapshot().torrent().isEnabled()).isFalse();
        var auth = target.snapshot().qbittorrent().getAuth();
        assertThat(auth.getApiKey()).isEmpty();
        assertThat(auth.getUsername()).isEmpty();
        assertThat(auth.getPassword()).isEmpty();
        assertThat(target.view("torrent").fields().stream().filter(f -> f.type().equals("secret")))
            .allMatch(f -> !f.configured());
    }
    @Test void endpointChangesInvalidateInstallationCredentialsAndStayInvalidAfterReload() {
        var target = withInstallationCredentials("https://qbit.example:443/qbit/");
        target.save("torrent",Map.of("torrent.base-url","https://QBIT.EXAMPLE/qbit"));
        assertThat(target.snapshot().torrent().isEnabled()).isTrue();
        assertThat(target.snapshot().qbittorrent().getAuth().getApiKey()).isEqualTo("installation-key");
        for (String url : new String[]{"http://qbit.example/qbit", "http://qbit.example:8080/qbit", "http://other.example/qbit", "http://other.example/other"}) {
            target.save("torrent",Map.of("torrent.base-url",url));
            assertCredentialsCleared(target);
        }
        assertCredentialsCleared(withInstallationCredentials("https://qbit.example/qbit"));
        assertThatThrownBy(() -> target.save("torrent",Map.of("torrent.enabled",true))).isInstanceOf(IllegalArgumentException.class);
        var cleared = new java.util.HashMap<String,Object>();
        cleared.put("torrent.qbittorrent.auth.api-key",null);
        cleared.put("torrent.qbittorrent.auth.password",null);
        target.save("torrent",cleared);
        assertCredentialsCleared(target);
        target.save("torrent",Map.of("torrent.base-url","https://qbit.example/qbit"));
        assertCredentialsCleared(target); // Returning to the old destination does not resurrect its secrets.
        target.restore("torrent");
        assertThat(target.snapshot().torrent().isEnabled()).isTrue();
        assertThat(target.snapshot().qbittorrent().getAuth().getApiKey()).isEqualTo("installation-key");
    }
    @Test void onlyExplicitCredentialsCanEnableANewDestinationAndPartialCredentialsNeverInherit() {
        var target = withInstallationCredentials("http://original.example");
        target.save("torrent",Map.of("torrent.base-url","http://new.example","torrent.enabled",true,
            "torrent.qbittorrent.auth.mode","session","torrent.qbittorrent.auth.username","new-user"));
        assertThat(target.snapshot().torrent().isEnabled()).isFalse();
        assertThat(target.snapshot().qbittorrent().getAuth().getPassword()).isEmpty();
        target.save("torrent",Map.of("torrent.qbittorrent.auth.password","new-password","torrent.enabled",true));
        assertThat(target.snapshot().torrent().isEnabled()).isTrue();
        var reloaded = withInstallationCredentials("http://original.example");
        assertThat(reloaded.snapshot().torrent().isEnabled()).isTrue();
        assertThat(reloaded.snapshot().qbittorrent().getAuth().getPassword()).isEqualTo("new-password");
        assertThat(reloaded.snapshot().qbittorrent().getAuth().getApiKey()).isEmpty();
        reloaded.save("torrent",Map.of("torrent.base-url","http://third.example","torrent.enabled",true,
            "torrent.qbittorrent.auth.mode","api-key","torrent.qbittorrent.auth.api-key","third-key"));
        assertThat(reloaded.snapshot().torrent().isEnabled()).isTrue();
        assertThat(reloaded.snapshot().qbittorrent().getAuth().getApiKey()).isEqualTo("third-key");
        assertThat(reloaded.snapshot().qbittorrent().getAuth().getPassword()).isEmpty();
    }
    @Test void legacyOrMismatchedBindingsAreInvalidatedOnStartup() {
        jdbc.update("insert into app_settings values (?,?)","torrent.base-url",JSON_VALUE("http://other.example"));
        jdbc.update("insert into app_settings values (?,?)","torrent.qbittorrent.auth.api-key",JSON_VALUE("legacy-key"));
        var target = withInstallationCredentials("http://original.example");
        assertCredentialsCleared(target);
        target.save("torrent",Map.of("torrent.qbittorrent.auth.api-key","new-key","torrent.enabled",true));
        jdbc.update("update app_settings set setting_value=? where setting_key='torrent.base-url'",JSON_VALUE("http://tampered.example"));
        assertCredentialsCleared(withInstallationCredentials("http://original.example"));
    }
    private String JSON_VALUE(String value) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);
    }
    @Test void checksNeverSendPreviousCredentialsToTheNewServerIncludingAfterRestartAndRestore() throws Exception {
        var originalCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var newCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var original = fakeServer(originalCalls);
        var replacement = fakeServer(newCalls);
        String oldUrl = "http://127.0.0.1:"+original.getAddress().getPort();
        String newUrl = "http://127.0.0.1:"+replacement.getAddress().getPort();
        try {
            var target = withInstallationCredentials(oldUrl);
            try (var client = new QBittorrentClient(target.snapshot().torrent(),target.snapshot().qbittorrent())) {
                assertThat(client.checkConnection().connected()).isTrue();
            }
            assertThat(originalCalls).allMatch(v -> v.equals("Bearer installation-key"));
            target.save("torrent",Map.of("torrent.base-url",newUrl));
            var reloaded = withInstallationCredentials(oldUrl);
            try (var client = new QBittorrentClient(reloaded.snapshot().torrent(),reloaded.snapshot().qbittorrent())) {
                assertThat(client.checkConnection().enabled()).isFalse();
            }
            assertThat(newCalls).isEmpty();
            reloaded.save("torrent",Map.of("torrent.qbittorrent.auth.api-key","replacement-key","torrent.enabled",true));
            try (var client = new QBittorrentClient(reloaded.snapshot().torrent(),reloaded.snapshot().qbittorrent())) {
                assertThat(client.checkConnection().connected()).isTrue();
            }
            assertThat(newCalls).hasSize(2).allMatch(v -> v.equals("Bearer replacement-key"));
            reloaded.restore("torrent");
            try (var client = new QBittorrentClient(reloaded.snapshot().torrent(),reloaded.snapshot().qbittorrent())) {
                assertThat(client.checkConnection().connected()).isTrue();
            }
            assertThat(originalCalls).hasSize(4).allMatch(v -> v.equals("Bearer installation-key"));
        } finally { original.stop(0); replacement.stop(0); }
    }
    private com.sun.net.httpserver.HttpServer fakeServer(java.util.List<String> calls) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            calls.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            var body = "5.2.0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); return server;
    }

    @Test void sessionPasswordsAndCookiesAreNeverReusedForAReplacementServer() throws Exception {
        var oldCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var newCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var original = sessionServer("original",oldCalls);
        var replacement = sessionServer("replacement",newCalls);
        String oldUrl = "http://127.0.0.1:"+original.getAddress().getPort();
        String newUrl = "http://127.0.0.1:"+replacement.getAddress().getPort();
        try {
            var target = withInstallationCredentials(oldUrl);
            target.save("torrent",Map.of("torrent.qbittorrent.auth.mode","session"));
            var t = new TorrentProperties(); t.useEffective(() -> target.snapshot().torrent());
            var q = new QBittorrentProperties(); q.useEffective(() -> target.snapshot().qbittorrent());
            try (var client = new QBittorrentClient(t,q)) {
                assertThat(client.checkConnection().connected()).isTrue();
                assertThat(oldCalls).anyMatch(v -> v.contains("installation-password"));
                target.save("torrent",Map.of("torrent.base-url",newUrl));
                assertThat(client.checkConnection().enabled()).isFalse();
                assertThat(newCalls).isEmpty();
                target.save("torrent",Map.of("torrent.qbittorrent.auth.username","new-user",
                    "torrent.qbittorrent.auth.password","new-password","torrent.enabled",true));
                assertThat(client.checkConnection().connected()).isTrue();
                assertThat(newCalls).hasSize(3).noneMatch(v -> v.contains("installation-") || v.contains("original-cookie"));
                assertThat(newCalls).anyMatch(v -> v.contains("new-password"));
                assertThat(newCalls).anyMatch(v -> v.contains("replacement-cookie"));
            }
        } finally { original.stop(0); replacement.stop(0); }
    }
    private com.sun.net.httpserver.HttpServer sessionServer(String name,java.util.List<String> calls) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            try (exchange) {
                calls.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))+";"+
                    String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"))+";"+
                    new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                if (exchange.getRequestURI().getPath().endsWith("/auth/login")) {
                    exchange.getResponseHeaders().add("Set-Cookie","SID="+name+"-cookie; Path=/; HttpOnly");
                    exchange.sendResponseHeaders(204,-1);
                } else {
                    var body = "5.2.0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
                }
            }
        });
        server.start(); return server;
    }

}
