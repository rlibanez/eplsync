package com.rlibanez.eplsync.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class BrowserErrorResponseTests {
    @Test void browserNavigationGetsStandaloneLocalizedHtml() throws Exception {
        var request = new MockHttpServletRequest("GET", "/");
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        request.addPreferredLocale(Locale.forLanguageTag("es"));
        var response = new MockHttpServletResponse();
        BrowserErrorResponse.write(request, response, 400, "HTTPS_REQUIRED");
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        assertTrue(response.getContentAsString().contains("Se requiere una conexión segura"));
        assertTrue(response.getContentAsString().contains("HTTPS_REQUIRED"));
        assertFalse(response.getContentAsString().contains("{{"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
    @Test void apiKeepsJsonEvenWhenHtmlIsAccepted() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/auth/status");
        request.setServletPath("/api/auth/status");
        request.addHeader("Accept", "text/html");
        var response = new MockHttpServletResponse();
        BrowserErrorResponse.write(request, response, 400, "HTTPS_REQUIRED");
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"code\":\"HTTPS_REQUIRED\"}", response.getContentAsString());
    }
    @Test void englishPageEscapesDiagnosticCode() throws Exception {
        var request = new MockHttpServletRequest("GET", "/");
        request.addHeader("Accept", "text/html");
        request.addPreferredLocale(Locale.ENGLISH);
        var response = new MockHttpServletResponse();
        BrowserErrorResponse.write(request, response, 403, "<script>");
        assertTrue(response.getContentAsString().contains("Access unavailable"));
        assertFalse(response.getContentAsString().contains("<script>"));
        assertTrue(response.getContentAsString().contains("&lt;script&gt;"));
    }
}
