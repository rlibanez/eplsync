package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CatalogMissingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_DELETE')")
@RestController
@RequestMapping("/api/catalog/import/missing")
public class CatalogMissingController {
    private final CatalogMissingService service;
    public CatalogMissingController(CatalogMissingService service) { this.service = service; }
    public record DeleteRequest(@NotBlank String token, boolean confirm) {}
    @PostMapping("/preview") public CatalogMissingService.Preview preview() { return service.preview(); }
    @GetMapping("/{token}") public CatalogMissingService.Preview page(@PathVariable String token,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.page(token, page, size);
    }
    @PostMapping("/delete") public CatalogMissingService.Result delete(@Valid @RequestBody DeleteRequest request) {
        return service.delete(request.token(), request.confirm());
    }
}
