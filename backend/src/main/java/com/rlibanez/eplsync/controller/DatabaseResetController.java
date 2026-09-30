package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.maintenance.DatabaseResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/maintenance")
public class DatabaseResetController {
    private final DatabaseResetService service;
    public DatabaseResetController(DatabaseResetService service) { this.service = service; }
    public record Confirmation(@AssertTrue Boolean confirm) {}

    @PostMapping("/reset")
    public DatabaseResetService.ResetResult reset(@Valid @RequestBody Confirmation confirmation) {
        if (!Boolean.TRUE.equals(confirmation.confirm()))
            throw new IllegalArgumentException("Se requiere confirm=true para reiniciar la base de datos");
        return service.reset();
    }
}
