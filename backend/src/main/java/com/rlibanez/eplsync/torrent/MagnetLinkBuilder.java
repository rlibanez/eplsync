package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class MagnetLinkBuilder {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final TorrentProperties properties;

    public MagnetLinkBuilder(TorrentProperties properties) {
        this.properties = properties;
    }

    public List<String> hashes(String links) {
        if (links == null || links.isBlank()) return List.of();
        var hashes = new LinkedHashSet<String>();
        // El catálogo separa los hashes por comas; también se admiten espacios y punto y coma.
        for (String token : links.split("[,;\\s]+")) {
            String hash = token.toUpperCase(Locale.ROOT);
            if (hash.matches("[0-9A-F]{40}")) {
                hashes.add(hash);
            } else if (hash.matches("[A-Z2-7]{32}")) {
                byte[] bytes = new byte[20];
                int buffer = 0, bits = 0, index = 0;
                for (char c : hash.toCharArray()) {
                    buffer = (buffer << 5) | BASE32.indexOf(c);
                    bits += 5;
                    if (bits >= 8) {
                        bits -= 8;
                        bytes[index++] = (byte) (buffer >> bits);
                    }
                }
                hashes.add(HexFormat.of().withUpperCase().formatHex(bytes));
            }
        }
        return List.copyOf(hashes);
    }

    public String build(String hash, Long eplId, String title) {
        var magnet = new StringBuilder("magnet:?xt=urn:btih:").append(hash)
                .append("&dn=").append(encode("EPL_" + eplId + "_" + title));
        properties.getTrackers().forEach(tracker -> magnet.append("&tr=").append(encode(tracker)));
        return magnet.toString();
    }

    private String encode(String value) {
        // ':' and '/' are valid within URI query values; keep tracker URLs readable.
        // Preserve escaping for separators (&, =), fragments (#), percent signs and spaces.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("%3A", ":").replace("%2F", "/");
    }
}
