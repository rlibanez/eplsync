package com.rlibanez.eplsync.events;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=create-drop",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false",
    "server.tomcat.accesslog.enabled=false"})
class EventApiTests {
    @Value("${local.server.port}") int port;
    @Autowired EventJournal journal;
    @Autowired PlatformTransactionManager manager;
    final JsonMapper json = JsonMapper.builder().build();
    @Autowired com.rlibanez.eplsync.security.AccountStore accounts;
    String csrfToken;
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/events" + path); }
    EventJournal.Entry record() {
        return journal.record(EventJournal.Category.TORRENT, "SYNC", EventJournal.Outcome.SUCCEEDED,
            EventContext.Origin.MANUAL, "api-operation", Map.of("checked", 3));
    }
    HttpResponse<String> request(String path, String body) throws Exception {
        var builder = authenticatedRequest(uri(path)).timeout(Duration.ofSeconds(5));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpRequest.Builder authenticatedRequest(URI uri) {
        var builder=HttpRequest.newBuilder(uri);
        if(csrfToken!=null) builder.header("X-CSRF-TOKEN",csrfToken);
        return builder;
    }
    @BeforeEach void clear() throws Exception {
        accounts.clear();
        accounts.initialize("eventadmin","admin@example.org","a permanent event password","a permanent event password");
        URI root=URI.create("http://127.0.0.1:"+port+"/api/auth/");
        csrfToken=json.readTree(http.send(HttpRequest.newBuilder(root.resolve("csrf")).build(),HttpResponse.BodyHandlers.ofString()).body()).path("token").asString();
        var login=http.send(authenticatedRequest(root.resolve("login")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"eventadmin\",\"password\":\"a permanent event password\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        csrfToken=json.readTree(http.send(HttpRequest.newBuilder(root.resolve("csrf")).build(),HttpResponse.BodyHandlers.ofString()).body()).path("token").asString();
        journal.delete(new EventJournal.Filter(null, null, null, null));
    }
    @AfterEach void close() { http.close(); }
    @Test void actionFiltersIndividualEventsAndGroupedOperationsAndCombinesWithCategory() throws Exception {
        journal.record(EventJournal.Category.SECURITY,"USER_CREATE",EventJournal.Outcome.SUCCEEDED,
            EventContext.Origin.MANUAL,"create-one",Map.of("username","alice"));
        journal.record(EventJournal.Category.SECURITY,"USER_UPDATE",EventJournal.Outcome.SUCCEEDED,
            EventContext.Origin.MANUAL,"edit-one",Map.of("username","alice"));
        record();
        for(String path:List.of("?action=USER_CREATE","/operations?action=USER_CREATE&category=SECURITY&origin=MANUAL")) {
            var response=request(path,null);
            assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body());
            assertThat(body.path("total").asInt()).isEqualTo(1);
        }
        var ordered=request("/operations?sort=category,asc&sort=event,asc&size=1",null);
        assertThat(ordered.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ordered.body()).path("items").get(0).path("latest").path("category").asString()).isEqualTo("SECURITY");
        assertThat(request("/operations?sort=category,asc&sort=category,desc",null).statusCode()).isEqualTo(400);
        assertThat(json.readTree(request("/operations?action=USER_CREATE&category=TORRENT",null).body()).path("total").asInt()).isZero();
        assertThat(json.readTree(request("/operations?action=USER_CREATE%27%20OR%201%3D1--",null).body()).path("total").asInt()).isZero();
    }

    @Test void disabledTorrentOperationsRecordReadableFailures() throws Exception {
        for (String path : List.of("/api/torrent/books/32", "/api/torrent/downloads/sync")) {
            var response = http.send(authenticatedRequest(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"dryRun\":false}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.headers().firstValue("X-EPLSync-Operation-Id")).as(path).isPresent();
            assertThat(json.readTree(response.body()).path("details").asString()).isEqualTo("La conexión torrent está deshabilitada");
        }
        var failures = journal.search(new EventJournal.Filter(null, EventJournal.Outcome.FAILED, null, null), 0, 20).items();
        assertThat(failures).hasSize(2);
        assertThat(failures).extracting(value -> Objects.requireNonNull(value).action()).containsExactlyInAnyOrder("SEND_BOOK", "SYNC");
        assertThat(failures).allSatisfy(event ->
                assertThat(event.details().get("reason")).isEqualTo("La conexión torrent está deshabilitada"));
        var grouped = json.readTree(request("/operations?outcome=FAILED", null).body());
        assertThat(grouped.path("total").asInt()).isEqualTo(2);
        for (var operation : grouped.path("items")) {
            assertThat(operation.path("startedAt").isNull()).isFalse();
            assertThat(operation.path("finishedAt").isNull()).isFalse();
            assertThat(operation.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(operation.path("events").size()).isEqualTo(2);
        }
    }

    @Test void disabledSyncPreviewHasItsOwnPersistedOperation() throws Exception {
        var response = http.send(authenticatedRequest(URI.create("http://127.0.0.1:" + port + "/api/torrent/downloads/sync"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"dryRun\":true}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(409);
        var operationId = response.headers().firstValue("X-EPLSync-Operation-Id").orElseThrow();
        var operation = json.readTree(request("/operations?operationId=" + operationId, null).body()).path("items").get(0);
        assertThat(operation.path("latest").path("action").asString()).isEqualTo("SYNC_PREVIEW");
        assertThat(operation.path("latest").path("outcome").asString()).isEqualTo("FAILED");
        assertThat(operation.path("latest").path("details").path("reason").asString()).isEqualTo("La conexión torrent está deshabilitada");
        assertThat(operation.path("events").size()).isEqualTo(2);
    }

    @Test void operationLinkFindsTheExactOperationAndHandlesDeletion() throws Exception {
        var event = record();
        journal.record(EventJournal.Category.CATALOG, "UPDATE", EventJournal.Outcome.STARTED,
                EventContext.Origin.MANUAL, "another-operation", Map.of());
        var response = json.readTree(request("/operations?operationId=" + event.operationId(), null).body());
        assertThat(response.path("total").asInt()).isEqualTo(1);
        assertThat(response.path("items").get(0).path("latest").path("operationId").asString()).isEqualTo(event.operationId());
        assertThat(json.readTree(request("/operations?operationId=missing", null).body()).path("total").asInt()).isZero();
    }

    @Test void endpointsValidateAndReturnPersistedHistoryAndRetention() throws Exception {
        record();
        var result = request("?category=TORRENT&outcome=SUCCEEDED&page=0&size=20", null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("total").asInt()).isEqualTo(1);
        assertThat(request("?from=2026-10-02T00:00:00Z&before=2026-10-01T00:00:00Z", null).statusCode()).isEqualTo(400);
        assertThat(request("?size=201", null).statusCode()).isEqualTo(400);
        assertThat(request("/delete", "{}").statusCode()).isEqualTo(400);
        var policy = json.readTree(request("/retention", null).body());
        assertThat(policy.path("maxCount").asInt()).isEqualTo(10000);
        assertThat(policy.path("maxAgeDays").asInt()).isEqualTo(365);
        assertThat(json.readTree(request("/delete", "{\"confirm\":true}").body()).path("deleted").asInt()).isEqualTo(1);
        assertThat(json.readTree(request("", null).body()).path("total").asInt()).isZero();
    }
    @Test void filtersByOriginTogetherWithCategoryAndOutcome() throws Exception {
        record();
        journal.record(EventJournal.Category.TORRENT, "SYNC", EventJournal.Outcome.SUCCEEDED,
            EventContext.Origin.SCHEDULED, "scheduled", Map.of());
        journal.record(EventJournal.Category.CATALOG, "UPDATE", EventJournal.Outcome.FAILED,
            EventContext.Origin.MANUAL, "failed", Map.of());
        var result = json.readTree(request("?origin=MANUAL&category=TORRENT&outcome=SUCCEEDED", null).body());
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("operationId").asString()).isEqualTo("api-operation");
        assertThat(json.readTree(request("?origin=SYSTEM", null).body()).path("total").asInt()).isZero();
        assertThat(request("?origin=INVALID", null).statusCode()).isEqualTo(400);
        var grouped = request("/operations?origin=MANUAL&category=TORRENT&outcome=SUCCEEDED", null);
        assertThat(grouped.statusCode()).isEqualTo(200);
        assertThat(json.readTree(grouped.body()).path("items").get(0).path("latest").path("operationId").asString()).isEqualTo("api-operation");
        assertThat(request("/operations?snapshot=-1", null).statusCode()).isEqualTo(400);
        assertThat(request("/operations?origin=INVALID", null).statusCode()).isEqualTo(400);
    }
    @Test @Timeout(15) void sseDeliversCommittedEventsAndReplaysFromLastEventId() throws Exception {
        var old = record();
        var response = http.send(authenticatedRequest(uri("/stream")).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        long last;
        try (var input = new BufferedReader(new InputStreamReader(response.body()))) {
            var ready = frame(input, "ready");
            assertThat(ready).contains("id:" + old.id());
            new TransactionTemplate(manager).executeWithoutResult(tx -> { record(); tx.setRollbackOnly(); });
            last = record().id();
            var event = frame(input, "event");
            assertThat(event).contains("id:" + last).contains("\"checked\":3");
        }
        var missed = record();
        var reconnect = http.send(authenticatedRequest(uri("/stream")).header("Last-Event-ID", Long.toString(last)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = new BufferedReader(new InputStreamReader(reconnect.body()))) {
            assertThat(frame(input, "ready")).contains("id:" + last);
            assertThat(frame(input, "event")).contains("id:" + missed.id());
        }
    }
    @Test @Timeout(15) void securityEventsNeverReachNonAdminStreamsOrReplay() throws Exception {
        accounts.policy(new com.rlibanez.eplsync.security.AccountStore.Policy(true,false,30,12));
        accounts.register("streamreader","reader@example.org","a permanent reader password");
        var reader=accounts.users().stream().filter(user -> user.username().equals("streamreader")).findFirst().orElseThrow();
        accounts.update(reader.id(),"USER","ACTIVE",Map.of("EVENTS_MANAGE","ALLOW"),null);
        var login=http.send(authenticatedRequest(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"streamreader\",\"password\":\"a permanent reader password\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        var response=http.send(authenticatedRequest(uri("/stream")).build(),HttpResponse.BodyHandlers.ofInputStream());
        EventJournal.Entry visible;
        try(var input=new BufferedReader(new InputStreamReader(response.body()))) {
            frame(input,"ready");
            journal.record(EventJournal.Category.SECURITY,"USER_DELETE",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,"private-operation",Map.of("username","private-user"));
            visible=record();
            assertThat(frame(input,"event")).contains("id:"+visible.id()).doesNotContain("private-user","SECURITY");
        }
        var replay=http.send(authenticatedRequest(uri("/stream")).header("Last-Event-ID","0").build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var input=new BufferedReader(new InputStreamReader(replay.body()))) {
            frame(input,"ready");
            assertThat(frame(input,"event")).contains("id:"+visible.id()).doesNotContain("private-user","SECURITY");
        }
    }
    @Test @Timeout(15) void fullResetInvalidatesConnectedBrowsersWithoutPersistingAnEvent() throws Exception {
        var old = record();
        var response = http.send(authenticatedRequest(uri("/stream")).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = new BufferedReader(new InputStreamReader(response.body()))) {
            frame(input, "ready");
            new TransactionTemplate(manager).executeWithoutResult(tx -> journal.clearForReset());
            assertThat(frame(input, "database-reset")).contains("\"cursor\":" + old.id());
            assertThat(journal.after(0,100)).isEmpty();
            assertThat(record().id()).isGreaterThan(old.id());
        }
    }
    String frame(BufferedReader input, String name) throws IOException {
        String line;
        StringBuilder frame = new StringBuilder();
        while ((line = input.readLine()) != null) {
            if (line.isEmpty()) {
                if (frame.toString().contains("event:" + name + "\n")) return frame.toString();
                frame.setLength(0);
            } else frame.append(line).append('\n');
        }
        throw new EOFException("SSE closed before " + name);
    }
}
