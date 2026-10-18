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
    static final String IDENTIFIERS = "calibre.identifiers";
    public record IdentifierOptions(Boolean autoCompleteIdentifiers) {}
    static final String PASSWORD = "calibre.password";
    public record Input(String url, String username, String password, Boolean clearPassword, String library, String browserUrl) {
        public Input(String url,String username,String password,Boolean clearPassword,String library) { this(url,username,password,clearPassword,library,""); }
        @Override public String toString() { return "Calibre connection input [redacted]"; }
    }
    public record View(String url, String username, boolean passwordConfigured, String library, String browserUrl, boolean autoCompleteIdentifiers) {
        public View(String url,String username,boolean passwordConfigured,String library,String browserUrl) { this(url,username,passwordConfigured,library,browserUrl,true); }
    }
    record Stored(String url, String username, String password, String library, String browserUrl, Boolean externalPassword) {}
    record Connection(String url, String username, String password, String library) {
        @Override public String toString() { return "Calibre connection [redacted]"; }
    }
    private final JdbcTemplate jdbc;
    private final String key;
    private final Stored defaults;
    private final String environmentPassword;
    private final JsonMapper json = JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired
    public CalibreSettings(JdbcTemplate jdbc, @Value("${eplsync.security.secret-key:}") String key,
            @Value("${eplsync.calibre.url:}") String url,
            @Value("${eplsync.calibre.browser-url:}") String browserUrl,
            @Value("${eplsync.calibre.username:}") String username,
            @Value("${eplsync.calibre.password:}") String password,
            @Value("${eplsync.calibre.library:}") String library) {
        this.jdbc = jdbc; this.key = key;
        this.defaults = new Stored(url.isBlank() ? "" : normalize(url), username.trim(), "", library,
                browserUrl.isBlank() ? "" : normalize(browserUrl), true);
        this.environmentPassword = password;
        if (username.length() > 256 || library.length() > 256 || library.indexOf('/') >= 0
                || library.indexOf('\\') >= 0 || password.length() > 4096)
            throw new UserInputException("Usuario, contraseña o biblioteca de Calibre inválidos");
        refreshSearchContext();
    }
    public CalibreSettings(JdbcTemplate jdbc, String key) {
        this(jdbc, key, "", "", "", "", "");
    }
    private boolean usesEnvironmentPassword(Stored value) {
        return Boolean.TRUE.equals(value.externalPassword()) && value.url().equals(defaults.url())
                && value.username().equals(defaults.username());
    }
    private Stored stored() {
        var values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, KEY);
        return values.isEmpty() ? defaults : json.readValue(values.getFirst(), Stored.class);
    }
    public synchronized View view() {
        var s = stored(); return new View(s.url(), s.username(), (!s.password().isEmpty() || (usesEnvironmentPassword(s) && !environmentPassword.isEmpty())), s.library(), s.browserUrl()==null ? "" : s.browserUrl(), autoCompleteIdentifiers());
    }
    public synchronized boolean autoCompleteIdentifiers() {
        var values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, IDENTIFIERS);
        if (values.isEmpty()) return true;
        return !Boolean.FALSE.equals(json.readValue(values.getFirst(), IdentifierOptions.class).autoCompleteIdentifiers());
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized IdentifierOptions saveIdentifierOptions(IdentifierOptions input) {
        if (input == null || input.autoCompleteIdentifiers() == null)
            throw new UserInputException("Indica si se deben completar automáticamente los identificadores");
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",
            IDENTIFIERS, json.writeValueAsString(input));
        return new IdentifierOptions(autoCompleteIdentifiers());
    }
    synchronized Connection resolve(Input input) {
        if (input == null) throw new UserInputException("Indica la conexión con Calibre");
        if(input.browserUrl()!=null && !input.browserUrl().isBlank()) normalize(input.browserUrl());
        var old = stored();
        String url = normalize(input.url());
        String username = input.username() == null ? "" : input.username().trim();
        String library = input.library() == null ? "" : input.library();
        if (username.length() > 256 || library.length() > 256 || library.indexOf('/') >= 0 || library.indexOf('\\') >= 0)
            throw new UserInputException("Usuario o biblioteca de Calibre inválidos");
        String password = input.password() == null ? "" : input.password();
        if (password.length() > 4096) throw new UserInputException("La contraseña de Calibre es demasiado larga");
        if (password.isEmpty() && !Boolean.TRUE.equals(input.clearPassword()) && url.equals(old.url()) && username.equals(old.username())) {
            if (!old.password().isEmpty()) {
                if (key.isBlank()) throw new UserInputException("No se pueden recuperar las credenciales de Calibre; revisa EPLSYNC_SECRET_KEY");
                try { password = new CredentialCipher(key).decrypt(PASSWORD, old.password()); }
                catch (IllegalStateException ex) { throw new UserInputException("No se pueden recuperar las credenciales de Calibre; revisa EPLSYNC_SECRET_KEY"); }
            } else if (usesEnvironmentPassword(old)) password = environmentPassword;
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
        boolean external = (input.password() == null || input.password().isEmpty())
                && !Boolean.TRUE.equals(input.clearPassword()) && previous.password().isEmpty()
                && usesEnvironmentPassword(previous) && c.url().equals(previous.url())
                && c.username().equals(previous.username());
        String encrypted = "";
        if (!c.password().isEmpty() && !external) {
            if (key.isBlank()) throw new SecretKeyRequiredException();
            encrypted = new CredentialCipher(key).encrypt(PASSWORD, c.password());
        }
        var value = json.writeValueAsString(new Stored(c.url(), c.username(), encrypted, c.library(), input.browserUrl()==null || input.browserUrl().isBlank() ? "" : normalize(input.browserUrl()), external));
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value", KEY, value);
        if (!previous.url().equals(c.url()) || !previous.username().equals(c.username()) || !previous.library().equals(c.library())) {
            jdbc.update("DELETE FROM calibre_books WHERE active=true");
            jdbc.update("DELETE FROM app_settings WHERE setting_key=?", CalibreCatalogService.KEY);
        }
        refreshSearchContext();
        return view();
    }
    private void refreshSearchContext() {
        var value=stored();
        com.rlibanez.eplsync.tasks.UpdateSearchContext.refresh(jdbc,"calibre",value.url(),value.username(),value.library());
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized String updateSearchContext() {
        refreshSearchContext();
        return com.rlibanez.eplsync.tasks.UpdateSearchContext.current(jdbc,"calibre");
    }
    static String normalize(String value) {
        try {
            if (value == null) throw new IllegalArgumentException();
            var uri = URI.create(value.trim()).normalize();
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
