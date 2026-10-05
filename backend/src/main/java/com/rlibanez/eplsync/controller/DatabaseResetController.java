package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.maintenance.DatabaseResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/maintenance")
public class DatabaseResetController {
    private final DatabaseResetService service;
    public DatabaseResetController(DatabaseResetService service) { this.service = service; }
    public record Confirmation(@AssertTrue Boolean confirm, Boolean eraseUsersAndSettings, String fullResetConfirmation) {}

    @PostMapping("/reset")
    public DatabaseResetService.ResetResult reset(@Valid @RequestBody Confirmation confirmation) {
        if (!Boolean.TRUE.equals(confirmation.confirm()))
            throw new IllegalArgumentException("Se requiere confirm=true para reiniciar la base de datos");
        if (Boolean.TRUE.equals(confirmation.eraseUsersAndSettings()) && !"BORRAR TODO".equals(confirmation.fullResetConfirmation()))
            throw new IllegalArgumentException("Escribe BORRAR TODO para confirmar el reinicio completo");
        return service.reset(Boolean.TRUE.equals(confirmation.eraseUsersAndSettings()));
    }
}
