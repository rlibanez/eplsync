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
    private boolean enabled;
    private String client = "qbittorrent";
    private String baseUrl = "http://localhost:8080";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Rename rename = new Rename();
    private Download download = new Download();

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
