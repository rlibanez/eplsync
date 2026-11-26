package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CalibreDataExport;
import com.rlibanez.eplsync.service.CatalogDataExport;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/calibre/data/export")
@PreAuthorize("hasAuthority('CALIBRE_READ')")
public class CalibreDataExportController {
    private final CalibreDataExport exports;
    public CalibreDataExportController(CalibreDataExport exports) {this.exports=exports;}
    @PostMapping("/preview")
    public CatalogDataExport.Preview preview(@Valid @RequestBody CalibreDataExport.Request request) {return exports.preview(request);}
    @PostMapping
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> export(
            @Valid @RequestBody CalibreDataExport.Request input,jakarta.servlet.http.HttpServletRequest request) {
        return CatalogMagnetController.attachment(exports.prepare(input),input.filename(),input.format().contentType(),request);
    }
}
