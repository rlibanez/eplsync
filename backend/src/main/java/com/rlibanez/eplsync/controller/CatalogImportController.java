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

    public record PreviewRequest(@jakarta.validation.constraints.NotBlank String token) {}

    @GetMapping("/preview/{token}")
    public ResponseEntity<ImportResult> retainedPreview(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(catalogImportService.retainedPreview(token));
    }
    @PostMapping("/preview/apply")
    public ImportResult applyPreview(@jakarta.validation.Valid @RequestBody PreviewRequest request) {
        return catalogImportService.applyPreview(request.token());
    }
    @PostMapping("/preview/refresh")
    public ImportResult refreshPreview(@jakarta.validation.Valid @RequestBody PreviewRequest request) {
        return catalogImportService.refreshPreview(request.token());
    }
    @PostMapping("/preview/discard")
    public ResponseEntity<Void> discardPreview(@jakarta.validation.Valid @RequestBody PreviewRequest request) {
        catalogImportService.discardPreview(request.token());
        return ResponseEntity.noContent().build();
    }

    public enum Source { URL, SAVED }
    public record RunRequest(@jakarta.validation.constraints.NotNull Source source,
                             boolean dryRun,
                             String url, String archiveId, Boolean includeDetails,
                             @Min(0) Integer page, @Min(1) Integer size) {}
    public record ImportSource(String defaultUrl, com.rlibanez.eplsync.service.CatalogImportStore.Archive archive) {}
    @GetMapping("/source")
    public ResponseEntity<ImportSource> source() {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ImportSource(catalogImportService.defaultUrl(), catalogImportService.savedArchive()));
    }
    @PostMapping(value = "/run", consumes = "application/json")
    public Object run(@RequestBody java.util.Map<String,Object> body, jakarta.servlet.http.HttpServletRequest servletRequest) {
        var request = com.rlibanez.eplsync.api.OperationBody.read(body,RunRequest.class,servletRequest);
        var mode = request.dryRun() ? CatalogImportService.Mode.PREVIEW : CatalogImportService.Mode.UPDATE;
        if (request.source() == Source.SAVED) {
            if (request.page() != null || request.size() != null || Boolean.TRUE.equals(request.includeDetails()))
                throw new IllegalArgumentException("El detalle paginado solo se admite al previsualizar una URL");
            if (request.archiveId() == null || request.archiveId().isBlank()) throw new IllegalArgumentException("Falta archiveId");
            return catalogImportService.runSaved(request.archiveId(), mode);
        }
        String url = validateUrl(request.url());
        if (request.dryRun()) {
            var preview = catalogImportService.previewCatalog(url,request.page() == null ? 0 : request.page(),request.size() == null ? 50 : request.size());
            return Boolean.TRUE.equals(request.includeDetails()) ? preview : preview.summary();
        }
        if (request.page() != null || request.size() != null || Boolean.TRUE.equals(request.includeDetails()))
            throw new IllegalArgumentException("El detalle paginado solo se admite al previsualizar una URL");
        return catalogImportService.updateCatalog(url);
    }
    public record UploadRequest(boolean dryRun) {}
    @PostMapping(value = "/run", consumes = "multipart/form-data")
    public ImportResult upload(@RequestPart("file") org.springframework.web.multipart.MultipartFile file,
                              @RequestPart("options") java.util.Map<String,Object> body,
                              jakarta.servlet.http.HttpServletRequest request) {
        var options = com.rlibanez.eplsync.api.OperationBody.read(body,UploadRequest.class,request);
        return catalogImportService.runUpload(file, options.dryRun() ? CatalogImportService.Mode.PREVIEW : CatalogImportService.Mode.UPDATE);
    }

    private String validateUrl(String url) {
        if (url != null && url.isBlank()) {
            url = null;
        }

        if (url != null) {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getHost().isBlank() || scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("La URL debe usar HTTP o HTTPS");
            }
        }

        return url;
    }
}
