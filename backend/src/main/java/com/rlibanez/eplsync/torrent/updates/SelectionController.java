package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** New library entries and revision updates, selected against local client history. */
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SEND')")
@RestController
@RequestMapping("/api/torrent")
public class SelectionController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SelectionController.class);
    private final UpdatePlanner planner;
    private final BulkStore bulk;

    public SelectionController(UpdatePlanner planner, BulkStore bulk) {
        this.planner = planner; this.bulk = bulk;
    }

    @PostMapping("/books")
    public ResponseEntity<?> books(@RequestBody java.util.Map<String,Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        var input = com.rlibanez.eplsync.api.OperationBody.read(body,TorrentOperationRequest.class,request);
        log.info("Solicitud torrent: dryRun={}, filtros={}", input.dryRun(), SelectionQueries.safeLog(input.filters()));
        if (input.selection() != null) {
            if (!"new".equals(input.selection())) throw new IllegalArgumentException("selection debe ser new");
            if (input.includeNotFound() != null || input.previousVersions() != null)
                throw new IllegalArgumentException("Las novedades no admiten includeNotFound ni previousVersions");
            return selected(input, UpdatePlanner.Selection.NEW);
        }
        if (input.includeNotFound() != null || input.previousVersions() != null)
            throw new IllegalArgumentException("El envío general no admite includeNotFound ni previousVersions");
        synchronized (bulk) {
            if (input.dryRun()) return ResponseEntity.ok(bulk.preview(input.filter(), input.pageable(),
                    input.paginated(), Boolean.TRUE.equals(input.all()), input.bulk(), Boolean.TRUE.equals(input.includeDetails())));
            var job = bulk.create(input.filter(),input.pageable(),input.paginated(),Boolean.TRUE.equals(input.all()),input.bulk());
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody java.util.Map<String,Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        return selected(com.rlibanez.eplsync.api.OperationBody.read(body,TorrentOperationRequest.class,request), UpdatePlanner.Selection.BOTH);
    }
    private ResponseEntity<?> selected(TorrentOperationRequest input, UpdatePlanner.Selection selection) {
        if (input.includeDetails() != null || input.all() != null || input.sort() != null || selection == UpdatePlanner.Selection.BOTH && input.selection() != null)
            throw new IllegalArgumentException("Opciones de selección no admitidas");
        if (input.dryRun()) return ResponseEntity.ok(SelectionQueries.page(UpdatePlanner.visible(planner.preview(input.filter(),
                Boolean.TRUE.equals(input.includeNotFound()),input.multipleHashes(),selection)),input.pageNumber(),input.pageSize()));
        if (input.paginated()) throw new IllegalArgumentException("page y size solo paginan la previsualización");
        synchronized (bulk) {
            var job = planner.create(input.filter(),Boolean.TRUE.equals(input.includeNotFound()),input.update(),selection);
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/"+job.jobId())).body(job);
        }
    }
}
