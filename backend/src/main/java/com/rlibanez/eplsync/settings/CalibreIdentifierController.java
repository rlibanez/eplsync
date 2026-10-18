package com.rlibanez.eplsync.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/calibre/identifiers")
@PreAuthorize("hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_FILES_READ') and hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
public class CalibreIdentifierController {
    private final CalibreIdentifiers identifiers;
    public CalibreIdentifierController(CalibreIdentifiers identifiers) {this.identifiers=identifiers;}
    public record Selection(long calibreId,String operationId) {}
    public record Confirmation(String token,String operationId) {}
    public record Cancellation(String operationId) {}
    @PostMapping("/cancel") public java.util.Map<String,Boolean> cancel(@RequestBody Cancellation input) {identifiers.cancel(input.operationId());return java.util.Map.of("cancelled",true);}
    @PostMapping("/preview") public CalibreIdentifiers.Preview preview(@RequestBody Selection input) {return identifiers.preview(input.calibreId(),input.operationId());}
    @PostMapping("/apply") public CalibreIdentifiers.Preview apply(@RequestBody Confirmation input) {return identifiers.apply(input.token(),input.operationId());}
}
