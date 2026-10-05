package com.rlibanez.eplsync.qbittorrent;

import java.net.URI;
import java.util.Locale;

/** Canonical identity of a qBittorrent endpoint, including its reverse-proxy base path. */
public final class QBittorrentDestination {
    private QBittorrentDestination() {}

    public static String normalize(String value) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            return scheme + "://" + host + (port == -1 ? "" : ":" + port) + path;
        } catch (RuntimeException ex) {
            // Never include a rejected URL: it may contain a password in user-info.
            throw new IllegalArgumentException("El destino de qBittorrent debe ser una URL HTTP(S) con host y puerto válidos, sin credenciales, query ni fragmento");
        }
    }
}
