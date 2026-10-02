package com.rlibanez.eplsync.events;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=create-drop",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false",
    "server.tomcat.accesslog.enabled=false"})
class EventApiTests {
    @Value("${local.server.port}") int port;
    @Autowired EventJournal journal;
    @Autowired PlatformTransactionManager manager;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/events" + path); }
    EventJournal.Entry record() {
        return journal.record(EventJournal.Category.TORRENT, "SYNC", EventJournal.Outcome.SUCCEEDED,
            EventContext.Origin.MANUAL, "api-operation", Map.of("checked", 3));
    }
    HttpResponse<String> request(String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @BeforeEach void clear() { journal.delete(new EventJournal.Filter(null, null, null, null)); }
    @AfterEach void close() { http.close(); }
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
        assertThat(result.path("items").get(0).path("operationId").asText()).isEqualTo("api-operation");
        assertThat(json.readTree(request("?origin=SYSTEM", null).body()).path("total").asInt()).isZero();
        assertThat(request("?origin=INVALID", null).statusCode()).isEqualTo(400);
        var grouped = request("/operations?origin=MANUAL&category=TORRENT&outcome=SUCCEEDED", null);
        assertThat(grouped.statusCode()).isEqualTo(200);
        assertThat(json.readTree(grouped.body()).path("items").get(0).path("latest").path("operationId").asText()).isEqualTo("api-operation");
        assertThat(request("/operations?snapshot=-1", null).statusCode()).isEqualTo(400);
        assertThat(request("/operations?origin=INVALID", null).statusCode()).isEqualTo(400);
    }
    @Test @Timeout(15) void sseDeliversCommittedEventsAndReplaysFromLastEventId() throws Exception {
        var old = record();
        var response = http.send(HttpRequest.newBuilder(uri("/stream")).build(), HttpResponse.BodyHandlers.ofInputStream());
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
        var reconnect = http.send(HttpRequest.newBuilder(uri("/stream")).header("Last-Event-ID", Long.toString(last)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = new BufferedReader(new InputStreamReader(reconnect.body()))) {
            assertThat(frame(input, "ready")).contains("id:" + last);
            assertThat(frame(input, "event")).contains("id:" + missed.id());
        }
    }
    @Test @Timeout(15) void fullResetInvalidatesConnectedBrowsersWithoutPersistingAnEvent() throws Exception {
        var old = record();
        var response = http.send(HttpRequest.newBuilder(uri("/stream")).build(), HttpResponse.BodyHandlers.ofInputStream());
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
