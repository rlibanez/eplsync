package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Global Hardcover credentials; only their presence and source are returned to clients. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class HardcoverSettings {
    static final String KEY="hardcover.api-key";
    public record Input(String apiKey,Boolean clearApiKey) {
        @Override public String toString() {return "Hardcover credentials [redacted]";}
    }
    public record View(boolean apiKeyConfigured,boolean environmentConfigured,boolean overridden) {}
    private final JdbcTemplate jdbc;
    private final String cipherKey;
    private final String environmentKey;
    public HardcoverSettings(JdbcTemplate jdbc,@Value("${eplsync.security.secret-key:}") String cipherKey,@Value("${eplsync.hardcover.api-key:}") String environmentKey) {
        this.jdbc=jdbc;this.cipherKey=cipherKey;this.environmentKey=normalize(environmentKey);
    }
    private java.util.List<String> stored() {return jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,KEY);}
    public synchronized View view() {
        var values=stored();return new View(values.isEmpty()?!environmentKey.isEmpty():!values.getFirst().isEmpty(),!environmentKey.isEmpty(),!values.isEmpty());
    }
    synchronized String apiKey() {
        var values=stored();if(values.isEmpty()) return environmentKey;
        if(values.getFirst().isEmpty()) return "";
        try {return new CredentialCipher(cipherKey).decrypt(KEY,values.getFirst());}
        catch(IllegalStateException ex) {throw new UserInputException("No se puede recuperar la API key de Hardcover; revisa EPLSYNC_SECRET_KEY");}
    }
    synchronized String resolve(Input input) {
        if(input==null) throw new UserInputException("Indica la configuración de Hardcover");
        String value=normalize(input.apiKey());
        if(!value.isEmpty() && Boolean.TRUE.equals(input.clearApiKey())) throw new UserInputException("No se puede cambiar y borrar la API key a la vez");
        return !value.isEmpty()?value:Boolean.TRUE.equals(input.clearApiKey())?"":apiKey();
    }
    @Transactional
    public synchronized View save(Input input) {
        resolve(input);
        String value=normalize(input.apiKey());
        if(!value.isEmpty()) {
            if(cipherKey.isBlank()) throw new SecretKeyRequiredException();
            value=new CredentialCipher(cipherKey).encrypt(KEY,value);
        } else if(!Boolean.TRUE.equals(input.clearApiKey())) return view();
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",KEY,value);
        return view();
    }
    @Transactional
    public synchronized View restore() {jdbc.update("DELETE FROM app_settings WHERE setting_key=?",KEY);return view();}
    static String normalize(String value) {
        if(value==null) return "";
        String key=value.strip().replaceFirst("(?i)^Bearer\\s+","");
        if(key.length()>8192 || key.chars().anyMatch(c->c<=32 || c>=127)) throw new UserInputException("La API key de Hardcover no tiene un formato válido");
        return key;
    }
}
