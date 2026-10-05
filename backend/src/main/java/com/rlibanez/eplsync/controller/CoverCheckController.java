package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CoverCheckService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('COVERS_MANAGE')")
@RestController
@RequestMapping("/api/catalog/covers/check")
public class CoverCheckController {
    private final CoverCheckService service;
    public CoverCheckController(CoverCheckService service) { this.service = service; }

    public record Check(boolean dryRun, @jakarta.validation.constraints.Min(0) Long afterId,
            @jakarta.validation.constraints.Min(1) Long eplId, @jakarta.validation.constraints.Min(1) Integer size,
            Boolean onlyUnchecked, Boolean coverAvailable) {}
    @PostMapping
    public ResponseEntity<CoverCheckService.Report> check(@RequestBody java.util.Map<String,Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        var input = com.rlibanez.eplsync.api.OperationBody.read(body, Check.class, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.check(input.dryRun(),
                input.afterId() == null ? 0 : input.afterId(), input.eplId(), input.size(),
                !Boolean.FALSE.equals(input.onlyUnchecked())).filterAvailability(input.coverAvailable()));
    }

    @ExceptionHandler(CoverCheckService.BusyException.class)
    ResponseEntity<java.util.Map<String, String>> busy() {
        return ResponseEntity.status(409).body(java.util.Map.of("message", "Ya hay una comprobación de portadas en curso"));
    }
}
