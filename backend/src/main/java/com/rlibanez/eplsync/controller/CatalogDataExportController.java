package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CatalogDataExport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/catalog/data/export")
@PreAuthorize("hasAuthority('CATALOG_READ')")
public class CatalogDataExportController {
    private final CatalogDataExport exports;

    public CatalogDataExportController(CatalogDataExport exports) {
        this.exports = exports;
    }

    @PostMapping("/preview")
    public CatalogDataExport.Preview preview(@Valid @RequestBody CatalogDataExport.Request selection) {
        return exports.preview(selection);
    }

    @PostMapping
    public ResponseEntity<StreamingResponseBody> export(@Valid @RequestBody CatalogDataExport.Request selection,
            HttpServletRequest request) {
        return CatalogMagnetController.attachment(exports.prepare(selection), selection.filename(),
                selection.format().contentType(), request);
    }
}
