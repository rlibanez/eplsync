package com.rlibanez.eplsync.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class BrowserErrorResponseTests {
    @ParameterizedTest
    @CsvSource(value={
        "es|es", "en|en", "fr|en", "es-MX|es", "en-GB|en",
        "fr-FR,es-ES;q=0.8,en;q=0.5|es",
        "fr,en;q=0.9,es;q=0.8|en",
        "es;q=0.5,en;q=0.9|en",
        "es;q=0,en;q=0.8|en",
        "fr,de;q=0.8|en", "*|en", "invalid;q=broken|en"
    },delimiter='|')
    void choosesFirstSupportedLanguageForHtmlAndCsrfJson(String preferences,String language) throws Exception {
        var request=new MockHttpServletRequest("GET","/");
        request.addHeader("Accept","text/html");request.addHeader("Accept-Language",preferences);
        var response=new MockHttpServletResponse();
        BrowserErrorResponse.write(request,response,400,"HTTPS_REQUIRED");
        assertTrue(response.getContentAsString().contains("<html lang=\""+language+"\">"));
        assertEquals(language,response.getHeader("Content-Language"));
        assertTrue(response.getContentAsString().contains(language.equals("es")
            ? "Se requiere una conexión segura" : "A secure connection is required"));
        request.setServletPath("/api/settings");
        var json=new MockHttpServletResponse();
        BrowserErrorResponse.write(request,json,403,"CSRF_INVALID");
        assertTrue(json.getContentType().startsWith("application/json"));
        assertEquals(language,json.getHeader("Content-Language"));
        assertTrue(json.getContentAsString().contains(language.equals("es") ? "La sesión ha cambiado" : "The session has changed"));
    }

    @Test void missingHeaderUsesEnglishEvenWithSpanishServerLocale() throws Exception {
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es"));
            var request=new MockHttpServletRequest("GET","/");request.addHeader("Accept","text/html");
            var response=new MockHttpServletResponse();
            BrowserErrorResponse.write(request,response,401,"AUTH_REQUIRED");
            assertTrue(response.getContentAsString().contains("Please sign in"));
            assertEquals("en",response.getHeader("Content-Language"));
        } finally { Locale.setDefault(previous); }
    }
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
