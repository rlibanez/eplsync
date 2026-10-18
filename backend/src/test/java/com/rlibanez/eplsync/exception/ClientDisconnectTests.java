package com.rlibanez.eplsync.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.util.stream.Stream;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.DatabindException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClientDisconnectTests {
    final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/torrent/downloads");
    final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Level previous;

    @BeforeEach void capture() {
        previous = logger.getLevel(); logger.setLevel(Level.DEBUG);
        logs.start(); logger.addAppender(logs);
    }
    @AfterEach void restore() { logger.detachAppender(logs); logs.stop(); logger.setLevel(previous); }

    static Stream<Throwable> disconnections() {
        return Stream.of(
            new ClientAbortException(new IOException("Broken pipe")),
            new AsyncRequestNotUsableException("ServletOutputStream failed", new ClientAbortException(new IOException("Broken pipe"))),
            new IOException("Broken pipe"),
            new SocketException("Connection reset"),
            new IOException("Connection reset by peer"));
    }
    static HttpMessageNotWritableException jsonFailure(Throwable cause) {
        return new HttpMessageNotWritableException("Could not write JSON", DatabindException.wrapWithPath(cause, new Object(), "completedAt"));
    }
    @ParameterizedTest @MethodSource("disconnections")
    void nestedJsonTransportFailuresAreDebugWithoutAnIncidentOrAnotherResponse(Throwable cause) {
        assertThat(handler.handleUnwritableResponse(jsonFailure(cause), request)).isNull();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).startsWith("Conexión HTTP cerrada");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
    static Stream<Throwable> genuineFailures() {
        return Stream.of(new IllegalStateException("Cannot serialize private-secret"),
            new IOException("Disk full"), new EOFException("Unexpected end of EPUB"),
            new IllegalArgumentException("Invalid value: Broken pipe"));
    }
    @ParameterizedTest @MethodSource("genuineFailures")
    void genuineJsonFailuresKeepErrorAndTheirOriginalTechnicalCause(Throwable cause) {
        var failure = jsonFailure(cause);
        var response = handler.handleUnwritableResponse(failure, request);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getFirst("X-Incident-ID")).isNotBlank();
        assertThat(response.getBody().details()).doesNotContain("private-secret", "Disk full", "EPUB", "Broken pipe");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(HttpMessageNotWritableException.class.getName());
            assertThat(event.getFormattedMessage()).contains(response.getBody().incidentId());
        });
    }
    @Test void textOnTheJsonWrapperAloneIsNotEvidenceOfADisconnection() {
        var response = handler.handleUnwritableResponse(new HttpMessageNotWritableException("Cannot serialize value 'Broken pipe'"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }
    @Test void directTransferDisconnectionsDoNotWriteJsonEvenBeforeResponseCommit() {
        var response = new MockHttpServletResponse();
        assertThat(handler.handleTransferFailure(new SocketException("Connection reset"), request, response)).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("X-Incident-ID")).isNull();
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }
    @Test void unrelatedServerFailuresWithTheSameWordsStillCreateAnErrorIncident() {
        var response = handler.handleGenericException(new org.springframework.dao.DataAccessResourceFailureException("Database unavailable", new IOException("Broken pipe")), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }
    @RestController static class Controller {
        @GetMapping("/aborted-json") Object aborted() { throw jsonFailure(new AsyncRequestNotUsableException("Output unavailable", new ClientAbortException(new IOException("Broken pipe")))); }
        @GetMapping("/invalid-json") Object invalid() { return new InvalidJson(); }
    }
    public static class InvalidJson { public String getValue() { throw new IllegalStateException("Cannot serialize private-secret"); } }

    @Test void mvcHandlesTheProductionExceptionChainWithoutWritingAnErrorBody() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Controller()).setControllerAdvice(handler).build();
        mvc.perform(get("/aborted-json")).andExpect(content().string(""))
            .andExpect(header().doesNotExist("X-Incident-ID"));
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }
    @Test void mvcLogsAnActualJacksonSerializationFailureAsError() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Controller()).setControllerAdvice(handler).build();
        mvc.perform(get("/invalid-json")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.incidentId").isNotEmpty())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-secret"))));
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }
}
