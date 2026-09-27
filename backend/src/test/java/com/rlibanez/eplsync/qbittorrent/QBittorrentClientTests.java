package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.service.TorrentClientService;
import java.util.List;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties.AuthMode;
import com.rlibanez.eplsync.controller.TorrentClientController;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QBittorrentClientTests {
    private HttpServer server;
    private ExecutorService executor;
    private QBittorrentClient client;
    private TorrentProperties properties;
    private QBittorrentProperties qbittorrent;
    private final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int keyStatus = 200;
    private volatile int renameStatus = 200;
    private volatile int loginStatus = 204;
    private volatile String loginBody = "";
    private volatile String cookieName = "QBT_SID_8080";
    private volatile String cookieValue = "session1";
    private volatile String version = "v5.2.3";
    private volatile boolean rejectSession;
    private volatile long delay;

    record Call(String method, String path, String authorization, String cookie, String body, String origin) {}

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        properties = new TorrentProperties();
        qbittorrent = new QBittorrentProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/qbit/");
        qbittorrent.getAuth().setApiKey("test-key");
        qbittorrent.getAuth().setUsername("user + ñ");
        qbittorrent.getAuth().setPassword(" p&+=ss ");
    }

    private QBittorrentClient client() {
        properties.validate();
        client = new QBittorrentClient(properties, qbittorrent);
        return client;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getRequestHeaders();
            var call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    headers.getFirst("Authorization"), headers.getFirst("Cookie"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), headers.getFirst("Origin"));
            calls.add(call);
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            if (call.path().equals("/qbit/api/v2/auth/login")) {
                if (loginStatus < 300 && !loginBody.equals("Fails."))
                    exchange.getResponseHeaders().add("Set-Cookie", cookieName + "=" + cookieValue + "; Path=/; HttpOnly");
                respond(exchange, loginStatus, loginBody);
            } else if (call.path().equals("/qbit/api/v2/app/version") || call.path().equals("/qbit/api/v2/app/webapiVersion") || call.path().equals("/qbit/api/v2/torrents/rename")) {
                if (call.authorization() != null) {
                    if (keyStatus != 200) { respond(exchange, keyStatus, "remote body with test-key secret"); return; }
                } else if (rejectSession || call.cookie() == null || !call.cookie().contains(cookieName + "=" + cookieValue)) {
                    respond(exchange, 403, "Forbidden"); return;
                }
                if (call.path().endsWith("/torrents/rename")) respond(exchange, renameStatus, "");
                else respond(exchange, 200, call.path().endsWith("/version") ? version : "2.15.1");
            } else {
                respond(exchange, 404, "Unexpected path");
            }
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) exchange.getResponseBody().write(bytes);
    }

    @AfterEach
    void close() {
        if (client != null) client.close();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void autoPrefersKeyAndReturnsVersionsWithoutLogin() {
        var status = client().checkConnection();
        assertThat(status.connected()).isTrue();
        assertThat(status.authMode()).isEqualTo("api-key");
        assertThat(status.version()).isEqualTo("v5.2.3");
        assertThat(status.apiVersion()).isEqualTo("2.15.1");
        assertThat(calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.authorization()).isEqualTo("Bearer test-key");
            assertThat(call.cookie()).isNull();
            assertThat(call.origin()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
        });
    }

    @Test
    void autoFallsBackToSessionOnAuthenticationRejectionAndEncodesCredentials() {
        keyStatus = 403;
        var status = client().checkConnection();
        assertThat(status.authMode()).isEqualTo("session");
        assertThat(calls).hasSize(4);
        var login = calls.get(1);
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.body()).isEqualTo("username=user+%2B+%C3%B1&password=+p%26%2B%3Dss+");
        assertThat(login.authorization()).isNull();
        assertThat(calls.get(2).cookie()).contains("QBT_SID_8080=session1");
        assertThat(calls.get(2).authorization()).isNull();
    }

    @Test
    void sessionReusesCookiesAndRenewsOnceWhenExpired() {
        qbittorrent.getAuth().setMode(AuthMode.SESSION);
        var qbit = client();
        qbit.checkConnection();
        qbit.checkConnection();
        assertThat(calls.stream().filter(c -> c.path().endsWith("/login")).count()).isEqualTo(1);
        cookieValue = "session2";
        qbit.checkConnection();
        assertThat(calls.stream().filter(c -> c.path().endsWith("/login")).count()).isEqualTo(2);
        assertThat(calls.getLast().cookie()).contains("session2");
        assertThat(calls).allSatisfy(call -> assertThat(call.authorization()).isNull());
    }

    @Test
    void autoWithOnlySessionSupportsLegacyCookieAndLoginResponse() {
        qbittorrent.getAuth().setApiKey("");
        cookieName = "SID";
        loginStatus = 200;
        loginBody = "Ok.";
        assertThat(client().checkConnection().authMode()).isEqualTo("session");
        assertThat(calls).hasSize(3);
        assertThat(calls.getLast().cookie()).contains("SID=session1");
    }

    @Test
    void explicitApiKeyNeverFallsBackAndAutoWithoutSessionCannotFallback() {
        keyStatus = 401;
        qbittorrent.getAuth().setMode(AuthMode.API_KEY);
        assertReason(client(), AUTHENTICATION);
        assertThat(calls).hasSize(1);
        client.close();
        qbittorrent.getAuth().setMode(AuthMode.AUTO);
        qbittorrent.getAuth().setPassword("");
        assertReason(client(), AUTHENTICATION);
        assertThat(calls).hasSize(2);
    }

    @Test
    void doesNotFallbackForServerErrorsRedirectsOrHtmlResponses() {
        var qbit = client();
        for (int status : new int[] {500, 502, 429, 302}) {
            keyStatus = status;
            assertReason(qbit, UPSTREAM);
        }
        keyStatus = 200;
        version = "<html>Login</html>";
        assertReason(qbit, UPSTREAM);
        assertThat(calls).hasSize(5).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
    }

    @Test
    void boundsTimeoutAndDoesNotFallback() {
        delay = 500;
        properties.setRequestTimeout(Duration.ofMillis(100));
        assertReason(client(), TIMEOUT);
        assertThat(calls).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void reportsConnectionFailureWithoutFallback() {
        var qbit = client();
        server.stop(0);
        assertReason(qbit, UPSTREAM);
        assertThat(calls).isEmpty();
    }

    @Test
    void rejectsBadLoginAndDoesNotLoopOnRejectedSession() {
        qbittorrent.getAuth().setMode(AuthMode.SESSION);
        loginStatus = 200;
        loginBody = "Fails.";
        var qbit = client();
        assertReason(qbit, AUTHENTICATION);
        assertThat(calls).hasSize(1);
        loginStatus = 204;
        loginBody = "";
        rejectSession = true;
        assertReason(qbit, AUTHENTICATION);
        assertThat(calls).hasSize(3);
    }

    @Test
    void disabledIntegrationMakesNoRequests() {
        properties.setEnabled(false);
        var status = client().checkConnection();
        assertThat(status.enabled()).isFalse();
        assertThat(status.connected()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void endpointExposesStatusAndSanitizedGatewayErrors() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new TorrentClientController(new TorrentClientService(properties, List.of(client()))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/torrent/client/connection")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.client").value("qbittorrent"))
                .andExpect(jsonPath("$.authMode").value("api-key"))
                .andExpect(jsonPath("$.version").value("v5.2.3"));
        keyStatus = 500;
        mvc.perform(get("/api/torrent/client/connection")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Conexión con el cliente torrent fallida"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-key"))));
    }

    @Test
    void renameEndpointResolvesBookFieldsAndOnlyCallsTorrentRename() throws Exception {
        var repository = org.mockito.Mockito.mock(com.rlibanez.eplsync.repository.CatalogBookRepository.class);
        String hash = "A".repeat(40);
        var book = com.rlibanez.eplsync.model.CatalogBook.builder().eplId(2663L)
                .author("Bronte, Charlotte").title("Jane Eyre & más").revision(1.2).links(hash).build();
        org.mockito.Mockito.when(repository.findById(2663L)).thenReturn(java.util.Optional.of(book));
        properties.getRename().setPattern("{author} - {title} [{eplId}] (r{revision})");
        var service = new com.rlibanez.eplsync.service.TorrentRenameService(repository,
                new com.rlibanez.eplsync.torrent.MagnetLinkBuilder(properties),
                new com.rlibanez.eplsync.torrent.TorrentNameResolver(), properties,
                new TorrentClientService(properties, List.of(client())));
        var mvc = MockMvcBuilders.standaloneSetup(new com.rlibanez.eplsync.controller.TorrentRenameController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String endpoint = "/api/catalog/books/2663/torrents/" + hash.toLowerCase(java.util.Locale.ROOT) + "/rename";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bronte, Charlotte - Jane Eyre & más [2663] (r1.2)"))
                .andExpect(jsonPath("$.hash").value(hash));
        assertThat(calls).hasSize(3);
        var rename = calls.getLast();
        assertThat(rename.method()).isEqualTo("POST");
        assertThat(rename.path()).isEqualTo("/qbit/api/v2/torrents/rename");
        assertThat(rename.authorization()).isEqualTo("Bearer test-key");
        assertThat(java.net.URLDecoder.decode(rename.body(), StandardCharsets.UTF_8))
                .isEqualTo("hash=" + hash + "&name=Bronte, Charlotte - Jane Eyre & más [2663] (r1.2)");
        calls.clear();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint.replace("2663", "99")))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint.replace(hash.toLowerCase(java.util.Locale.ROOT), "B".repeat(40))))
                .andExpect(status().isBadRequest());
        properties.getRename().setEnabled(false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint))
                .andExpect(status().isConflict());
        assertThat(calls).isEmpty();
    }

    @Test
    void renameUsesSessionFallbackAndDoesNotRetryTheWriteOnFailure() {
        keyStatus = 403;
        renameStatus = 204;
        var qbit = client();
        qbit.renameTorrent("A".repeat(40), "Nombre (r1.2)");
        assertThat(calls.getLast().cookie()).contains("QBT_SID_8080=session1");
        assertThat(calls.getLast().authorization()).isNull();
        calls.clear();
        renameStatus = 500;
        assertThatThrownBy(() -> qbit.renameTorrent("A".repeat(40), "Otro nombre"))
                .isInstanceOf(QBittorrentConnectionException.class);
        assertThat(calls.stream().filter(call -> call.path().endsWith("/torrents/rename")).count()).isEqualTo(1);
        assertThat(calls).noneSatisfy(call -> assertThat(call.path()).contains("renameFile", "renameFolder"));
    }

    @Test
    void renameReportsMissingTorrentWithoutAddingIt() {
        renameStatus = 404;
        var qbit = client();
        assertThatThrownBy(() -> qbit.renameTorrent("A".repeat(40), "Nombre"))
                .isInstanceOfSatisfying(com.rlibanez.eplsync.exception.TorrentOperationException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThat(calls).hasSize(3);
        assertThat(calls.getLast().path()).isEqualTo("/qbit/api/v2/torrents/rename");
    }

    @Test
    void incompleteCredentialsReturn503WithoutNetworkAndDoNotPreventClientCreation() throws Exception {
        qbittorrent.getAuth().setApiKey("");
        qbittorrent.getAuth().setPassword("");
        var qbit = client();
        assertThat(calls).isEmpty();
        var mvc = MockMvcBuilders.standaloneSetup(new TorrentClientController(
                new TorrentClientService(properties, List.of(qbit))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/torrent/client/connection"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details").value("Configuración qBittorrent: faltan credenciales completas para auth.mode"));
        assertThat(calls).isEmpty();
    }

    private void assertReason(QBittorrentClient qbit, QBittorrentConnectionException.Reason reason) {
        assertThatThrownBy(qbit::checkConnection).isInstanceOfSatisfying(QBittorrentConnectionException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(reason));
    }
}
