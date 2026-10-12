package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** One atomic setting; passwords are never returned and remain bound to their destination. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class CalibreSettings {
    static final String KEY = "calibre.connection";
    static final String PASSWORD = "calibre.password";
    public record Input(String url, String username, String password, Boolean clearPassword, String library) {
        @Override public String toString() { return "Calibre connection input [redacted]"; }
    }
    public record View(String url, String username, boolean passwordConfigured, String library) {}
    record Stored(String url, String username, String password, String library) {}
    record Connection(String url, String username, String password, String library) {
        @Override public String toString() { return "Calibre connection [redacted]"; }
    }
    private final JdbcTemplate jdbc;
    private final String key;
    private final JsonMapper json = JsonMapper.builder().build();
    public CalibreSettings(JdbcTemplate jdbc, @Value("${eplsync.security.secret-key:}") String key) {
        this.jdbc = jdbc; this.key = key;
    }
    private Stored stored() {
        var values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, KEY);
        return values.isEmpty() ? new Stored("", "", "", "") : json.readValue(values.getFirst(), Stored.class);
    }
    public synchronized View view() {
        var s = stored(); return new View(s.url(), s.username(), !s.password().isEmpty(), s.library());
    }
    synchronized Connection resolve(Input input) {
        if (input == null) throw new UserInputException("Indica la conexión con Calibre");
        var old = stored();
        String url = normalize(input.url());
        String username = input.username() == null ? "" : input.username().trim();
        String library = input.library() == null ? "" : input.library();
        if (username.length() > 256 || library.length() > 256 || library.indexOf('/') >= 0 || library.indexOf('\\') >= 0)
            throw new UserInputException("Usuario o biblioteca de Calibre inválidos");
        String password = input.password() == null ? "" : input.password();
        if (password.length() > 4096) throw new UserInputException("La contraseña de Calibre es demasiado larga");
        if (password.isEmpty() && !Boolean.TRUE.equals(input.clearPassword()) && url.equals(old.url()) && username.equals(old.username()) && !old.password().isEmpty()) {
            if (key.isBlank()) throw new UserInputException("No se pueden recuperar las credenciales de Calibre; revisa EPLSYNC_SECRET_KEY");
            try { password = new CredentialCipher(key).decrypt(PASSWORD, old.password()); }
            catch (IllegalStateException ex) { throw new UserInputException("No se pueden recuperar las credenciales de Calibre; revisa EPLSYNC_SECRET_KEY"); }
        }
        return new Connection(url, username, password, library);
    }
    synchronized Connection connection() {
        var s = stored(); return resolve(new Input(s.url(), s.username(), "", false, s.library()));
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized View save(Input input) {
        var previous = stored();
        var c = resolve(input);
        if (c.library().isBlank()) throw new UserInputException("Selecciona una biblioteca de Calibre");
        String encrypted = "";
        if (!c.password().isEmpty()) {
            if (key.isBlank()) throw new SecretKeyRequiredException();
            encrypted = new CredentialCipher(key).encrypt(PASSWORD, c.password());
        }
        var value = json.writeValueAsString(new Stored(c.url(), c.username(), encrypted, c.library()));
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value", KEY, value);
        if (!previous.url().equals(c.url()) || !previous.username().equals(c.username()) || !previous.library().equals(c.library())) {
            jdbc.update("DELETE FROM calibre_books WHERE active=true");
            jdbc.update("DELETE FROM app_settings WHERE setting_key=?", CalibreCatalogService.KEY);
        }
        return view();
    }
    static String normalize(String value) {
        try {
            var uri = URI.create(value == null ? "" : value.trim()).normalize();
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || value.length() > 2048) throw new IllegalArgumentException();
            int port = uri.getPort();
            if (port == (scheme.equalsIgnoreCase("https") ? 443 : 80)) port = -1;
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI(scheme.toLowerCase(java.util.Locale.ROOT), null, uri.getHost().toLowerCase(java.util.Locale.ROOT), port, path, null, null).toASCIIString();
        } catch (Exception ex) { throw new UserInputException("La URL de Calibre debe ser HTTP o HTTPS, sin credenciales, parámetros ni fragmentos"); }
    }
}
