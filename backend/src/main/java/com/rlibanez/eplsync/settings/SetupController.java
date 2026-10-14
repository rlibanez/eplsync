package com.rlibanez.eplsync.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Installation-wide progress; absence means an existing installation, never auto-open. */
@RestController
@RequestMapping("/api/settings/setup")
@PreAuthorize("hasRole('ADMIN')")
public class SetupController {
    public static final String KEY = "installation.setup";
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public SetupController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Progress(
        @Min(0) @Max(3) int step,
        @Pattern(regexp="active|deferred|completed") String status,
        @Pattern(regexp="pending|done|skipped") String torrent,
        @Pattern(regexp="pending|done|skipped") String calibre) {}
    @GetMapping public Progress progress() {
        var rows = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, KEY);
        return rows.isEmpty() ? new Progress(0,"completed","pending","pending") : json.readValue(rows.getFirst(), Progress.class);
    }
    @PostMapping @Transactional public Progress save(@Valid @RequestBody Progress value) {
        if (value.status()==null || value.torrent()==null || value.calibre()==null)
            throw new com.rlibanez.eplsync.exception.UserInputException("Estado del asistente inválido");
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value", KEY, json.writeValueAsString(value));
        return value;
    }
}
