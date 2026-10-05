package com.rlibanez.eplsync.qbittorrent;

import java.util.Objects;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.torrent.TorrentClient;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import com.rlibanez.eplsync.torrent.downloads.RemoteTorrent;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties.AuthMode;
import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import jakarta.annotation.PreDestroy;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
public class QBittorrentClient implements TorrentClient, AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QBittorrentClient.class);
    private final tools.jackson.databind.json.JsonMapper jsonMapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private AuthMode sendingAuth;
    private final TorrentProperties properties;
    private final QBittorrentProperties qbittorrent;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient apiKeyClient;
    private final String authorizedDestination;
    private final HttpClient sessionClient;

    private Holder runtime;
    private static class Holder {
        final QBittorrentClient client;
        final TorrentProperties torrent;
        final QBittorrentProperties qb;
        int users;
        boolean retired;
        Holder(TorrentProperties torrent, QBittorrentProperties qb) {
            this.torrent = torrent; this.qb = qb; this.client = new QBittorrentClient(torrent, qb);
        }
    }
    private boolean dynamic() { return properties.effective() != properties; }
    private <T> T configured(java.util.function.Function<QBittorrentClient,T> action) {
        Holder selected;
        synchronized (this) {
            var torrent = properties.effective(); var qb = qbittorrent.effective();
            if (runtime == null || runtime.torrent != torrent || runtime.qb != qb) {
                if (runtime != null) { runtime.retired = true; if (runtime.users == 0) runtime.client.close(); }
                runtime = new Holder(torrent, qb);
            }
            selected = runtime; selected.users++;
        }
        try { return action.apply(selected.client); }
        finally { synchronized (this) { if (--selected.users == 0 && selected.retired) selected.client.close(); } }
    }

    public QBittorrentClient(TorrentProperties properties, QBittorrentProperties qbittorrent) {
        this.properties = properties;
        this.qbittorrent = qbittorrent;
        authorizedDestination = properties.isEnabled() ? QBittorrentDestination.normalize(properties.getBaseUrl()) : null;
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
        if (dynamic()) return configured(value -> Objects.requireNonNull(value).checkConnection());
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
        if (dynamic()) { configured(c -> { c.renameTorrent(hash, name); return null; }); return; }
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

    @Override
    public com.rlibanez.eplsync.torrent.TorrentDownload withDefaults(
            com.rlibanez.eplsync.torrent.TorrentDownload download) {
        var options = download.qbittorrent();
        var defaults = qbittorrent.getDownload();
        var effective = new com.rlibanez.eplsync.dto.TorrentDownloadRequest.QBittorrent(
                options != null && options.category() != null ? options.category() : defaults.getCategory(),
                options != null && options.tags() != null ? new java.util.ArrayList<>(options.tags()) : new java.util.ArrayList<>(defaults.getTags()),
                options != null && options.autoManagement() != null ? options.autoManagement() : defaults.isAutoManagement());
        return new com.rlibanez.eplsync.torrent.TorrentDownload(download.hash(), download.magnet(),
                download.start(), download.savePath(), download.name(), effective, download.book());
    }

    /** Una única escritura; los timeouts no provocan reenvíos automáticos. */
    @Override
    public com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(
            com.rlibanez.eplsync.torrent.TorrentDownload download) {
        return addTorrent(download, new com.rlibanez.eplsync.torrent.TorrentSubmissionContext());
    }

    @Override
    public com.rlibanez.eplsync.dto.TorrentDownloadResult.Status addTorrent(
            com.rlibanez.eplsync.torrent.TorrentDownload download,
            com.rlibanez.eplsync.torrent.TorrentSubmissionContext context) {
        if (dynamic()) return configured(c -> c.addTorrent(download, context));
        if (!properties.isEnabled()) throw new TorrentOperationException(HttpStatus.CONFLICT,
                "La conexión torrent está deshabilitada");
        var defaults = qbittorrent.getDownload();
        var options = download.qbittorrent();
        String category = options != null && options.category() != null ? options.category() : defaults.getCategory();
        var tagPatterns = options != null && options.tags() != null ? options.tags() : defaults.getTags();
        var tags = new com.rlibanez.eplsync.torrent.TorrentNameResolver().resolveTags(tagPatterns, download.book());
        boolean automatic = options != null && options.autoManagement() != null
                ? options.autoManagement() : defaults.isAutoManagement();
        category = category == null ? "" : category.trim();
        if (category.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Categoría inválida");
        if (tags == null || tags.stream().anyMatch(tag -> tag == null || tag.isBlank()
                || tag.contains(",") || tag.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Las etiquetas no pueden estar vacías ni contener comas o caracteres de control");
        String savePath = download.savePath();
        if (savePath != null && savePath.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("savePath contiene caracteres de control");
        if (automatic && savePath != null && !savePath.isBlank())
            throw new IllegalArgumentException("savePath requiere autoManagement=false");
        if (context.containsTorrent(download.hash(), this::submissionHashes))
            return com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ALREADY_EXISTS;
        if (!category.isEmpty()) validateCategory(category, context);
        boolean key = sendingAuth() == AuthMode.API_KEY;
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("urls", download.magnet());
        fields.put("category", category);
        fields.put("tags", String.join(",", tags.stream().map(tag -> Objects.requireNonNull(tag).trim()).distinct().toList()));
        fields.put("stopped", Boolean.toString(!download.start()));
        fields.put("autoTMM", Boolean.toString(automatic));
        if (!automatic && savePath != null && !savePath.isBlank()) fields.put("savepath", savePath);
        if (download.name() != null) fields.put("rename", download.name());
        String body = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var builder = request("torrents/add").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key) builder.header("Authorization", "Bearer " + qbittorrent.getAuth().getApiKey());
        try {
            var response = send(key ? apiKeyClient : sessionClient, builder.build());
            if (response.statusCode() == 401 || response.statusCode() == 403) invalidateSendingState();
            if (response.statusCode() == 409) {
                throw new TorrentOperationException(HttpStatus.CONFLICT,
                        "qBittorrent ha rechazado el envío; comprueba si el torrent ya existe");
            }
            checkStatus(response);
            // qBittorrent 5.2.3 devuelve contadores JSON; versiones anteriores usaban Ok.
            if (response.statusCode() == 200 && response.body().trim().equals("Ok.")) {
                context.torrentAccepted(download.hash());
                return com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED;
            }
            var result = parseJson(response.body());
            if ((response.statusCode() != 200 && response.statusCode() != 202)
                    || result.path("failure_count").asInt(-1) != 0
                    || result.path("success_count").asInt(0) + result.path("pending_count").asInt(0) != 1)
                throw new QBittorrentConnectionException(UPSTREAM);
            context.torrentAccepted(download.hash());
            return com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED;
        } catch (RuntimeException ex) {
            // El servidor puede haber aceptado un envío cuya respuesta se perdió.
            context.invalidateTorrents();
            throw ex;
        }
    }

    /** Índice por solicitud: el identificador remoto puede diferir del hash v1 del catálogo. */
    private java.util.Set<String> submissionHashes() {
        var response = readAuthenticatedJson("torrents/info");
        if (!response.isArray()) throw new QBittorrentConnectionException(UPSTREAM);
        var hashes = new java.util.HashSet<String>();
        for (var item : response) {
            var aliases = torrentHashes(item);
            if (aliases.isEmpty()) throw new QBittorrentConnectionException(UPSTREAM);
            hashes.addAll(aliases);
        }
        return hashes;
    }

    /** Instantánea completa y sin filtros: permite detectar ausencias y descubrir torrents ajenos al envío. */
    @Override
    public java.util.List<RemoteTorrent> listTorrents() {
        if (dynamic()) return configured(value -> Objects.requireNonNull(value).listTorrents());
        var response = readAuthenticatedJson("torrents/info");
        if (!response.isArray()) throw new QBittorrentConnectionException(UPSTREAM);
        var result = new java.util.ArrayList<RemoteTorrent>();
        var hashes = new java.util.HashSet<String>();
        for (var item : response) {
            String hash = item.path("hash").asString("").toUpperCase(java.util.Locale.ROOT);
            String state = item.path("state").asString("");
            if (!hash.matches("[0-9A-F]{40}|[0-9A-F]{64}") || state.isBlank()
                    || !item.path("progress").isNumber() || !item.path("amount_left").isNumber()
                    || !hashes.add(hash)) throw new QBittorrentConnectionException(UPSTREAM);
            double progress = item.path("progress").asDouble();
            long left = item.path("amount_left").asLong();
            if (!Double.isFinite(progress) || progress < 0 || progress > 1 || left < 0)
                throw new QBittorrentConnectionException(UPSTREAM);
            var status = remoteStatus(state, progress, left);
            long completion = item.path("completion_on").asLong(0);
            java.time.Instant completedAt = null;
            if (completion > 0) {
                try { completedAt = java.time.Instant.ofEpochSecond(completion); }
                catch (java.time.DateTimeException ex) { throw new QBittorrentConnectionException(UPSTREAM); }
            }
            result.add(new RemoteTorrent(hash, status, completedAt, torrentHashes(item), item.path("content_path").asString(""), item.path("name").asString("")));
        }
        return java.util.List.copyOf(result);
    }

    @Override
    public void deleteTorrent(String remoteId, boolean deleteFiles) {
        if (dynamic()) { configured(c -> { c.deleteTorrent(remoteId, deleteFiles); return null; }); return; }
        if (!remoteId.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new IllegalArgumentException("Identificador remoto inválido");
        boolean key = sendingAuth() == AuthMode.API_KEY;
        var builder = request("torrents/delete").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("hashes=" + encode(remoteId) + "&deleteFiles=" + deleteFiles));
        if (key) builder.header("Authorization", "Bearer " + qbittorrent.getAuth().getApiKey());
        // Una escritura incierta no se reintenta automáticamente.
        checkStatus(send(key ? apiKeyClient : sessionClient, builder.build()));
    }

    private java.util.Set<String> torrentHashes(tools.jackson.databind.JsonNode item) {
        var result = new java.util.HashSet<String>();
        for (String field : java.util.List.of("hash", "infohash_v1", "infohash_v2")) {
            String value = item.path(field).asString("").toUpperCase(java.util.Locale.ROOT);
            if (value.isEmpty()) continue;
            int length = field.equals("infohash_v1") ? 40 : field.equals("infohash_v2") ? 64 : 0;
            if (!(length == 0 ? value.matches("[0-9A-F]{40}|[0-9A-F]{64}")
                    : value.matches("[0-9A-F]{" + length + "}")))
                throw new QBittorrentConnectionException(UPSTREAM);
            result.add(value);
        }
        return result;
    }

    private DownloadStatus remoteStatus(String state, double progress, long left) {
        var status = switch (state) {
            case "error", "missingFiles" -> DownloadStatus.ERROR;
            case "checkingDL", "checkingUP", "checkingResumeData", "moving" -> DownloadStatus.CHECKING;
            case "queuedDL", "queuedUP" -> DownloadStatus.QUEUED;
            case "pausedDL", "pausedUP", "stoppedDL", "stoppedUP" -> DownloadStatus.PAUSED;
            case "downloading", "forcedDL", "stalledDL", "metaDL", "forcedMetaDL",
                 "uploading", "forcedUP", "stalledUP" -> DownloadStatus.DOWNLOADING;
            default -> DownloadStatus.UNKNOWN;
        };
        if (status != DownloadStatus.ERROR
                && status != DownloadStatus.CHECKING
                && status != DownloadStatus.UNKNOWN
                && progress == 1 && left == 0)
            return DownloadStatus.DOWNLOADED;
        return status;
    }

    private synchronized AuthMode sendingAuth() {
        if (sendingAuth == null) {
            sendingAuth = checkConnection().authMode().equals("api-key") ? AuthMode.API_KEY : AuthMode.SESSION;
            log.debug("Autenticación preparada para envíos a qBittorrent: {}", sendingAuth);
        }
        return sendingAuth;
    }

    private synchronized void invalidateSendingState() {
        sendingAuth = null;
    }

    private tools.jackson.databind.JsonNode readAuthenticatedJson(String path) {
        var mode = sendingAuth();
        try { return readJson(path, mode == AuthMode.API_KEY); }
        catch (QBittorrentConnectionException ex) {
            if (ex.getReason() != AUTHENTICATION) throw ex;
            synchronized (this) {
                if (sendingAuth == mode) invalidateSendingState();
                mode = sendingAuth();
            }
            // Reintentar solo la lectura, nunca el POST de alta.
            return readJson(path, mode == AuthMode.API_KEY);
        }
    }

    @Override
    public java.util.List<String> listCategories() {
        if (dynamic()) return configured(value -> Objects.requireNonNull(value).listCategories());
        var response = readAuthenticatedJson("torrents/categories");
        if (!response.isObject()) throw new QBittorrentConnectionException(UPSTREAM);
        var names = new java.util.ArrayList<String>();
        response.properties().forEach(entry -> {
            if (!entry.getKey().isBlank()) names.add(entry.getKey());
        });
        names.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        log.debug("Categorías de qBittorrent consultadas para esta solicitud");
        return java.util.List.copyOf(names);
    }

    private void validateCategory(String category, com.rlibanez.eplsync.torrent.TorrentSubmissionContext context) {
        var categories = context.categories(() -> new java.util.HashSet<>(listCategories()));
        if (!categories.contains(category)) throw new TorrentOperationException(HttpStatus.CONFLICT,
                "La categoría configurada no existe en qBittorrent");
    }

    private tools.jackson.databind.JsonNode readJson(String path, boolean key) {
        var builder = request(path).GET();
        if (key) builder.header("Authorization", "Bearer " + qbittorrent.getAuth().getApiKey());
        var response = send(key ? apiKeyClient : sessionClient, builder.build());
        checkStatus(response);
        if (response.statusCode() != 200) throw new QBittorrentConnectionException(UPSTREAM);
        return parseJson(response.body());
    }

    private tools.jackson.databind.JsonNode parseJson(String body) {
        try {
            var node = jsonMapper.readTree(body);
            if (node == null) throw new QBittorrentConnectionException(UPSTREAM);
            return node;
        } catch (tools.jackson.core.JacksonException ex) {
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
        String base = QBittorrentDestination.normalize(properties.getBaseUrl());
        URI uri = URI.create(base);
        return HttpRequest.newBuilder(URI.create(base + "/api/v2/" + path))
                .timeout(properties.getRequestTimeout())
                .header("Origin", uri.getScheme() + "://" + uri.getRawAuthority())
                .header("Referer", base + "/");
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) {
        String expected = authorizedDestination + "/api/v2/";
        if (!request.uri().toString().startsWith(expected)
                || !QBittorrentDestination.normalize(properties.getBaseUrl()).equals(authorizedDestination)) {
            throw new QBittorrentConnectionException(UPSTREAM);
        }
        String path = request.uri().getPath();
        String endpoint = path.substring(path.lastIndexOf("/api/v2/"));
        long started = System.nanoTime();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.trace("qBittorrent {} {}: HTTP {}, {}ms", request.method(), endpoint, response.statusCode(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (response.statusCode() >= 400) log.debug("qBittorrent {} {}: HTTP {}", request.method(), endpoint, response.statusCode());
            return response;
        } catch (HttpTimeoutException ex) {
            throw new QBittorrentConnectionException(TIMEOUT);
        } catch (IOException ex) {
            log.debug("Fallo de comunicación qBittorrent {} {}: {}", request.method(), endpoint, ex.getClass().getSimpleName());
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
    @Override
    public synchronized void close() {
        if (runtime != null) { runtime.retired = true; if (runtime.users == 0) runtime.client.close(); runtime = null; }
        if (apiKeyClient != null) apiKeyClient.shutdownNow();
        if (sessionClient != null) sessionClient.shutdownNow();
        cookies.getCookieStore().removeAll();
    }
}
