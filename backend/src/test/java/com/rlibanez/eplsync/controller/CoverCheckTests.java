package com.rlibanez.eplsync.controller;

import java.util.Objects;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.CoverCheckService;
import com.rlibanez.eplsync.service.CoverProbe;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class CoverCheckTests {
    @Autowired CatalogBookRepository repository;
    @Autowired CoverCheckService service;
    @MockitoBean CoverProbe probe;
    @MockitoBean com.rlibanez.eplsync.service.CoverProbeFactory probeFactory;
    MockMvc mvc;

    @Test void logsStartTenPercentProgressAndSummaryUsingSelectedTotal() {
        for (int id = 1; id <= 25; id++) book(id, "https://example.org/ok.jpg", id <= 5 ? true : null);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CoverCheckService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.check(true, 10, null, 10, true);
            var messages = appender.list.stream().map(value -> Objects.requireNonNull(value).getFormattedMessage()).toList();
            assertThat(messages.getFirst()).contains("Inicio comprobación", "dryRun=true", "total=10", "afterId=10", "size=10");
            var progress = messages.stream().filter(message -> message.startsWith("Progreso")).toList();
            assertThat(progress).hasSize(10);
            for (int i = 1; i <= 10; i++) {
                assertThat(progress.get(i - 1)).contains(i * 10 + "% (" + i + "/10 libros)");
            }
            assertThat(messages.getLast()).contains("Fin comprobación", "comprobados=10", "disponibles=10",
                    "inconcluyentes=0", "actualizados=0", "urlsConsultadas=1", "duraciónMs=");
            appender.list.clear();
            service.check(true, 0, 1L, null, true);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("total=0");
            assertThat(appender.list.getLast().getFormattedMessage()).contains("Fin comprobación", "comprobados=0");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @BeforeEach void setup() {
        when(probeFactory.create(any())).thenReturn(probe);
        repository.deleteAllInBatch();
        mvc = MockMvcBuilders.standaloneSetup(new CoverCheckController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(probe.check("https://example.org/ok.jpg")).thenReturn(new CoverProbe.Result(true, 200, "AVAILABLE"));
        when(probe.check("https://example.org/missing.jpg")).thenReturn(new CoverProbe.Result(false, 404, "NOT_FOUND"));
        when(probe.check("https://example.org/error.jpg")).thenReturn(new CoverProbe.Result(null, 429, "HTTP_ERROR"));
    }

    void book(long id, String url, Boolean available) {
        repository.saveAndFlush(CatalogBook.builder().eplId(id).revision(1.0).author("Autor").title("Libro")
                .coverUrl(url).coverAvailable(available).build());
    }

    @Test void getIsReadOnlyAndPostOnlyWritesConclusiveStatesWithoutDeletingUrls() throws Exception {
        book(1, "https://example.org/ok.jpg", null);
        book(2, "https://example.org/missing.jpg", null);
        book(3, "https://example.org/error.jpg", true);
        book(4, null, null);
        var before = repository.findAll();
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("onlyUnchecked", "false"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.dryRun").value(true)).andExpect(jsonPath("$.checked").value(3))
                .andExpect(jsonPath("$.wouldChange").value(2)).andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.inconclusive").value(1));
        assertThat(repository.findAll()).usingRecursiveComparison().isEqualTo(before);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", false).field("onlyUnchecked", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.updated").value(2));
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isTrue();
        var missing = repository.findById(2L).orElseThrow();
        assertThat(missing.getCoverAvailable()).isFalse();
        assertThat(missing.getCoverUrl()).endsWith("missing.jpg");
        assertThat(missing.getLastModifiedDate()).isNull();
        assertThat(repository.findById(3L).orElseThrow().getCoverAvailable()).isTrue();
    }

    @Test void cursorDoesNotSkipRowsAfterUpdatesAndSharedUrlsAreCheckedOncePerBatch() {
        book(1, "https://example.org/ok.jpg", null);
        book(2, "https://example.org/ok.jpg", null);
        book(3, "https://example.org/missing.jpg", null);
        var first = service.check(false, 0, null, 2, true);
        assertThat(first.nextAfterId()).isEqualTo(2);
        assertThat(first.hasMore()).isTrue();
        verify(probe, times(1)).check("https://example.org/ok.jpg");
        var next = service.check(false, first.nextAfterId(), null, 2, true);
        assertThat(next.items()).extracting(value -> Objects.requireNonNull(value).eplId()).containsExactly(3L);
        assertThat(next.hasMore()).isFalse();
        assertThat(service.check(true, 0, null, 20, true).checked()).isZero();
        assertThat(service.check(true, 0, 2L, 20, false).items())
                .extracting(value -> Objects.requireNonNull(value).eplId()).containsExactly(2L);
    }

    @Test void rejectsInvalidLimitsWithoutNetworkRequests() throws Exception {
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", false).field("afterId", "-1")).andExpect(status().isBadRequest());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("coverAvailable", "invalid")).andExpect(status().isBadRequest());
        verifyNoInteractions(probe);
    }

    @Test void getFiltersFreshResultsNotStoredStatesAndKeepsWholeBatchSummary() throws Exception {
        book(1, "https://example.org/ok.jpg", false);
        book(2, "https://example.org/missing.jpg", true);
        book(3, "https://example.org/error.jpg", null);
        var before = repository.findAll();
        for (boolean available : new boolean[] {true, false}) {
            mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("onlyUnchecked", "false")
                    .field("coverAvailable", Boolean.toString(available)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.checked").value(3))
                    .andExpect(jsonPath("$.available").value(1)).andExpect(jsonPath("$.unavailable").value(1))
                    .andExpect(jsonPath("$.inconclusive").value(1)).andExpect(jsonPath("$.updated").value(0))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].eplId").value(available ? 1 : 2))
                    .andExpect(jsonPath("$.items[0].available").value(available));
        }
        assertThat(repository.findAll()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test void emptyFilteredBatchStillReturnsCursorForFollowingBooks() throws Exception {
        book(1, "https://example.org/ok.jpg", null);
        book(2, "https://example.org/ok.jpg", null);
        book(3, "https://example.org/missing.jpg", null);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("coverAvailable", "false").field("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.checked").value(2)).andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextAfterId").value(2));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("coverAvailable", "false")
                .field("size", "2").field("afterId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eplId").value(3))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test void changedUrlDuringCheckIsNotOverwritten() {
        book(1, "https://example.org/ok.jpg", null);
        when(probe.check("https://example.org/ok.jpg")).thenAnswer(call -> {
            var book = repository.findById(1L).orElseThrow();
            book.setCoverUrl("https://example.org/new.jpg");
            repository.saveAndFlush(book);
            return new CoverProbe.Result(true, 200, "AVAILABLE");
        });
        var result = service.check(false, 0, null, 20, true);
        assertThat(result.updated()).isZero();
        assertThat(result.items().getFirst().reason()).isEqualTo("CONCURRENT_CHANGE");
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isNull();
    }

    @Test void concurrentChecksReturnConflictWhileCatalogRemainsReadable() throws Exception {
        book(1, "https://example.org/ok.jpg", null);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(probe.check(anyString())).thenAnswer(call -> {
            started.countDown();
            release.await();
            return new CoverProbe.Result(true, 200, "AVAILABLE");
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.check(true, 0, null, 20, true));
            try {
                assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true)).andExpect(status().isConflict());
                assertThat(repository.findById(1L)).isPresent();
            } finally {
                release.countDown();
            }
            assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS).checked()).isEqualTo(1);
        }
    }

    @Test void omittedSizeScansWholeCatalogAcrossInternalBatchesAndFiltersOnlyOutput() throws Exception {
        for (int id = 1; id <= 123; id++) {
            book(id, id % 2 == 0 ? "https://example.org/ok.jpg" : "https://example.org/missing.jpg", true);
        }
        book(124, null, null);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("onlyUnchecked", "false").field("coverAvailable", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checked").value(123))
                .andExpect(jsonPath("$.available").value(61)).andExpect(jsonPath("$.unavailable").value(62))
                .andExpect(jsonPath("$.items.length()").value(62)).andExpect(jsonPath("$.items[61].eplId").value(123))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextAfterId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.updated").value(0));
        verify(probe, times(1)).check("https://example.org/ok.jpg");
        verify(probe, times(1)).check("https://example.org/missing.jpg");
        assertThat(repository.findById(123L).orElseThrow().getCoverAvailable()).isTrue();
    }

    @Test void omittedSizePostChecksAllUncheckedAndExplicitSizeCanExceedFifty() throws Exception {
        for (int id = 1; id <= 103; id++) book(id, "https://example.org/ok.jpg", null);
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true).field("size", "75"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checked").value(75))
                .andExpect(jsonPath("$.nextAfterId").value(75)).andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checked").value(103))
                .andExpect(jsonPath("$.updated").value(103)).andExpect(jsonPath("$.hasMore").value(false));
        assertThat(repository.findAll()).allSatisfy(book -> assertThat(book.getCoverAvailable()).isTrue());
        mvc.perform(com.rlibanez.eplsync.api.OperationRequest.operation("/api/catalog/covers/check", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checked").value(0));
    }
}
