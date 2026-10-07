package com.rlibanez.eplsync.security;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
@org.springframework.context.annotation.DependsOn("accountStore")
public class UpdatePreferences {
    public record Preferences(List<DownloadStatus> states) {}
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    public UpdatePreferences(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS revision_update_settings (id INTEGER PRIMARY KEY CHECK(id=1), settings TEXT NOT NULL)");
    }
    public static Preferences defaults() {
        return new Preferences(List.of(DownloadStatus.values()));
    }
    public Preferences get() {
        var rows=jdbc.queryForList("SELECT settings FROM revision_update_settings WHERE id=1",String.class);
        var json=rows.isEmpty() ? null : rows.getFirst();
        return json==null ? defaults() : mapper.readValue(json,Preferences.class);
    }
    public Preferences save(Preferences input) {
        if(input==null || input.states()==null || input.states().isEmpty() || input.states().size()>DownloadStatus.values().length
                || input.states().stream().anyMatch(Objects::isNull) || new HashSet<>(input.states()).size()!=input.states().size())
            throw new UserInputException("Selecciona al menos un estado válido, sin duplicados");
        var result=new Preferences(List.copyOf(input.states()));
        jdbc.update("INSERT INTO revision_update_settings(id,settings) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET settings=excluded.settings",mapper.writeValueAsString(result));
        return result;
    }
}
