package com.rlibanez.eplsync.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.HtmlUtils;

/** Standalone error view: it also works when security blocks the frontend assets. */
final class BrowserErrorResponse {
    private static final String TEMPLATE;
    static {
        try (var stream = BrowserErrorResponse.class.getResourceAsStream("/errors/browser.html")) {
            if (stream == null) throw new IllegalStateException("Missing browser error template");
            TEMPLATE = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    static void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String accept = request.getHeader("Accept");
        // API consumers always receive the same machine-readable response, even in a browser.
        if (request.getServletPath().startsWith("/api/") || accept == null || !accept.contains("text/html")) {
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"" + code + "\"}");
            return;
        }
        boolean english = request.getLocale().getLanguage().equals("en");
        String title;
        String message;
        switch (code) {
            case "HTTPS_REQUIRED" -> {
                title = english ? "A secure connection is required" : "Se requiere una conexión segura";
                message = english
                    ? "This installation requires HTTPS. Open EPL Sync using its HTTPS address. If you administer this installation, check the certificate and reverse proxy configuration."
                    : "Esta instalación exige HTTPS. Abre EPL Sync mediante su dirección HTTPS. Si administras esta instalación, comprueba la configuración del certificado y del proxy inverso.";
            }
            case "AUTH_REQUIRED", "SESSION_EXPIRED" -> {
                title = english ? "Please sign in" : "Inicia sesión";
                message = english ? "Your session is missing or has expired. Return to EPL Sync and sign in again."
                    : "No hay una sesión activa o ha caducado. Vuelve a EPL Sync e inicia sesión de nuevo.";
            }
            default -> {
                title = english ? "Access unavailable" : "Acceso no disponible";
                message = english ? "You cannot access this page. Check your account permissions or contact the administrator."
                    : "No puedes acceder a esta página. Comprueba los permisos de tu cuenta o contacta con el administrador.";
            }
        }
        response.setContentType("text/html");
        response.getWriter().write(TEMPLATE.replace("{{lang}}", english ? "en" : "es")
            .replace("{{title}}", HtmlUtils.htmlEscape(title, "UTF-8"))
            .replace("{{message}}", HtmlUtils.htmlEscape(message, "UTF-8"))
            .replace("{{code}}", HtmlUtils.htmlEscape(code, "UTF-8"))
            .replace("{{status}}", Integer.toString(status)));
    }
}
