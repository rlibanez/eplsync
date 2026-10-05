package com.rlibanez.eplsync.qbittorrent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "eplsync.torrent.qbittorrent")
public class QBittorrentProperties {
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private transient java.util.function.Supplier<QBittorrentProperties> effectiveSupplier;
    public void useEffective(java.util.function.Supplier<QBittorrentProperties> supplier) { this.effectiveSupplier = supplier; }
    public QBittorrentProperties effective() { return effectiveSupplier == null ? this : effectiveSupplier.get(); }
    public Auth getAuth() { var current = effective(); return current == this ? auth : current.getAuth(); }
    public Download getDownload() { var current = effective(); return current == this ? download : current.getDownload(); }

    private Auth auth = new Auth();
    private Download download = new Download();

    @Getter
    @Setter
    public static class Download {
        private String category = "Libros";
        private java.util.List<String> tags = java.util.List.of("EPLSync", "{language}");
        private boolean autoManagement = true;
    }


    public enum AuthMode { AUTO, API_KEY, SESSION }

    @Getter
    @Setter
    public static class Auth {
        private AuthMode mode = AuthMode.AUTO;
        private String apiKey = "";
        private String username = "";
        private String password = "";

        public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }
        public boolean hasSessionCredentials() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }
    }

    public void validate() {
        if (auth == null || auth.getMode() == null) throw invalid("auth.mode es obligatorio");
        boolean credentialsAvailable = switch (auth.getMode()) {
            case API_KEY -> auth.hasApiKey();
            case SESSION -> auth.hasSessionCredentials();
            case AUTO -> auth.hasApiKey() || auth.hasSessionCredentials();
        };
        if (!credentialsAvailable) throw invalid("faltan credenciales completas para auth.mode");
        if (auth.hasApiKey() && auth.getMode() != AuthMode.SESSION
                && !auth.getApiKey().matches("[\\x21-\\x7E]+")) {
            throw invalid("auth.api-key debe ser un token sin espacios ni caracteres de control");
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new com.rlibanez.eplsync.exception.UserInputException("Configuración qBittorrent: " + message);
    }
}
