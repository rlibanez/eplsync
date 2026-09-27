package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.torrent.TorrentClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties.AuthMode;
import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import jakarta.annotation.PreDestroy;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import static com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.*;

@Service
@ConditionalOnProperty(prefix = "eplsync.torrent", name = "client", havingValue = "qbittorrent", matchIfMissing = true)
public class QBittorrentClient implements TorrentClient {
    private final TorrentProperties properties;
    private final QBittorrentProperties qbittorrent;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient apiKeyClient;
    private final HttpClient sessionClient;

    public QBittorrentClient(TorrentProperties properties, QBittorrentProperties qbittorrent) {
        this.properties = properties;
        this.qbittorrent = qbittorrent;
        // Sin conexiones al arrancar ni clientes HTTP cuando la integración está deshabilitada.
        apiKeyClient = properties.isEnabled() ? newClient().build() : null;
        sessionClient = properties.isEnabled() ? newClient().cookieHandler(cookies).build() : null;
    }

    @Override
    public String type() { return "qbittorrent"; }

    private HttpClient.Builder newClient() {
        return HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
    }

    /** Serializa la renovación de sesión y solo realiza lecturas y login remoto. */
    public synchronized TorrentConnectionStatus checkConnection() {
        if (!properties.isEnabled()) return new TorrentConnectionStatus(false, false, type(), null, null, null);
        try {
            qbittorrent.validate();
        } catch (IllegalArgumentException ex) {
            throw new TorrentOperationException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
        var auth = qbittorrent.getAuth();
        if (auth.getMode() == AuthMode.SESSION) return checkSession();
        if (auth.getMode() == AuthMode.API_KEY) return readVersions(AuthMode.API_KEY);
        if (auth.hasApiKey()) {
            try {
                return readVersions(AuthMode.API_KEY);
            } catch (QBittorrentConnectionException ex) {
                if (ex.getReason() != AUTHENTICATION || !auth.hasSessionCredentials()) throw ex;
            }
        }
        return checkSession();
    }

    @Override
    public synchronized void renameTorrent(String hash, String name) {
        if (!properties.isEnabled() || !properties.getRename().isEnabled()) {
            throw new TorrentOperationException(HttpStatus.CONFLICT, "El renombrado torrent está deshabilitado");
        }
        if (hash == null || !hash.matches("(?i)[0-9a-f]{40}") || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Se requiere un hash válido y un nombre de torrent no vacío");
        }
        // Elegir/autenticar con lecturas antes de la escritura. El POST no se reintenta.
        var connection = checkConnection();
        boolean useApiKey = connection.authMode().equals("api-key");
        var builder = request("torrents/rename").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("hash=" + encode(hash) + "&name=" + encode(name)));
        if (useApiKey) builder.header("Authorization", "Bearer " + qbittorrent.getAuth().getApiKey());
        var response = send(useApiKey ? apiKeyClient : sessionClient, builder.build());
        if (response.statusCode() == 404) {
            throw new TorrentOperationException(HttpStatus.NOT_FOUND, "El torrent no existe en qBittorrent");
        }
        if (response.statusCode() == 409) {
            throw new TorrentOperationException(HttpStatus.CONFLICT, "qBittorrent ha rechazado el nombre del torrent");
        }
        checkStatus(response);
        if ((response.statusCode() != 200 && response.statusCode() != 204) || !response.body().isBlank()) {
            throw new QBittorrentConnectionException(UPSTREAM);
        }
    }

    private TorrentConnectionStatus checkSession() {
        if (!cookies.getCookieStore().getCookies().isEmpty()) {
            try {
                return readVersions(AuthMode.SESSION);
            } catch (QBittorrentConnectionException ex) {
                if (ex.getReason() != AUTHENTICATION) throw ex;
            }
        }
        // Como máximo un login por comprobación, incluso con credenciales incorrectas.
        cookies.getCookieStore().removeAll();
        login();
        return readVersions(AuthMode.SESSION);
    }

    private void login() {
        var auth = qbittorrent.getAuth();
        String body = "username=" + encode(auth.getUsername()) + "&password=" + encode(auth.getPassword());
        var request = request("auth/login").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = send(sessionClient, request);
        checkStatus(response);
        // Versiones anteriores: 200 Ok./Fails.; 5.2.3: 204 sin cuerpo.
        String result = response.body().trim();
        if (result.equals("Fails.")) throw new QBittorrentConnectionException(AUTHENTICATION);
        if (!(response.statusCode() == 204 || (response.statusCode() == 200 && result.equals("Ok.")))
                || cookies.getCookieStore().getCookies().isEmpty()) {
            throw new QBittorrentConnectionException(UPSTREAM);
        }
    }

    private TorrentConnectionStatus readVersions(AuthMode mode) {
        String version = readVersion("app/version", mode);
        String apiVersion = readVersion("app/webapiVersion", mode);
        return new TorrentConnectionStatus(true, true, type(),
                mode == AuthMode.API_KEY ? "api-key" : "session", version, apiVersion);
    }

    private String readVersion(String path, AuthMode mode) {
        var builder = request(path).GET();
        if (mode == AuthMode.API_KEY) builder.header("Authorization", "Bearer " + qbittorrent.getAuth().getApiKey());
        var response = send(mode == AuthMode.API_KEY ? apiKeyClient : sessionClient, builder.build());
        checkStatus(response);
        String version = response.body().trim();
        if (response.statusCode() != 200 || version.length() > 64
                || !version.matches("v?\\d+(?:\\.\\d+)+(?:[A-Za-z0-9.+_-]*)")) {
            throw new QBittorrentConnectionException(UPSTREAM);
        }
        return version;
    }

    private HttpRequest.Builder request(String path) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        URI uri = URI.create(base);
        return HttpRequest.newBuilder(URI.create(base + "/api/v2/" + path))
                .timeout(properties.getRequestTimeout())
                .header("Origin", uri.getScheme() + "://" + uri.getRawAuthority())
                .header("Referer", base + "/");
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new QBittorrentConnectionException(TIMEOUT);
        } catch (IOException ex) {
            throw new QBittorrentConnectionException(UPSTREAM);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QBittorrentConnectionException(INTERRUPTED);
        }
    }

    private void checkStatus(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw new QBittorrentConnectionException(AUTHENTICATION);
        if (status < 200 || status >= 300) throw new QBittorrentConnectionException(UPSTREAM);
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @PreDestroy
    public void close() {
        if (apiKeyClient != null) apiKeyClient.shutdownNow();
        if (sessionClient != null) sessionClient.shutdownNow();
        cookies.getCookieStore().removeAll();
    }
}
