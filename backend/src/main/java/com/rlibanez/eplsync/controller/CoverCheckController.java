package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CoverCheckService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog/covers/check")
public class CoverCheckController {
    private final CoverCheckService service;
    public CoverCheckController(CoverCheckService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<CoverCheckService.Report> preview(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(required = false) Long eplId,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "true") boolean onlyUnchecked,
            @RequestParam(required = false) Boolean coverAvailable) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.check(true, afterId, eplId, size, onlyUnchecked).filterAvailability(coverAvailable));
    }

    @PostMapping
    public ResponseEntity<CoverCheckService.Report> apply(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(required = false) Long eplId,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "true") boolean onlyUnchecked) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.check(false, afterId, eplId, size, onlyUnchecked));
    }

    @ExceptionHandler(CoverCheckService.BusyException.class)
    ResponseEntity<java.util.Map<String, String>> busy() {
        return ResponseEntity.status(409).body(java.util.Map.of("message", "Ya hay una comprobación de portadas en curso"));
    }
}
