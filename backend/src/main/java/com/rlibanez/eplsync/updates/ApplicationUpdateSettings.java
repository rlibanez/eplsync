package com.rlibanez.eplsync.updates;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class ApplicationUpdateSettings {
    private static final String KEY="application.updates.automatic";
    public record Settings(Boolean automatic) {}
    private final JdbcTemplate jdbc;
    private final boolean defaultAutomatic;
    public ApplicationUpdateSettings(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Value("${eplsync.updates.automatic:true}") boolean automatic) {
        this.jdbc=jdbc;defaultAutomatic=automatic;
    }
    public boolean automatic() {
        var rows=jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,KEY);
        return rows.isEmpty() ? defaultAutomatic : Boolean.parseBoolean(rows.getFirst());
    }
    public Settings save(Settings input) {
        if(input==null || input.automatic()==null) throw new com.rlibanez.eplsync.exception.UserInputException("Indica si la comprobación automática está habilitada");
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",KEY,input.automatic().toString());
        return input;
    }
}
