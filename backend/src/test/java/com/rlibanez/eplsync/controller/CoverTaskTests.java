package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.config.CoverCheckProperties;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=create-drop"})
class CoverTaskTests {
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired CoverTaskService tasks;
    @Autowired CatalogBookRepository repository;
    @Autowired CoverCheckProperties defaults;
    @Autowired MaintenanceGate gate;
    @MockitoBean CoverProbeFactory factory;
    @MockitoBean CoverProbe probe;
    MockMvc mvc;

    @BeforeEach void setup() {
        repository.deleteAllInBatch();
        when(factory.create(any())).thenReturn(probe);
        when(probe.check(anyString())).thenReturn(new CoverProbe.Result(false, 404, "NOT_FOUND"));
        mvc = MockMvcBuilders.standaloneSetup(new CoverTaskController(tasks)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    void seed(int size) {
        repository.saveAllAndFlush(java.util.stream.LongStream.rangeClosed(1, size).mapToObj(id ->
                CatalogBook.builder().eplId(id).revision(1.0).title("Libro").author("Autor")
                        .coverUrl("https://example.org/cover.jpg").coverAvailable(true).build()).toList());
    }

    CoverTaskService.Status finished() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tasks.current().state().equals("RUNNING") && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(tasks.current().state()).isEqualTo("COMPLETED");
        return tasks.current();
    }

    @Test void backgroundTaskChecksAllPreviouslyCheckedBooksWithIsolatedOptions() throws Exception {
        long cursor = events.cursor();
        seed(61);
        mvc.perform(post("/api/catalog/covers/task").contentType("application/json").content("""
                {"dryRun":false,"options":{"connectTimeoutMs":1000,"requestTimeoutMs":2000,"batchTimeoutMs":3000,"concurrency":2}}
                """)).andExpect(status().isAccepted());
        var result = finished();
        assertThat(result.checked()).isEqualTo(61);
        assertThat(result.total()).isEqualTo(61);
        var entries = events.after(cursor, 10);
        assertThat(entries).extracting(com.rlibanez.eplsync.events.EventJournal.Entry::outcome)
                .containsExactly(com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED,
                    com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        assertThat(entries.getLast().details()).containsEntry("checked", 61);
        assertThat(result.summary().updated()).isEqualTo(61);
        assertThat(result.summary().items()).isEmpty();
        assertThat(repository.findAll()).allSatisfy(book -> assertThat(book.getCoverAvailable()).isFalse());
        var captured = org.mockito.ArgumentCaptor.forClass(CoverCheckProperties.class);
        verify(factory).create(captured.capture());
        assertThat(captured.getValue().getConcurrency()).isEqualTo(2);
        assertThat(captured.getValue().getRequestTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(defaults.getConcurrency()).isEqualTo(4);
        mvc.perform(get("/api/catalog/covers/task")).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.state").value("COMPLETED"))
                .andExpect(jsonPath("$.task.summary.updated").value(61));
        mvc.perform(get("/api/catalog/covers/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestTimeoutMs").value(3000));
    }

    @Test void dryRunNeverWritesAndPreventsConcurrentTasksAndReset() throws Exception {
        seed(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(probe.check(anyString())).thenAnswer(call -> {
            entered.countDown(); release.await(); return new CoverProbe.Result(false, 404, "NOT_FOUND");
        });
        mvc.perform(post("/api/catalog/covers/task").contentType("application/json").content("{\"dryRun\":true}"))
                .andExpect(status().isAccepted());
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/catalog/covers/task")).andExpect(jsonPath("$.task.state").value("RUNNING"));
            mvc.perform(post("/api/catalog/covers/task").contentType("application/json").content("{\"dryRun\":false}"))
                    .andExpect(status().isConflict());
            assertThat(gate.enter(true)).isFalse();
        } finally { release.countDown(); }
        assertThat(finished().summary().updated()).isZero();
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isTrue();
    }

    @Test void invalidPerRunParametersAreRejectedBeforeStarting() throws Exception {
        mvc.perform(post("/api/catalog/covers/task").contentType("application/json").content("""
                {"dryRun":false,"options":{"connectTimeoutMs":3000,"requestTimeoutMs":1000,"batchTimeoutMs":4000,"concurrency":2}}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(factory);
    }

    @Test void onlyUncheckedExcludesBothPreviouslyAvailableAndUnavailableCovers() throws Exception {
        seed(3);
        var unchecked = repository.findById(1L).orElseThrow();
        unchecked.setCoverAvailable(null);
        repository.saveAndFlush(unchecked);
        var unavailable = repository.findById(3L).orElseThrow();
        unavailable.setCoverAvailable(false);
        repository.saveAndFlush(unavailable);
        mvc.perform(post("/api/catalog/covers/task").contentType("application/json")
                .content("{\"dryRun\":false,\"onlyUnchecked\":true}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.onlyUnchecked").value(true));
        var result = finished();
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.summary().updated()).isEqualTo(1);
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isFalse();
        assertThat(repository.findById(2L).orElseThrow().getCoverAvailable()).isTrue();
        assertThat(repository.findById(3L).orElseThrow().getCoverAvailable()).isFalse();
    }
}
