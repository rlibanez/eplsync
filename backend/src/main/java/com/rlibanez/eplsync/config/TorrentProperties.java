package com.rlibanez.eplsync.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import com.rlibanez.eplsync.torrent.TorrentNameResolver;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "eplsync.torrent")
public class TorrentProperties {
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private transient java.util.function.Supplier<TorrentProperties> effectiveSupplier;
    public void useEffective(java.util.function.Supplier<TorrentProperties> supplier) { this.effectiveSupplier = supplier; }
    public TorrentProperties effective() { return effectiveSupplier == null ? this : effectiveSupplier.get(); }
    public boolean isEnabled() { var current = effective(); return current == this ? enabled : current.isEnabled(); }
    public String getClient() { var current = effective(); return current == this ? client : current.getClient(); }
    public String getBaseUrl() { var current = effective(); return current == this ? baseUrl : current.getBaseUrl(); }
    public Duration getConnectTimeout() { var current = effective(); return current == this ? connectTimeout : current.getConnectTimeout(); }
    public Duration getRequestTimeout() { var current = effective(); return current == this ? requestTimeout : current.getRequestTimeout(); }
    public Rename getRename() { var current = effective(); return current == this ? rename : current.getRename(); }
    public Download getDownload() { var current = effective(); return current == this ? download : current.getDownload(); }
    public Bulk getBulk() { var current = effective(); return current == this ? bulk : current.getBulk(); }
    public List<String> getTrackers() { var current = effective(); return current == this ? trackers : current.getTrackers(); }

    private boolean enabled;
    private String client = "qbittorrent";
    private String baseUrl = "http://localhost:8080";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Rename rename = new Rename();
    private Download download = new Download();
    private Bulk bulk = new Bulk();

    @Getter
    @Setter
    public static class Bulk {
        private com.rlibanez.eplsync.torrent.bulk.MultipleHashes multipleHashes = com.rlibanez.eplsync.torrent.bulk.MultipleHashes.SKIP;
        private int batchSize = 100;
        private int concurrency = 1;
        private Duration interval = Duration.ofMillis(500);
    }


    @Getter
    @Setter
    public static class Download {
        private boolean start = true;
        private String savePath;
    }

    private List<String> trackers = List.of();

    @Getter
    @Setter
    public static class Rename {
        private boolean enabled = true;
        private String pattern = "EPL_{eplId}_{title}";
    }

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException ex) {
            throw invalid("base-url debe ser una URL HTTP(S) válida");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw invalid("base-url requiere HTTP(S), host y puerto válido, sin credenciales, query ni fragmento");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw invalid("los timeouts deben ser positivos");
        }
        if (client == null || client.isBlank()) throw invalid("client es obligatorio");
        if (rename == null) throw invalid("rename es obligatorio");
        if (rename.isEnabled()) TorrentNameResolver.validatePattern(rename.getPattern());
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Configuración torrent: " + message);
    }

    public void setTrackers(List<String> trackers) {
        this.trackers = trackers.stream().map(tracker -> java.util.Objects.requireNonNull(tracker).trim()).filter(s -> !s.isEmpty())
                .peek(TorrentProperties::validateTracker).distinct().toList();
    }

    private static void validateTracker(String tracker) {
        URI uri = URI.create(tracker);
        if (uri.getScheme() == null
                || !Set.of("udp", "http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() > 65535 || uri.getPort() == 0
                || (uri.getScheme().equalsIgnoreCase("udp") && uri.getPort() < 1)) {
            throw new IllegalArgumentException("Tracker inválido: se requiere una URL HTTP(S) o UDP con puerto válido");
        }
    }
}
