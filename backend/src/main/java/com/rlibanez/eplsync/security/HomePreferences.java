package com.rlibanez.eplsync.security;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
@org.springframework.context.annotation.DependsOn("accountStore")
public class HomePreferences {
    public record Section(String id, Boolean enabled, Integer bookCount, Integer eventCount) {
        public Section(String id, Boolean enabled, Integer bookCount) {this(id,enabled,bookCount,null);}
    }
    public record Preferences(List<Section> sections) {}
    private static final Set<String> BOOKS=Set.of("newReleases","recentUpdates","recentBooks");
    private static final Set<String> IDS=Set.of("header","overview","newReleases","recentUpdates","recentBooks","recentEvents");
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    public HomePreferences(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        if(jdbc.queryForList("PRAGMA table_info(users)").stream().noneMatch(column -> "home_preferences".equals(column.get("name"))))
            jdbc.execute("ALTER TABLE users ADD COLUMN home_preferences TEXT");
    }
    public static Preferences defaults() {
        return new Preferences(List.of(new Section("header",true,null),new Section("overview",true,null),
            new Section("newReleases",true,10),new Section("recentUpdates",true,10),
            new Section("recentBooks",true,10),new Section("recentEvents",true,null,10)));
    }
    public Preferences get(String userId) {
        var json=jdbc.queryForObject("SELECT home_preferences FROM users WHERE id=?",String.class,userId);
        return json==null ? defaults() : withEventDefaults(mapper.readValue(json,Preferences.class));
    }
    private Preferences withEventDefaults(Preferences input) {
        return new Preferences(input.sections().stream().map(section ->
            "recentEvents".equals(section.id()) && section.eventCount()==null
                ? new Section(section.id(),section.enabled(),section.bookCount(),10) : section).toList());
    }
    public Preferences save(String userId, Preferences input) {
        if(input==null || input.sections()==null || input.sections().size()!=IDS.size())
            throw new UserInputException("Debes incluir las seis secciones de la pantalla inicial");
        var seen=new HashSet<String>();
        for(var section:input.sections()) {
            if(section==null || section.id()==null || !IDS.contains(section.id()) || !seen.add(section.id()) || section.enabled()==null)
                throw new UserInputException("Las secciones deben ser válidas y no pueden repetirse");
            if(BOOKS.contains(section.id())) {
                if(section.bookCount()==null || section.bookCount()<1 || section.bookCount()>100)
                    throw new UserInputException("El número de libros debe estar entre 1 y 100");
            } else if(section.bookCount()!=null) throw new UserInputException("Solo las secciones de libros admiten un número de libros");
            if("recentEvents".equals(section.id())) {
                if(section.eventCount()!=null && (section.eventCount()<1 || section.eventCount()>100))
                    throw new UserInputException("El número de eventos debe estar entre 1 y 100");
            } else if(section.eventCount()!=null) throw new UserInputException("Solo Últimos eventos admite un número de eventos");
        }
        var result=withEventDefaults(new Preferences(List.copyOf(input.sections())));
        if(jdbc.update("UPDATE users SET home_preferences=? WHERE id=?",mapper.writeValueAsString(result),userId)!=1)
            throw new org.springframework.security.access.AccessDeniedException("Cuenta no disponible");
        return result;
    }
}
