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
    private final com.rlibanez.eplsync.service.MagnetExportService exports;

    public CatalogMagnetController(CatalogMagnetService service, com.rlibanez.eplsync.service.MagnetExportService exports) {
        this.service = service; this.exports = exports;
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

    /** Prepare before committing the response so preparation errors remain readable JSON. */
    @PostMapping(value = "/magnets/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportSelection(
            @Valid @RequestBody ExportSelection selection, jakarta.servlet.http.HttpServletRequest request) {
        return exportResponse(selection.filters(), Sort.by("eplId"), request);
    }

    @GetMapping(value = "/magnets/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> export(
            @Valid @ModelAttribute CatalogBookFilter filter, Sort sort, jakarta.servlet.http.HttpServletRequest request) {
        return exportResponse(filter, sort, request);
    }
    private ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportResponse(
            CatalogBookFilter filter, Sort sort, jakarta.servlet.http.HttpServletRequest request) {
        var file = exports.prepare(filter, sort);
        try {
            var async = org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(request);
            async.getAsyncWebRequest().setTimeout(com.rlibanez.eplsync.service.MagnetExportService.TRANSFER_TIME.toMillis());
            async.getAsyncWebRequest().addCompletionHandler(file::close);
            async.registerCallableInterceptor(file, new org.springframework.web.context.request.async.CallableProcessingInterceptor() {
                @Override public <T> void afterCompletion(org.springframework.web.context.request.NativeWebRequest ignored,
                        java.util.concurrent.Callable<T> task) { file.close(); }
                @Override public <T> Object handleTimeout(org.springframework.web.context.request.NativeWebRequest ignored,
                        java.util.concurrent.Callable<T> task) { file.close(); return RESULT_NONE; }
                @Override public <T> Object handleError(org.springframework.web.context.request.NativeWebRequest ignored,
                        java.util.concurrent.Callable<T> task, Throwable error) { file.close(); return RESULT_NONE; }
            });
            return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8")).header("Content-Disposition", "attachment; filename=\"magnets.txt\"")
                    .header("Cache-Control", "no-store").contentLength(file.size()).body(file::writeTo);
        } catch (RuntimeException | java.io.IOException ex) { file.close(); throw new IllegalStateException("No se pudo iniciar la exportación", ex); }
    }
}
