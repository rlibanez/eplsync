package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

class CalibreTests {
    HttpServer server;
    String url;
    int status = 200;
    boolean redirect, large;
    final List<String> calls = new ArrayList<>();
    final CalibreClient client = new CalibreClient();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", e -> {
            calls.add(e.getRequestURI().toString());
            String path = e.getRequestURI().getPath();
            String body = path.endsWith("library-info") ? "{\"library_map\":{\"main\":\"Library\"},\"default_library\":\"main\"}"
                : path.contains("/search/") ? "{\"total_num\":2,\"book_ids\":[3,9]}"
                : "{\"3\":{\"title\":\"Book\",\"authors\":[\"Author\"],\"identifiers\":{\"epl\":\"80899\",\"revision\":\"1.0\"}},\"9\":{\"title\":\"Other\",\"identifiers\":{}}}";
            if (large) body = " ".repeat(2 * 1024 * 1024 + 1);
            e.getResponseHeaders().add("Content-Type", "application/json");
            if (redirect) e.getResponseHeaders().add("Location", url + "/other");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(redirect ? 302 : status, bytes.length);
            try (var stream = e.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    CalibreSettings.Connection connection() { return new CalibreSettings.Connection(url, "", "", "main"); }
    @Test void readsLibrariesAndBatchesMetadata() {
        assertThat(client.check(connection()).defaultLibrary()).isEqualTo("main");
        var books = client.books(connection(), 0, 20);
        assertThat(books.total()).isEqualTo(2);
        assertThat(books.items().getFirst().eplId()).isEqualTo(80899);
        assertThat(books.items().getFirst().revision()).isEqualByComparingTo("1.0");
        assertThat(books.items().get(1).linked()).isFalse();
        assertThat(calls).hasSize(3).contains("/ajax/books/main?ids=3,9");
    }
    @Test void rejectsAuthenticationRedirectsAndOversizedResponses() {
        status = 401;
        assertThatThrownBy(() -> client.check(connection())).hasMessageContaining("rechazado");
        status = 200; redirect = true;
        assertThatThrownBy(() -> client.check(connection())).hasMessageContaining("redirección");
        assertThat(calls).doesNotContain("/other");
        redirect = false; large = true;
        assertThatThrownBy(() -> client.check(connection())).hasMessageContaining("2 MiB");
    }
    @Test void challengeAuthentication() {
        server.removeContext("/");
        server.createContext("/", e -> {
            String auth = e.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                e.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"calibre\"");
                e.sendResponseHeaders(401, -1);
            } else {
                assertThat(auth).isEqualTo("Basic " + Base64.getEncoder().encodeToString("reader:password".getBytes(StandardCharsets.UTF_8)));
                byte[] body = "{\"library_map\":{},\"default_library\":\"\"}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body);
            }
            e.close();
        });
        assertThat(client.check(new CalibreSettings.Connection(url, "reader", "password", "")).connected()).isTrue();
    }
    @Test void validatesIdentifiersAndPagination() {
        assertThat(CalibreClient.positiveId("-1")).isNull();
        assertThat(CalibreClient.revision("Infinity")).isNull();
        assertThat(CalibreClient.revision("0")).isNull();
        assertThatThrownBy(() -> client.books(connection(), 0, 101)).hasMessageContaining("1–100");
        assertThat(calls).isEmpty();
    }
    @Test void digestAuthentication() {
        server.removeContext("/");
        server.createContext("/", e -> {
            String auth = e.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                e.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"calibre\", nonce=\"test-nonce\", algorithm=MD5, qop=\"auth\"");
                e.sendResponseHeaders(401, -1);
            } else {
                assertThat(auth).startsWith("Digest ").contains("username=\"reader\"").contains("nonce=\"test-nonce\"").contains("uri=\"/ajax/library-info\"");
                byte[] body = "{\"library_map\":{},\"default_library\":\"\"}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body);
            }
            e.close();
        });
        assertThat(client.check(new CalibreSettings.Connection(url, "reader", "password", "")).connected()).isTrue();
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="calibre.test.url", matches=".+")
    void readsActualCalibreWithoutChangingIt() {
        var c = new CalibreSettings.Connection(CalibreSettings.normalize(System.getProperty("calibre.test.url")), "", "", "calibre");
        assertThat(client.check(c).libraries()).extracting(CalibreClient.Library::id).contains("calibre");
        assertThat(client.books(c, 0, 20).items()).anyMatch(b -> Long.valueOf(80899).equals(b.eplId()) && b.linked());
    }
    @Test void encryptsPasswordsAndBindsThemToDestination() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE calibre_books(active boolean)");
            jdbc.execute("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY,setting_value TEXT)");
            var settings = new CalibreSettings(jdbc, Base64.getEncoder().encodeToString(new byte[32]));
            settings.save(new CalibreSettings.Input(url, "reader", "private-password", false, "main"));
            assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings", String.class)).doesNotContain("private-password").contains("encrypted:v1:");
            assertThat(settings.view().passwordConfigured()).isTrue();
            assertThat(settings.connection().password()).isEqualTo("private-password");
            assertThat(settings.resolve(new CalibreSettings.Input("http://other:8080", "reader", "", false, "main")).password()).isEmpty();
            assertThat(settings.resolve(new CalibreSettings.Input(url, "other", "", false, "main")).password()).isEmpty();
            assertThat(settings.resolve(new CalibreSettings.Input(url, "reader", "", true, "main")).password()).isEmpty();
            jdbc.update("DELETE FROM app_settings");
            assertThatThrownBy(() -> new CalibreSettings(jdbc, "").save(new CalibreSettings.Input(url, "reader", "secret", false, "main"))).isInstanceOf(SecretKeyRequiredException.class);
        } finally { source.destroy(); }
    }
    @Test void validatesUrls() {
        assertThat(CalibreSettings.normalize("HTTP://LOCALHOST:80/calibre/")).isEqualTo("http://localhost/calibre");
        for (String bad : List.of("file:///tmp/library", "http://user:password@host", "http://host?token=secret", "http://host/#library"))
            assertThatThrownBy(() -> CalibreSettings.normalize(bad)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
}
