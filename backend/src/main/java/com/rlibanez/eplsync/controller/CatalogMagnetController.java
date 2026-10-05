package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.service.CatalogMagnetService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ')")
@RestController
@RequestMapping("/api/catalog")
public class CatalogMagnetController {
    private final CatalogMagnetService service;

    public CatalogMagnetController(CatalogMagnetService service) {
        this.service = service;
    }

    @GetMapping(value = "/books/{eplId}/magnets", produces = "application/json")
    public ResponseEntity<List<String>> forBook(@PathVariable Long eplId) {
        return service.forBook(eplId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/magnets", produces = "application/json")
    public Object search(@Valid @ModelAttribute CatalogBookFilter filter, Sort sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, jakarta.servlet.http.HttpServletRequest request) {
        for (String key : java.util.List.of("page", "size")) {
            if (request.getParameterValues(key) != null && request.getParameterValues(key).length != 1)
                throw new IllegalArgumentException("Parámetro repetido: " + key);
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        com.rlibanez.eplsync.config.QueryLimits.page(pageNumber, pageSize);
        return service.page(filter, sort, pageNumber, pageSize);
    }

    public record ExportSelection(@jakarta.validation.constraints.NotNull @Valid CatalogBookFilter filters) {}

    /** Read-only export with a body, so large explicit selections do not exceed URL limits. */
    @PostMapping(value = "/magnets/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> exportSelection(@Valid @RequestBody ExportSelection selection) {
        var magnets = service.search(selection.filters(), Sort.by("eplId"));
        return ResponseEntity.ok().body(magnets.isEmpty() ? "" : String.join("\n", magnets) + "\n");
    }

    @GetMapping(value = "/magnets/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> export(@Valid @ModelAttribute CatalogBookFilter filter, Sort sort) {
        var magnets = service.search(filter, sort);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"magnets.txt\"")
                .body(magnets.isEmpty() ? "" : String.join("\n", magnets) + "\n");
    }
}
