package com.rlibanez.eplsync.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverProbeTests {
    @Test void usesConfiguredRequestBudget() {
        var properties = new com.rlibanez.eplsync.config.CoverCheckProperties();
        properties.setRequestTimeout(Duration.ofSeconds(9));
        properties.setBatchTimeout(Duration.ofSeconds(10));
        var response = response(200, "image/jpeg", null);
        var probe = new CoverProbe(properties) {
            @Override protected void validate(URI uri) {}
            @Override protected HttpResponse<InputStream> request(URI uri, Duration timeout) {
                assertThat(timeout).isGreaterThan(Duration.ofSeconds(8)).isLessThanOrEqualTo(Duration.ofSeconds(9));
                return response;
            }
        };
        try {
            assertThat(probe.check("https://example.org/cover.jpg").available()).isTrue();
        } finally {
            probe.shutdown();
        }
    }
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response(int status, String type, String location) {
        var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(location == null
                ? Map.of("Content-Type", List.of(type))
                : Map.of("Content-Type", List.of(type), "Location", List.of(location)), (a, b) -> true));
        when(response.body()).thenReturn(spy(new ByteArrayInputStream(new byte[0])));
        return response;
    }

    CoverProbe returning(HttpResponse<InputStream> response) {
        return new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            @Override protected void validate(URI uri) {}
            @Override protected HttpResponse<InputStream> request(URI uri, Duration timeout) { return response; }
        };
    }

    @Test void distinguishesErrorImagesAndPreservesUnknownStatesWithoutDownloadingBodies() throws Exception {
        for (int status : List.of(200, 404, 410, 403, 429, 500)) {
            var response = response(status, "image/png", null);
            var result = returning(response).check("https://example.org/cover.jpg");
            assertThat(result.available()).isEqualTo(status == 200 ? Boolean.TRUE
                    : status == 404 || status == 410 ? Boolean.FALSE : null);
            assertThat(result.httpStatus()).isEqualTo(status);
            verify(response.body()).close();
            verify(response.body(), never()).read(any(byte[].class));
        }
        assertThat(returning(response(200, "text/html", null)).check("https://example.org/x").available()).isNull();
    }

    @Test void followsRelativeRedirectsAndRevalidatesEveryDestination() {
        var first = response(302, "text/plain", "/missing.jpg");
        var last = response(404, "image/png", null);
        var visited = new java.util.ArrayList<URI>();
        var probe = new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            @Override protected void validate(URI uri) { visited.add(uri); }
            @Override protected HttpResponse<InputStream> request(URI uri, Duration timeout) {
                return uri.getPath().equals("/old.jpg") ? first : last;
            }
        };
        assertThat(probe.check("https://example.org/old.jpg").available()).isFalse();
        assertThat(visited).containsExactly(URI.create("https://example.org/old.jpg"), URI.create("https://example.org/missing.jpg"));
    }

    @Test void loopsAndTimeoutsAreInconclusive() {
        assertThat(returning(response(302, "text/plain", "/loop")).check("https://example.org/loop").reason())
                .isEqualTo("TOO_MANY_REDIRECTS");
        var probe = new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            @Override protected void validate(URI uri) {}
            @Override protected HttpResponse<InputStream> request(URI uri, Duration timeout) throws java.io.IOException {
                throw new java.net.http.HttpTimeoutException("timeout");
            }
        };
        assertThat(probe.check("https://example.org/x").available()).isNull();
        assertThat(probe.check("https://example.org/x").reason()).isEqualTo("TIMEOUT");
    }

    @Test void rejectsNonHttpCredentialsAndLocalDestinationsBeforeRequesting() {
        var probe = new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties());
        for (String url : List.of("file:///etc/passwd", "http://127.0.0.1/x", "http://[::1]/x",
                "http://169.254.169.254/x", "http://10.0.0.1/x", "https://user:pass@example.org/x")) {
            assertThat(probe.check(url).reason()).isEqualTo("INVALID_URL");
        }
    }
}
