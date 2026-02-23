package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.ImportResult;
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

    public CatalogImportController(CatalogImportService catalogImportService) {
        this.catalogImportService = catalogImportService;
    }

    /**
     * Inicia la importación del catálogo.
     * Si no se proporciona URL, usa la URL oficial de ePubLibre.
     * 
     * @param url URL personalizada del ZIP (opcional).
     * @return Resultado de la importación.
     */
    @PostMapping
    public ResponseEntity<ImportResult> importCatalog(
            @RequestParam(required = false) @URL(message = "La URL no es válida") String url) {

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

        ImportResult result = (url != null)
                ? catalogImportService.importCatalog(url)
                : catalogImportService.importCatalog();

        return ResponseEntity.ok(result);
    }
}
