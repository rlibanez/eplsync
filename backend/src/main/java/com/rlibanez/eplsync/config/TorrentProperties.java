package com.rlibanez.eplsync.config;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eplsync.torrent")
public class TorrentProperties {
    private List<String> trackers = List.of();

    public List<String> getTrackers() {
        return trackers;
    }

    public void setTrackers(List<String> trackers) {
        this.trackers = trackers.stream().map(String::trim).filter(s -> !s.isEmpty())
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
