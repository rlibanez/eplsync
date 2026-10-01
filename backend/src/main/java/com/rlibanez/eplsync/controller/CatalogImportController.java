package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.ImportResult;
import jakarta.validation.constraints.Min;
import com.rlibanez.eplsync.service.CatalogImportService;

import org.hibernate.validator.constraints.URL;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Controller REST para importar el catálogo de libros de ePubLibre.
 */
@RestController
@RequestMapping("/api/catalog/import")
@Validated
public class CatalogImportController {

    private final CatalogImportService catalogImportService;
    private final com.rlibanez.eplsync.repository.CatalogMetadataRepository metadata;

    public CatalogImportController(CatalogImportService catalogImportService, com.rlibanez.eplsync.repository.CatalogMetadataRepository metadata) {
        this.metadata = metadata;
        this.catalogImportService = catalogImportService;
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> metadata() {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(java.util.Collections.singletonMap("metadata", metadata.findById(1L).orElse(null)));
    }

    /**
     * Reemplaza todo el catálogo local por el catálogo importado.
     * Si no se proporciona URL, usa eplsync.catalog.zip-url.
     * 
     * @param url URL personalizada del ZIP (opcional).
     * @return Resultado de la importación.
     */
    @PostMapping("/reset")
    public ResponseEntity<ImportResult> importCatalog(
            @RequestParam(required = false) @URL(message = "La URL no es válida") String url) {

        return ResponseEntity.ok(catalogImportService.importCatalog(validateUrl(url)));
    }

    /** Actualiza el catálogo conservando los libros y sus fechas de alta. */
    @PostMapping("/update")
    public ResponseEntity<ImportResult> updateCatalog(
            @RequestParam(required = false) @URL(message = "La URL no es válida") String url) {
        return ResponseEntity.ok(catalogImportService.updateCatalog(validateUrl(url)));
    }

    /** Devuelve el resumen del dry-run; includeDetails permite consultar el detalle paginado. */
    @PostMapping("/preview")
    public ResponseEntity<?> previewCatalog(
            @RequestParam(required = false) @URL(message = "La URL no es válida") String url,
            @RequestParam(defaultValue = "false") boolean includeDetails,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) int size) {
        var preview = catalogImportService.previewCatalog(validateUrl(url), page, size);
        return ResponseEntity.ok(includeDetails ? preview : preview.summary());
    }

    private String validateUrl(String url) {
        if (url != null && url.isBlank()) {
            url = null;
        }

        if (url != null) {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("La URL debe usar HTTP o HTTPS");
            }
        }

        return url;
    }
}
