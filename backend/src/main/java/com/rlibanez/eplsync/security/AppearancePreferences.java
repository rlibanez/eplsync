package com.rlibanez.eplsync.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class AppearancePreferences {
    public record Preferences(String userId,String scheme,String palette) {}
    public record Input(String userId,String scheme,String palette,boolean initialize) {}
    private final JdbcTemplate jdbc;
    public AppearancePreferences(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Preferences get(String userId) {
        var rows=jdbc.query("SELECT scheme,palette FROM user_appearance WHERE user_id=?",
            (rs,n)->new Preferences(userId,rs.getString(1),rs.getString(2)),userId);
        return rows.isEmpty()?new Preferences(userId,null,null):rows.getFirst();
    }
    @Transactional
    public Preferences save(String userId,Input input) {
        if(input==null || !userId.equals(input.userId()))
            throw new org.springframework.security.access.AccessDeniedException("La sesión del usuario ha cambiado");
        if(input.scheme()==null || !java.util.Set.of("light","dark").contains(input.scheme())
                || input.palette()==null || !java.util.Set.of("teal","blue","violet","rose","orange","cyan","indigo","grape","lime","yellow").contains(input.palette()))
            throw new UserInputException("Tema o paleta inválidos");
        String conflict=input.initialize()?"DO NOTHING":"DO UPDATE SET scheme=excluded.scheme,palette=excluded.palette";
        jdbc.update("INSERT INTO user_appearance(user_id,scheme,palette) VALUES(?,?,?) ON CONFLICT(user_id) "+conflict,
            userId,input.scheme(),input.palette());
        return get(userId);
    }
}
