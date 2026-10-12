package com.rlibanez.eplsync.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/calibre")
@PreAuthorize("hasRole('ADMIN')")
public class CalibreController {
    private final CalibreSettings settings;
    private final CalibreClient client;
    public CalibreController(CalibreSettings settings, CalibreClient client) { this.settings = settings; this.client = client; }
    @GetMapping public CalibreSettings.View view() { return settings.view(); }
    @PutMapping public CalibreSettings.View save(@RequestBody CalibreSettings.Input input) {
        var connection = settings.resolve(input);
        var check = client.check(connection);
        if (check.libraries().stream().noneMatch(l -> l.id().equals(input.library())))
            throw new com.rlibanez.eplsync.exception.UserInputException("La biblioteca seleccionada no está disponible para esta cuenta");
        // Persist exactly the credentials that were checked, even if another admin saved concurrently.
        return settings.save(new CalibreSettings.Input(connection.url(), connection.username(), connection.password(), true, connection.library()));
    }
    @PostMapping("/connection") public CalibreClient.Check check(@RequestBody CalibreSettings.Input input) {
        return client.check(settings.resolve(input));
    }
    @GetMapping("/books") public CalibreClient.Books books(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return client.books(settings.connection(), page, size); }
}
