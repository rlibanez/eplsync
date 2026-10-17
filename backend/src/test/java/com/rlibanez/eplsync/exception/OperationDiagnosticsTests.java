package com.rlibanez.eplsync.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OperationDiagnosticsTests {
    @Test void retainsTechnicalChainWithoutSecretsOrRemoteBodies() {
        var cause = new java.net.ConnectException("https://user:password@example.test token=secret Cookie=session");
        var failure = new IllegalStateException("Authorization: Bearer secret\nSQL credentials", cause);
        assertThat(OperationDiagnostics.technical(failure))
            .contains("IllegalStateException", "ConnectException", "OperationDiagnosticsTests")
            .doesNotContain("password", "secret", "session", "Authorization", "example.test");
        assertThat(OperationDiagnostics.reason(failure,"fallback")).contains("No se pudo conectar").doesNotContain("secret");
    }
    @Test void retainsHttpStatusWithoutResponseBody() {
        var failure = new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"token=secret");
        assertThat(OperationDiagnostics.technical(failure)).contains("HTTP 401").doesNotContain("secret");
    }
    @Test void classifiesFileErrorsWithoutDisclosingPaths() {
        var failure = new java.nio.file.AccessDeniedException("/private/password");
        assertThat(OperationDiagnostics.reason(failure,"fallback")).contains("permisos").doesNotContain("/private");
    }
}
