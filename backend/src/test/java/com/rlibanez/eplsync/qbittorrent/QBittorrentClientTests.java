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

    @Test void deletionUsesRemoteIdAndExplicitFilePolicy() {
        var qbit = client();
        String remoteId = "B".repeat(40);
        qbit.deleteTorrent(remoteId, false);
        var deletion = calls.stream().filter(call -> call.path().endsWith("/torrents/delete")).findFirst().orElseThrow();
        assertThat(deletion.method()).isEqualTo("POST");
        assertThat(deletion.body()).isEqualTo("hashes=" + remoteId + "&deleteFiles=false");
        assertThat(deletion.authorization()).isEqualTo("Bearer test-key");
        qbit.deleteTorrent(remoteId, true);
        assertThat(calls.getLast().body()).isEqualTo("hashes=" + remoteId + "&deleteFiles=true");
        assertThatThrownBy(() -> qbit.deleteTorrent("all", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void readsHybridHashesAndRejectsMalformedAliases() {
        torrentInfo = """
                [{"hash":"%s","infohash_v1":"%s","infohash_v2":"%s",
                  "state":"uploading","progress":1,"amount_left":0}]
                """.formatted("b".repeat(40), "a".repeat(40), "b".repeat(64));
        var qbit = client();
        var result = qbit.listTorrents();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().aliases()).containsExactlyInAnyOrder(
                "A".repeat(40), "B".repeat(40), "B".repeat(64));
        torrentInfo = torrentInfo.replace("a".repeat(40), "invalid");
        assertThatThrownBy(qbit::listTorrents).isInstanceOf(QBittorrentConnectionException.class);
    }

    @Test void readsFullSnapshotAndMapsCompletionWithoutTreatingStoppedOrCheckingAsComplete() {
        String hash = "A".repeat(40);
        torrentInfo = "[{\"hash\":\"" + hash + "\",\"state\":\"stoppedUP\",\"progress\":1,\"amount_left\":0,\"completion_on\":1700000000}]";
        var qbit = client();
        var result = qbit.listTorrents();
        assertThat(result.getFirst().status()).isEqualTo(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.DOWNLOADED);
        assertThat(result.getFirst().completedAt()).isEqualTo(java.time.Instant.ofEpochSecond(1700000000));
        torrentInfo = torrentInfo.replace("stoppedUP", "checkingUP");
        assertThat(qbit.listTorrents().getFirst().status()).isEqualTo(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.CHECKING);
        torrentInfo = torrentInfo.replace("checkingUP", "missingFiles");
        assertThat(qbit.listTorrents().getFirst().status()).isEqualTo(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.ERROR);
        torrentInfo = torrentInfo.replace("missingFiles", "stoppedDL").replace("\"progress\":1", "\"progress\":0.5");
        assertThat(qbit.listTorrents().getFirst().status()).isEqualTo(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.PAUSED);
        torrentInfo = "[{\"hash\":\"" + hash + "\"}]";
        assertThatThrownBy(qbit::listTorrents).isInstanceOf(QBittorrentConnectionException.class);
    }

    private com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService tracking() {
        var tracker = org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.class);
        org.mockito.Mockito.when(tracker.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                });
        return tracker;
    }

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

    private com.rlibanez.eplsync.service.TorrentDownloadService downloadService(String links) {
        var repository = org.mockito.Mockito.mock(com.rlibanez.eplsync.repository.CatalogBookRepository.class);
        var book = new com.rlibanez.eplsync.model.CatalogBook();
        book.setEplId(2663L);
        book.setTitle("Jane Eyre & más");
        book.setAuthor("Bronte, Charlotte");
        book.setRevision(1.2);
        book.setLanguage(com.rlibanez.eplsync.model.enums.Language.ESPANOL);
        book.setLinks(links);
        org.mockito.Mockito.when(repository.findById(2663L)).thenReturn(java.util.Optional.of(book));
        // Event persistence is covered separately; execute the supplied operation here.
        var events = org.mockito.Mockito.mock(com.rlibanez.eplsync.events.EventJournal.class);
        org.mockito.Mockito.when(events.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(3)).get());
        var service = new com.rlibanez.eplsync.service.TorrentDownloadService(repository, properties,
                new com.rlibanez.eplsync.torrent.MagnetLinkBuilder(properties),
                new com.rlibanez.eplsync.torrent.TorrentNameResolver(),
                new TorrentClientService(properties, List.of(client()), tracking()), events);
        return service;
    }

    private java.util.Map<String, String> addedFields() {
        var call = calls.stream().filter(c -> c.path().endsWith("/torrents/add")).findFirst().orElseThrow();
        return java.util.Arrays.stream(call.body().split("&")).map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair ->
                        java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private com.rlibanez.eplsync.dto.TorrentDownloadRequest options(String json) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, com.rlibanez.eplsync.dto.TorrentDownloadRequest.class);
    }
    @Test void downloadUsesDefaultsAndSendsOnlyOneMagnet() {
        properties.getRename().setPattern("{author} - {title} [{eplId}] (r{revision})");
        downloadService("A".repeat(40)).download(2663L, null);
        assertThat(addedFields()).containsEntry("category", "Libros").containsEntry("tags", "EPLSync,es")
            .containsEntry("stopped", "false").containsEntry("autoTMM", "true")
            .containsEntry("rename", "Bronte, Charlotte - Jane Eyre & más [2663] (r1.2)").doesNotContainKey("savepath");
        assertThat(calls.stream().filter(c -> c.method().equals("POST")).count()).isEqualTo(1);
    }
    @Test void downloadOverridesDefaultsAndSupportsSessionFallback() {
        keyStatus = 403;
        downloadService("A".repeat(40)).download(2663L, options("""
            {"start":false,"savePath":"/downloads/libros","rename":{"enabled":false},
            "qbittorrent":{"category":"Personal","tags":[],"autoManagement":false}}
            """));
        assertThat(addedFields()).containsEntry("category", "Personal").containsEntry("tags", "")
            .containsEntry("savepath", "/downloads/libros").containsEntry("stopped", "true")
            .containsEntry("autoTMM", "false").doesNotContainKey("rename");
        assertThat(calls.getLast().cookie()).contains("session1");
        assertThat(calls.getLast().authorization()).isNull();
    }
    @Test void existingTorrentDoesNotWriteOrChangeOptions() {
        torrentInfo = "[{\"hash\":\"" + "a".repeat(40) + "\"}]";
        assertThat(downloadService("A".repeat(40)).download(2663L,null).status().name()).isEqualTo("ALREADY_EXISTS");
        assertThat(calls).noneMatch(c -> c.method().equals("POST"));
    }
    @Test void multipleHashesRequireSelectionAndRejectUnrelatedHash() {
        var service = downloadService("A".repeat(40) + "," + "B".repeat(40));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,null)).hasMessageContaining("varios torrents");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,options("{\"hash\":\""+"C".repeat(40)+"\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
        service.download(2663L,options("{\"hash\":\""+"b".repeat(40)+"\"}"));
        assertThat(addedFields().get("urls")).contains("urn:btih:"+"B".repeat(40));
    }
    @Test void rejectsConflictingDestinationAndMissingCategoryBeforeWriting() {
        var service = downloadService("A".repeat(40));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,options("{\"savePath\":\"/downloads\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty(); categories = "{}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,null)).isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        assertThat(calls).noneMatch(c -> c.method().equals("POST"));
    }
    @Test void remoteAddFailureIsNotRetried() {
        addStatus=500;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> downloadService("A".repeat(40)).download(2663L,null)).isInstanceOf(com.rlibanez.eplsync.exception.TorrentConnectionException.class);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/add")).count()).isEqualTo(1);
    }
    @Test void downloadValidatesMissingBookEmptyLinksAndDisabledIntegration() {
        var service = downloadService("");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(999L,null)).hasMessageContaining("no existe");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,null)).hasMessageContaining("hashes torrent");
        properties.setEnabled(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,null)).hasMessageContaining("deshabilitada");
        assertThat(calls).isEmpty();
    }
    @Test void downloadRejectsInvalidRenameAndTagsWithoutNetwork() {
        var service = downloadService("A".repeat(40));
        for(String json : List.of("{\"rename\":{\"pattern\":\"{notAField}\"}}", "{\"qbittorrent\":{\"tags\":[\"one,two\"]}}"))
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,options(json))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
    @Test void requestTagPlaceholdersReplaceDefaultsAndResolveBeforeSending() {
        downloadService("A".repeat(40)).download(2663L,options("""
            {"qbittorrent":{"tags":["idioma:{language}","{collection}","idioma:es","{revision}"]}}
            """));
        assertThat(addedFields()).containsEntry("tags", "idioma:es,1.2");
    }
    @Test void invalidConfiguredOrRequestTagPlaceholdersFailBeforeNetwork() {
        var service = downloadService("A".repeat(40)); qbittorrent.getDownload().setTags(List.of("{missing}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,null)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.download(2663L,options("{\"qbittorrent\":{\"tags\":[\"{author}\"]}}"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void snapshotRetainsQbittorrentDefaultsWhenConfigurationChanges() {
        var book = com.rlibanez.eplsync.model.CatalogBook.builder()
                .eplId(1L).language(com.rlibanez.eplsync.model.enums.Language.INGLES).build();
        var qbit = client();
        var command = qbit.withDefaults(new com.rlibanez.eplsync.torrent.TorrentDownload(
                "A".repeat(40), "magnet:?xt=urn:btih:" + "A".repeat(40), false, null, "Frozen name", null, book));
        qbittorrent.getDownload().setCategory("Changed");
        qbittorrent.getDownload().setTags(List.of("Changed"));
        qbittorrent.getDownload().setAutoManagement(false);
        assertThat(qbit.addTorrent(command)).isEqualTo(com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED);
        assertThat(addedFields()).containsEntry("category", "Libros").containsEntry("tags", "EPLSync,en")
                .containsEntry("autoTMM", "true").containsEntry("stopped", "true").containsEntry("rename", "Frozen name");
    }

    private com.rlibanez.eplsync.torrent.TorrentDownload command(String hash) {
        return new com.rlibanez.eplsync.torrent.TorrentDownload(hash,
                "magnet:?xt=urn:btih:" + hash, true, null, "Test", null,
                com.rlibanez.eplsync.model.CatalogBook.builder().language(com.rlibanez.eplsync.model.enums.Language.INGLES).build());
    }

    @Test
    void bulkContextReusesCategoriesWhileExplicitConnectionCheckIsFresh() {
        var qbit = client();
        var context = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
        qbit.addTorrent(command("A".repeat(40)), context);
        assertThat(calls).hasSize(5);
        calls.clear();
        qbit.addTorrent(command("B".repeat(40)), context);
        assertThat(calls).extracting(Call::path).containsExactly("/qbit/api/v2/torrents/add");
        calls.clear();
        qbit.checkConnection();
        assertThat(calls).extracting(Call::path).containsExactly("/qbit/api/v2/app/version", "/qbit/api/v2/app/webapiVersion");
    }

    @Test
    void hybridHashesAreRecognizedAndSnapshotIsScopedToSubmission() {
        var qbit = client();
        String v1 = "a".repeat(40), v2 = "b".repeat(64);
        torrentInfo = "[{\"hash\":\"" + "b".repeat(40) + "\",\"infohash_v1\":\"" + v1
                + "\",\"infohash_v2\":\"" + v2 + "\"}]";
        var context = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
        assertThat(qbit.addTorrent(command(v1.toUpperCase()), context).name()).isEqualTo("ALREADY_EXISTS");
        assertThat(qbit.addTorrent(command(v2), context).name()).isEqualTo("ALREADY_EXISTS");
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/info")).count()).isEqualTo(1);
        assertThat(calls).noneMatch(c -> c.path().endsWith("/torrents/add"));
        torrentInfo = "[]";
        assertThat(qbit.addTorrent(command(v1)).name()).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptedHashesAreRememberedAndFailuresReloadSnapshot() {
        var qbit = client();
        var context = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
        String hash = "A".repeat(40);
        qbit.addTorrent(command(hash), context);
        calls.clear();
        assertThat(qbit.addTorrent(command(hash), context).name()).isEqualTo("ALREADY_EXISTS");
        assertThat(calls).isEmpty();
        addStatus = 409;
        assertThatThrownBy(() -> qbit.addTorrent(command("C".repeat(40)), context)).isInstanceOf(RuntimeException.class);
        torrentInfo = "[{\"hash\":\"" + "B".repeat(40) + "\",\"infohash_v1\":\"" + "C".repeat(40) + "\"}]";
        calls.clear();
        assertThat(qbit.addTorrent(command("C".repeat(40)), context).name()).isEqualTo("ALREADY_EXISTS");
        assertThat(calls).extracting(Call::path).containsExactly("/qbit/api/v2/torrents/info");
    }

    @Test
    void expiredSessionRenewsOnReadAndDoesNotDuplicateAdd() {
        qbittorrent.getAuth().setMode(AuthMode.SESSION);
        var qbit = client();
        qbit.addTorrent(command("A".repeat(40)));
        calls.clear(); cookieValue = "renewed";
        qbit.addTorrent(command("B".repeat(40)));
        assertThat(calls.stream().filter(c -> c.path().endsWith("/auth/login")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/add")).count()).isEqualTo(1);
        assertThat(calls.getLast().cookie()).contains("renewed");
    }

    @Test
    void individualRequestsReloadCategoriesAndFailedPostIsNeverRetried() {
        var qbit = client();
        qbit.addTorrent(command("A".repeat(40)));
        calls.clear();
        qbit.addTorrent(command("B".repeat(40)));
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/categories")).count()).isEqualTo(1);
        assertThat(calls).noneMatch(c -> c.path().contains("/app/"));
        calls.clear(); addStatus = 403;
        assertThatThrownBy(() -> qbit.addTorrent(command("C".repeat(40)))).isInstanceOf(QBittorrentConnectionException.class);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/add")).count()).isEqualTo(1);
        calls.clear(); addStatus = 200;
        qbit.addTorrent(command("C".repeat(40)));
        assertThat(calls).anyMatch(c -> c.path().endsWith("/app/version"));
    }

    @Test
    void concurrentAddsShareInitialAuthenticationAndCategoryLookup() {
        var qbit = client();
        var context = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
        var futures = java.util.stream.IntStream.range(1, 4).mapToObj(n ->
                java.util.concurrent.CompletableFuture.runAsync(() -> qbit.addTorrent(command(String.format("%040X", n)), context)))
                .toArray(java.util.concurrent.CompletableFuture[]::new);
        java.util.concurrent.CompletableFuture.allOf(futures).join();
        assertThat(calls.stream().filter(c -> c.path().endsWith("/app/version")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/app/webapiVersion")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/categories")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/add")).count()).isEqualTo(3);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/info")).count()).isEqualTo(1);
    }

    @Test
    void detailedHttpLogsNeverIncludeCredentialsCookiesOrMagnetBody() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QBittorrentClient.class);
        var previous = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            client().addTorrent(command("A".repeat(40)));
            String output = appender.list.stream().map(event -> event.getFormattedMessage())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(output).contains("HTTP 200", "/api/v2/torrents/add")
                    .doesNotContain("test-key", "session1", "magnet:", " p&+=ss ", "Authorization", "Cookie");
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }

    @Test
    void revokedCachedKeyFallsBackOnReadAndReusesSessionForFollowingAdds() {
        var qbit = client();
        qbit.addTorrent(command("A".repeat(40)));
        keyStatus = 403; calls.clear();
        qbit.addTorrent(command("B".repeat(40)));
        assertThat(calls.stream().filter(c -> c.path().endsWith("/auth/login")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/torrents/add")).count()).isEqualTo(1);
        assertThat(calls.getLast().authorization()).isNull();
        calls.clear();
        qbit.addTorrent(command("C".repeat(40)));
        assertThat(calls).extracting(Call::path).containsExactly("/qbit/api/v2/torrents/info", "/qbit/api/v2/torrents/categories", "/qbit/api/v2/torrents/add");
        assertThat(calls).allMatch(c -> c.authorization() == null && c.cookie().contains("session1"));
    }

    @Test
    void separateSubmissionContextsNeverShareCategoryLists() {
        var qbit = client();
        var first = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
        qbit.addTorrent(command("A".repeat(40)), first);
        categories = "{}"; calls.clear();
        // El mismo trabajo conserva la lista obtenida inicialmente.
        qbit.addTorrent(command("B".repeat(40)), first);
        assertThat(calls).noneMatch(c -> c.path().endsWith("/categories"));
        calls.clear();
        var second = new com.rlibanez.eplsync.torrent.TorrentSubmissionContext();
        assertThatThrownBy(() -> qbit.addTorrent(command("C".repeat(40)), second))
                .isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        assertThat(calls.stream().filter(c -> c.path().endsWith("/categories")).count()).isEqualTo(1);
        assertThat(calls).noneMatch(c -> c.path().endsWith("/add"));
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
        var mvc = MockMvcBuilders.standaloneSetup(new TorrentClientController(new TorrentClientService(properties, List.of(client()), tracking())))
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
                new TorrentClientService(properties, List.of(client()), tracking()));
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
                new TorrentClientService(properties, List.of(qbit), tracking())))
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
