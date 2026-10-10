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

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties={"eplsync.security.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
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
    @Test void savingRestoringAndReloadingPreserveOtherComponentsSettings() {
        var updates = new com.rlibanez.eplsync.updates.ApplicationUpdateSettings(jdbc, true);
        updates.save(new com.rlibanez.eplsync.updates.ApplicationUpdateSettings.Settings(false));
        // Future components may use their own format and even a similar key prefix.
        Map<String,String> foreign = Map.of("application.updates.automatic", "false",
                "future.component.options", "opaque value", "torrent.future-component.option", "untouched");
        foreign.forEach((key,value) -> jdbc.update("insert into app_settings values (?,?) "
                + "on conflict(setting_key) do update set setting_value=excluded.setting_value",key,value));
        try {
            settings.save("events", Map.of("events.retention.max-count", 50));
            assertThat(settings.snapshot().events().getRetention().getMaxCount()).isEqualTo(50);
            assertForeignSettings(foreign);
            settings.restore("events");
            assertThat(jdbc.queryForList("select setting_value from app_settings where setting_key='events.retention.max-count'",String.class)).isEmpty();
            assertForeignSettings(foreign);
            var reloaded = reload(TEST_KEY);
            reloaded.save("events", Map.of("events.retention.max-count", 60));
            assertForeignSettings(foreign);
            assertThat(new com.rlibanez.eplsync.updates.ApplicationUpdateSettings(jdbc,true).automatic()).isFalse();
        } finally {
            foreign.keySet().forEach(key -> jdbc.update("delete from app_settings where setting_key=?",key));
        }
    }
    private void assertForeignSettings(Map<String,String> expected) {
        expected.forEach((key,value) -> assertThat(jdbc.queryForObject(
                "select setting_value from app_settings where setting_key=?",String.class,key)).isEqualTo(value));
    }
    private static final String TEST_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private ServerSettings reload(String key) {
        var environment=new org.springframework.mock.env.MockEnvironment()
            .withProperty("eplsync.catalog.zip-url",env.getRequiredProperty("eplsync.catalog.zip-url"))
            .withProperty("eplsync.security.secret-key",key);
        return new ServerSettings(jdbc,manager,environment,new TorrentProperties(),new QBittorrentProperties(),
            new CatalogImportProperties(),new CoverCheckProperties(),new EventSettings());
    }
    @Test void missingOrWrongKeysPreserveCiphertextAndKeepOtherSettingsUsable() throws Exception {
        var target=reload(TEST_KEY);
        target.save("torrent",Map.of("torrent.qbittorrent.auth.username","test-user","torrent.qbittorrent.auth.password","private-password","torrent.qbittorrent.auth.api-key","private-key"));
        String stored=jdbc.queryForObject("select setting_value from app_settings where setting_key='torrent.qbittorrent.auth.password'",String.class);
        assertThat(stored).contains(CredentialCipher.PREFIX).doesNotContain("private-password");
        assertThat(reload(TEST_KEY).snapshot().qbittorrent().getAuth().getPassword()).isEqualTo("private-password");
        var blocked=reload("");
        assertThat(blocked.snapshot().torrent().isEnabled()).isFalse();
        assertThat(blocked.view("torrent").credentialsError()).contains("EPLSYNC_SECRET_KEY");
        blocked.save("events",Map.of("events.retention.max-count",17));
        assertThat(jdbc.queryForObject("select setting_value from app_settings where setting_key='torrent.qbittorrent.auth.password'",String.class)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("select setting_value from app_settings where setting_key='torrent.qbittorrent.auth.username'",String.class)).isEqualTo("\"test-user\"");
        assertThatThrownBy(() -> blocked.save("torrent",Map.of("torrent.enabled",true))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> blocked.restore("torrent")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var mvc=MockMvcBuilders.standaloneSetup(new SettingsController(blocked)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/settings/torrent").contentType("application/json").content("{\"torrent.enabled\":true}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").doesNotExist());

        var bytes=new byte[32];bytes[0]=1;
        assertThat(reload(java.util.Base64.getEncoder().encodeToString(bytes)).view("torrent").credentialsError()).isNotNull();
    }
    @Test void missingKeyRejectsSecretWritesAtomicallyButAllowsNonSecretSettings() throws Exception {
        var target=reload("");
        target.save("torrent",Map.of("torrent.bulk.concurrency",2));
        var before=jdbc.queryForList("select * from app_settings order by setting_key");
        assertThatThrownBy(() -> target.save("torrent",Map.of("torrent.qbittorrent.auth.password","private-secret","torrent.bulk.concurrency",3))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForList("select * from app_settings order by setting_key")).isEqualTo(before);
        assertThat(target.snapshot().torrent().getBulk().getConcurrency()).isEqualTo(2);
        var mvc=MockMvcBuilders.standaloneSetup(new SettingsController(target)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/settings/torrent").contentType("application/json").content("{\"torrent.qbittorrent.auth.password\":\"private-secret\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SECRET_KEY_REQUIRED"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-secret"))));
        assertThat(jdbc.queryForList("select * from app_settings order by setting_key")).isEqualTo(before);

    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "api-key,qBittorrent: introduce una API key para activar la conexión.",
        "session,qBittorrent: introduce usuario y contraseña para activar la conexión.",
        "auto,qBittorrent: introduce una API key o usuario y contraseña para activar la conexión."
    })
    void reportsMissingCredentialsWithoutChangingSettings(String mode, String message) throws Exception {
        var target = reload(TEST_KEY);
        var before = jdbc.queryForList("select * from app_settings order by setting_key");
        var mvc = MockMvcBuilders.standaloneSetup(new SettingsController(target))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/settings/torrent").contentType("application/json")
            .content("{\"torrent.enabled\":true,\"torrent.qbittorrent.auth.mode\":\"" + mode + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details").value(message));
        assertThat(target.snapshot().torrent().isEnabled()).isFalse();
        assertThat(jdbc.queryForList("select * from app_settings order by setting_key")).isEqualTo(before);
    }

    @Test void reportsCoverLimitsAndSafeBindingErrors() throws Exception {
        var target = reload(TEST_KEY);
        assertThatThrownBy(() -> target.save("covers", Map.of("catalog.cover-check.connect-timeout", "5s",
            "catalog.cover-check.request-timeout", "2s")))
            .hasMessage("Portadas: los tiempos deben estar entre 1 ms y 5 minutos y cumplir conexión <= petición <= lote.");
        assertThatThrownBy(() -> target.save("covers", Map.of("catalog.cover-check.concurrency", 33)))
            .hasMessage("Portadas: la concurrencia debe estar entre 1 y 32.");
        var mvc = MockMvcBuilders.standaloneSetup(new SettingsController(target))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/settings/torrent").contentType("application/json")
            .content("{\"torrent.qbittorrent.auth.mode\":\"private-invalid-value\",\"torrent.qbittorrent.auth.password\":\"private-password\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details").value("Formato inválido en el ajuste torrent.qbittorrent.auth.mode. Revisa el tipo y formato del campo."))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-invalid-value"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
    }

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
            target.save("torrent",Map.of("torrent.base-url",url,"torrent.enabled",false));
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
        var before = jdbc.queryForList("select * from app_settings order by setting_key");
        assertThatThrownBy(() -> target.save("torrent",Map.of("torrent.base-url","http://new.example",
            "torrent.qbittorrent.auth.mode","session","torrent.qbittorrent.auth.username","new-user")))
            .hasMessage("qBittorrent: introduce usuario y contraseña para activar la conexión.");
        assertThat(target.snapshot().torrent().isEnabled()).isTrue();
        assertThat(target.snapshot().torrent().getBaseUrl()).isEqualTo("http://original.example");
        assertThat(jdbc.queryForList("select * from app_settings order by setting_key")).isEqualTo(before);
        target.save("torrent",Map.of("torrent.base-url","http://new.example",
            "torrent.qbittorrent.auth.mode","session","torrent.qbittorrent.auth.username","new-user",
            "torrent.qbittorrent.auth.password","new-password"));
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
            target.save("torrent",Map.of("torrent.base-url",newUrl,"torrent.enabled",false));
            var reloaded = withInstallationCredentials(oldUrl);
            try (var client = new QBittorrentClient(reloaded.snapshot().torrent(),reloaded.snapshot().qbittorrent())) {
                assertThatThrownBy(client::checkConnection).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
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
    @Test void formConnectionCheckUsesUnsavedCredentialsWithoutPublishingOrPersisting() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(auth);
            boolean allowed = "Bearer temporary-key".equals(auth);
            byte[] body = (allowed ? (exchange.getRequestURI().getPath().endsWith("webapiVersion") ? "2.15.1" : "v5.2.4") : "Forbidden").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(allowed ? 200 : 403, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var target = reload(""); // A probe does not require an encryption key.
            var snapshot = target.snapshot();
            var before = jdbc.queryForList("select * from app_settings order by setting_key");
            var mvc = MockMvcBuilders.standaloneSetup(new SettingsController(target))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var values = new java.util.HashMap<String,Object>();
            values.put("torrent.base-url", url); values.put("torrent.qbittorrent.auth.mode", "api-key");
            values.put("torrent.qbittorrent.auth.api-key", "temporary-key");
            mvc.perform(post("/api/settings/torrent/connection").contentType("application/json").content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(values)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.version").value("v5.2.4"));
            assertThat(requests).hasSize(2).containsOnly("Bearer temporary-key");
            values.put("torrent.qbittorrent.auth.api-key", "incorrect-key");
            mvc.perform(post("/api/settings/torrent/connection").contentType("application/json").content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(values)))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("incorrect-key"))));
            values.remove("torrent.qbittorrent.auth.api-key"); requests.clear();
            mvc.perform(post("/api/settings/torrent/connection").contentType("application/json").content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(values)))
                .andExpect(status().isServiceUnavailable());
            assertThat(requests).isEmpty();
            assertThat(target.snapshot()).isSameAs(snapshot);
            assertThat(jdbc.queryForList("select * from app_settings order by setting_key")).isEqualTo(before);
        } finally { server.stop(0); }
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(authorities={"SETTINGS_MANAGE"})
    void onlyAdminsCanProbeAnUnsavedDestinationOrCredentials() {
        var controller = new SettingsController(reload(TEST_KEY));
        assertThatThrownBy(() -> controller.checkTorrent(Map.of("torrent.base-url", "http://127.0.0.1:1234")))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> controller.checkTorrent(Map.of("torrent.qbittorrent.auth.api-key", "private-key")))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
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
                target.save("torrent",Map.of("torrent.base-url",newUrl,"torrent.enabled",false));
                assertThatThrownBy(client::checkConnection).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
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
