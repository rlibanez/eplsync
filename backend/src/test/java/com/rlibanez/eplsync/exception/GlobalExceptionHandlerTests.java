package com.rlibanez.eplsync.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.UncategorizedSQLException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTests {
    private final MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/security/users");
    @Test void unexpectedErrorsExposeOnlyAPublicMessageAndMatchingIncidentIdInLogs() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            var exception=new UncategorizedSQLException("insert /private/database", "INSERT INTO users(password_hash) VALUES('private-secret')",
                new SQLException("SQLITE_CONSTRAINT_UNIQUE: users.username_normalized"));
            var response=new GlobalExceptionHandler().handleGenericException(exception,request);
            var body=response.getBody();
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(body.details()).startsWith("Ha ocurrido un error inesperado. Referencia: ");
            assertThat(java.util.UUID.fromString(body.incidentId()).toString()).isEqualTo(body.incidentId());
            assertThat(response.getHeaders().getFirst("X-Incident-ID")).isEqualTo(body.incidentId());
            assertThat(body.toString()).doesNotContain("INSERT","SQLITE","/private/database","private-secret");
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains(body.incidentId());
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(UncategorizedSQLException.class.getName());
            });
            assertThat(new GlobalExceptionHandler().handleGenericException(exception,request).getBody().incidentId()).isNotEqualTo(body.incidentId());
        } finally { logger.detachAppender(appender);appender.stop(); }
    }
    @Test void libraryValidationErrorsAreHiddenButDeliberateValidationMessagesRemainUseful() {
        var handler=new GlobalExceptionHandler();
        var unsafe=handler.handleIllegalArgumentException(new IllegalArgumentException("SQL /private/file token=private-secret"),request);
        assertThat(unsafe.getStatusCode().value()).isEqualTo(400);
        assertThat(unsafe.getBody().details()).doesNotContain("SQL","/private/file","private-secret");
        var safe=handler.handleIllegalArgumentException(new UserInputException("El nombre de usuario no está disponible"),request);
        assertThat(safe.getBody().details()).isEqualTo("El nombre de usuario no está disponible");
        assertThat(safe.getBody().incidentId()).isNull();
    }
    @Test void importTechnicalMessagesAreHiddenWithoutLosingDownloadRejectionReasons() {
        var handler=new GlobalExceptionHandler();
        var unsafe=handler.handleCatalogImport(new CatalogImportException("/private/input.csv token=private-secret",new java.io.IOException("internal")),request);
        assertThat(unsafe.getBody().details()).startsWith("No se pudo importar el catálogo. Referencia: ").doesNotContain("private-secret","/private/input.csv","internal");
        var rejected=new CatalogDownloadException("El ZIP descargado supera el tamaño máximo permitido (128 MiB)");
        var safe=handler.handleCatalogImport(new CatalogImportException("internal wrapper",rejected),request);
        assertThat(safe.getBody().details()).isEqualTo(rejected.getMessage());
    }
    @org.springframework.web.bind.annotation.RestController
    static class BrokenController {
        @org.springframework.web.bind.annotation.GetMapping("/broken") String broken() { throw new IllegalStateException("SQL password=private-secret /private/path"); }
    }
    @Test void serializedUnexpectedErrorDoesNotContainTechnicalDetails() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BrokenController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/broken")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.incidentId").isNotEmpty())
            .andExpect(header().exists("X-Incident-ID"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-secret"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/private/path"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
    }
}
