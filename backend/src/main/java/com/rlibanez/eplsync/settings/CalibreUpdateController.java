package com.rlibanez.eplsync.settings;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController
@RequestMapping("/api/calibre/updates")
@PreAuthorize("hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ') and hasAuthority('CATALOG_READ')")
public class CalibreUpdateController {
    private final CalibreUpdateFlow flow;
    public CalibreUpdateController(CalibreUpdateFlow flow) {this.flow=flow;}
    public record Selection(List<Long> ids) {}
    @PreAuthorize("hasAuthority('CALIBRE_READ') and hasAuthority('DOWNLOADS_FILES_READ') and hasAuthority('CATALOG_READ')")
    @PostMapping("/availability") public List<CalibreUpdateFlow.Availability> availability(@RequestBody Selection selection) {return flow.availability(selection.ids());}
    @PostMapping("/prepare") public List<CalibreUpdateFlow.Book> prepare(@RequestBody Selection selection) {return flow.prepare(selection.ids());}
    @PostMapping public CalibreUpdateFlow.Result create(@RequestBody CalibreUpdateFlow.Request request) {return flow.create(request);}
}
