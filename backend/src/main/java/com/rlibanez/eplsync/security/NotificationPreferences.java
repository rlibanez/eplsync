package com.rlibanez.eplsync.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class NotificationPreferences {
    public record Preferences(Boolean floating, Boolean unreadBadge,Boolean starts,Boolean finishes,
            Boolean success,Boolean warning,Boolean error,Integer seconds) {
        public Preferences(Boolean floating,Boolean unreadBadge) { this(floating,unreadBadge,true,true,true,true,true,5); }
    }
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    public NotificationPreferences(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static Preferences defaults() { return new Preferences(true,true); }
    public Preferences get(String userId) {
        var json=jdbc.queryForObject("SELECT notification_preferences FROM users WHERE id=?",String.class,userId);
        if(json==null) return defaults();
        return merge(mapper.readValue(json,Preferences.class),defaults());
    }
    private Preferences merge(Preferences value,Preferences fallback) {
        return new Preferences(value.floating()==null ? fallback.floating() : value.floating(),
            value.unreadBadge()==null ? fallback.unreadBadge() : value.unreadBadge(),
            value.starts()==null ? fallback.starts() : value.starts(),value.finishes()==null ? fallback.finishes() : value.finishes(),
            value.success()==null ? fallback.success() : value.success(),value.warning()==null ? fallback.warning() : value.warning(),
            value.error()==null ? fallback.error() : value.error(),value.seconds()==null ? fallback.seconds() : value.seconds());
    }
    @Transactional
    public Preferences save(String userId,Preferences input) {
        if(input==null || input.floating()==null || input.unreadBadge()==null)
            throw new UserInputException("Indica las dos opciones de notificaciones");
        input=merge(input,get(userId));
        if(input.seconds()<1 || input.seconds()>60) throw new UserInputException("La duración debe estar entre 1 y 60 segundos");
        if(jdbc.update("UPDATE users SET notification_preferences=? WHERE id=?",mapper.writeValueAsString(input),userId)!=1)
            throw new UserInputException("Usuario inexistente");
        return input;
    }
}
