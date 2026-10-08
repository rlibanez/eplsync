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
    // This ordered registry defines both new-account defaults and format compatibility.
    private static final List<Section> DEFAULTS=List.of(new Section("header",true,null),new Section("overview",true,null),
            new Section("newReleases",true,10),new Section("recentUpdates",true,10),
            new Section("recentBooks",true,10),new Section("recentEvents",true,null,10));
    private static final Map<String,Section> AVAILABLE=DEFAULTS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Section::id,section -> section));
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    public HomePreferences(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        if(jdbc.queryForList("PRAGMA table_info(users)").stream().noneMatch(column -> "home_preferences".equals(column.get("name"))))
            jdbc.execute("ALTER TABLE users ADD COLUMN home_preferences TEXT");
    }
    public static Preferences defaults() {
        return new Preferences(DEFAULTS);
    }
    public Preferences get(String userId) {
        var json=jdbc.queryForObject("SELECT home_preferences FROM users WHERE id=?",String.class,userId);
        if(json==null) return defaults();
        Preferences stored=mapper.readerFor(Preferences.class)
                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        return merge(stored);
    }
    /** Preserve saved choices and order, then append missing sections in default order; no database writes. */
    private Preferences merge(Preferences input) {
        var sections=new LinkedHashMap<String,Section>();
        if(input!=null && input.sections()!=null) for(var section:input.sections()) {
            if(section==null || section.id()==null || sections.containsKey(section.id())) continue;
            var defaultSection=AVAILABLE.get(section.id());
            if(defaultSection==null) continue;
            sections.put(section.id(),new Section(section.id(),section.enabled()==null ? defaultSection.enabled() : section.enabled(),
                    count(section.bookCount(),defaultSection.bookCount()),count(section.eventCount(),defaultSection.eventCount())));
        }
        for(var section:DEFAULTS) sections.putIfAbsent(section.id(),section);
        return new Preferences(List.copyOf(sections.values()));
    }
    private Integer count(Integer saved,Integer fallback) {
        if(fallback==null) return null;
        return saved!=null && saved>=1 && saved<=100 ? saved : fallback;
    }
    public Preferences save(String userId, Preferences input) {
        if(input==null || input.sections()==null || input.sections().size()!=AVAILABLE.size())
            throw new UserInputException("Debes incluir todas las secciones de la pantalla inicial");
        var seen=new HashSet<String>();
        for(var section:input.sections()) {
            if(section==null || section.id()==null || !AVAILABLE.containsKey(section.id()) || !seen.add(section.id()) || section.enabled()==null)
                throw new UserInputException("Las secciones deben ser válidas y no pueden repetirse");
            if(AVAILABLE.get(section.id()).bookCount()!=null) {
                if(section.bookCount()==null || section.bookCount()<1 || section.bookCount()>100)
                    throw new UserInputException("El número de libros debe estar entre 1 y 100");
            } else if(section.bookCount()!=null) throw new UserInputException("Solo las secciones de libros admiten un número de libros");
            if(AVAILABLE.get(section.id()).eventCount()!=null) {
                if(section.eventCount()!=null && (section.eventCount()<1 || section.eventCount()>100))
                    throw new UserInputException("El número de eventos debe estar entre 1 y 100");
            } else if(section.eventCount()!=null) throw new UserInputException("Solo Últimos eventos admite un número de eventos");
        }
        var result=merge(input);
        if(jdbc.update("UPDATE users SET home_preferences=? WHERE id=?",mapper.writeValueAsString(result),userId)!=1)
            throw new org.springframework.security.access.AccessDeniedException("Cuenta no disponible");
        return result;
    }
}
