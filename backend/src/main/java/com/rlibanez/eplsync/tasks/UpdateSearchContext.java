package com.rlibanez.eplsync.tasks;

import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persistent generations prevent late searches from restoring results after a destination change. */
public final class UpdateSearchContext {
    private UpdateSearchContext() {}
    public static String key(String type) {return "updates.search-context."+type;}
    public static String current(JdbcTemplate jdbc,String type) {
        var values=jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,key(type));
        if(!values.isEmpty()) return values.getFirst();
        // A full reset removes settings while the application remains running.
        jdbc.update("INSERT OR IGNORE INTO app_settings(setting_key,setting_value) VALUES(?,?)",key(type),"unbound:"+UUID.randomUUID());
        return jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,key(type));
    }
    /** Called in the same transaction as the settings change (or during startup). */
    public static void refresh(JdbcTemplate jdbc,String type,String... identity) {
        String fingerprint;
        try {
            byte[] bytes=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(List.of(identity));
            fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(Exception ex) {throw new IllegalStateException("Cannot identify update search destination",ex);}
        var previous=current(jdbc,type);
        if(previous!=null && previous.startsWith(fingerprint+":")) return;
        jdbc.update("DELETE FROM update_search_results WHERE search_type=?",type);
        if(type.equals("downloads"))
            jdbc.update("DELETE FROM update_search_results WHERE search_type='calibre' AND json_extract(criteria_json,'$.checkFiles')=1");
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",
            key(type),fingerprint+":"+UUID.randomUUID());
    }
}
