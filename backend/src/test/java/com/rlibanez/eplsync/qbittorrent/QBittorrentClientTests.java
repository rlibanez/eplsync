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
    private volatile String torrentInfo = "[]";
    private volatile String categories = "{\"Libros\":{},\"Personal\":{}}";
    private volatile int addStatus = 200;
    private volatile String addBody = "{\"success_count\":1,\"failure_count\":0,\"pending_count\":0}";
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
            } else if (call.path().equals("/qbit/api/v2/app/version") || call.path().equals("/qbit/api/v2/app/webapiVersion") || call.path().startsWith("/qbit/api/v2/torrents/")) {
                if (call.authorization() != null) {
                    if (keyStatus != 200) { respond(exchange, keyStatus, "remote body with test-key secret"); return; }
                } else if (rejectSession || call.cookie() == null || !call.cookie().contains(cookieName + "=" + cookieValue)) {
                    respond(exchange, 403, "Forbidden"); return;
                }
                if (call.path().endsWith("/torrents/info")) respond(exchange, 200, torrentInfo);
                else if (call.path().endsWith("/torrents/categories")) respond(exchange, 200, categories);
                else if (call.path().endsWith("/torrents/add")) respond(exchange, addStatus, addBody);
                else if (call.path().endsWith("/torrents/rename")) respond(exchange, renameStatus, "");
                else respond(exchange, 200, call.path().endsWith("/version") ? version : "2.15.1");
            } else {
                respond(exchange, 404, "Unexpected path");
            }
        }
    }

    private org.springframework.test.web.servlet.MockMvc downloadMvc(String links) {
        var repository = org.mockito.Mockito.mock(com.rlibanez.eplsync.repository.CatalogBookRepository.class);
        var book = new com.rlibanez.eplsync.model.CatalogBook();
        book.setEplId(2663L);
        book.setTitle("Jane Eyre & más");
        book.setAuthor("Bronte, Charlotte");
        book.setRevision(1.2);
        book.setLanguage(com.rlibanez.eplsync.model.enums.Language.ESPANOL);
        book.setLinks(links);
        org.mockito.Mockito.when(repository.findById(2663L)).thenReturn(java.util.Optional.of(book));
        var service = new com.rlibanez.eplsync.service.TorrentDownloadService(repository, properties,
                new com.rlibanez.eplsync.torrent.MagnetLinkBuilder(properties),
                new com.rlibanez.eplsync.torrent.TorrentNameResolver(),
                new TorrentClientService(properties, List.of(client())));
        return MockMvcBuilders.standaloneSetup(new com.rlibanez.eplsync.controller.TorrentDownloadController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private java.util.Map<String, String> addedFields() {
        var call = calls.stream().filter(c -> c.path().endsWith("/torrents/add")).findFirst().orElseThrow();
        return java.util.Arrays.stream(call.body().split("&")).map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair ->
                        java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    @Test
    void downloadUsesDefaultsAndSendsOnlyOneMagnet() throws Exception {
        properties.getRename().setPattern("{author} - {title} [{eplId}] (r{revision})");
        downloadMvc("A".repeat(40)).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(addedFields()).containsEntry("category", "Libros").containsEntry("tags", "EPLSync,es")
                .containsEntry("stopped", "false").containsEntry("autoTMM", "true")
                .containsEntry("rename", "Bronte, Charlotte - Jane Eyre & más [2663] (r1.2)")
                .doesNotContainKey("savepath");
        assertThat(addedFields().get("urls")).startsWith("magnet:?xt=urn:btih:" + "A".repeat(40));
        assertThat(calls.stream().filter(c -> c.method().equals("POST")).count()).isEqualTo(1);
    }

    @Test
    void downloadOverridesDefaultsAndSupportsSessionFallback() throws Exception {
        keyStatus = 403;
        downloadMvc("A".repeat(40)).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663").contentType("application/json").content("""
                {"start":false,"savePath":"/downloads/libros","rename":{"enabled":false},
                 "qbittorrent":{"category":"Personal","tags":[],"autoManagement":false}}
                """)) .andExpect(status().isAccepted());
        assertThat(addedFields()).containsEntry("category", "Personal").containsEntry("tags", "")
                .containsEntry("savepath", "/downloads/libros").containsEntry("stopped", "true")
                .containsEntry("autoTMM", "false").doesNotContainKey("rename");
        assertThat(calls.getLast().cookie()).contains("session1");
        assertThat(calls.getLast().authorization()).isNull();
    }

    @Test
    void existingTorrentDoesNotWriteOrChangeOptions() throws Exception {
        torrentInfo = "[{\"hash\":\"" + "a".repeat(40) + "\"}]";
        downloadMvc("A".repeat(40)).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_EXISTS"));
        assertThat(calls).noneMatch(c -> c.method().equals("POST"));
    }

    @Test
    void multipleHashesRequireSelectionAndRejectUnrelatedHash() throws Exception {
        var mvc = downloadMvc("A".repeat(40) + "," + "B".repeat(40));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663")).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663").contentType("application/json")
                .content("{\"hash\":\"" + "C".repeat(40) + "\"}")).andExpect(status().isBadRequest());
        assertThat(calls).isEmpty();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663").contentType("application/json")
                .content("{\"hash\":\"" + "b".repeat(40) + "\"}")).andExpect(status().isAccepted());
        assertThat(addedFields().get("urls")).contains("urn:btih:" + "B".repeat(40));
    }

    @Test
    void rejectsConflictingDestinationAndMissingCategoryBeforeWriting() throws Exception {
        var mvc = downloadMvc("A".repeat(40));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663").contentType("application/json")
                .content("{\"savePath\":\"/downloads\"}")).andExpect(status().isBadRequest());
        assertThat(calls).isEmpty();
        categories = "{}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663")).andExpect(status().isConflict());
        assertThat(calls).noneMatch(c -> c.method().equals("POST"));
    }

    @Test
    void remoteAddFailureIsNotRetried() throws Exception {
        addStatus = 500;
        var mvc = downloadMvc("A".repeat(40));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663")).andExpect(status().isBadGateway());
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/add")).count()).isEqualTo(1);
    }

    @Test
    void downloadValidatesMissingBookEmptyLinksAndDisabledIntegration() throws Exception {
        var mvc = downloadMvc("");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/999")).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663")).andExpect(status().isUnprocessableContent());
        properties.setEnabled(false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663")).andExpect(status().isConflict());
        assertThat(calls).isEmpty();
    }

    @Test
    void downloadRejectsInvalidRenameAndTagsWithoutNetwork() throws Exception {
        var mvc = downloadMvc("A".repeat(40));
        for (String body : List.of("{\"rename\":{\"pattern\":\"{notAField}\"}}",
                "{\"qbittorrent\":{\"tags\":[\"one,two\"]}}")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/torrent/books/2663").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void requestTagPlaceholdersReplaceDefaultsAndResolveBeforeSending() throws Exception {
        downloadMvc("A".repeat(40)).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663").contentType("application/json").content("""
                {"qbittorrent":{"tags":["idioma:{language}","{collection}","idioma:es","{revision}"]}}
                """)) .andExpect(status().isAccepted());
        assertThat(addedFields()).containsEntry("tags", "idioma:es,1.2");
    }

    @Test
    void invalidConfiguredOrRequestTagPlaceholdersFailBeforeNetwork() throws Exception {
        var mvc = downloadMvc("A".repeat(40));
        qbittorrent.getDownload().setTags(List.of("{missing}"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663")).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/torrent/books/2663").contentType("application/json")
                .content("{\"qbittorrent\":{\"tags\":[\"{author}\"]}}"))
                .andExpect(status().isBadRequest());
        assertThat(calls).isEmpty();
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
