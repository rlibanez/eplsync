package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SEND')")
@RestController
@RequestMapping("/api/torrent/updates")
public class UpdateController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UpdateController.class);
    private final UpdatePlanner planner;
    private final UpdateCleanupService cleanup;
    private final BulkStore bulk;
    public UpdateController(UpdatePlanner planner, UpdateCleanupService cleanup, BulkStore bulk) {
        this.planner = planner; this.cleanup = cleanup; this.bulk = bulk;
    }

    @PostMapping
    public ResponseEntity<?> run(@RequestBody java.util.Map<String,Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        var input = com.rlibanez.eplsync.api.OperationBody.read(body, TorrentOperationRequest.class, request);
        log.info("Solicitud torrent: dryRun={}, filtros={}", input.dryRun(), SelectionQueries.safeLog(input.filters()));
        if (input.detailPage()!=null || input.detailSize()!=null || input.includeDetails() != null || input.selection() != null || input.all() != null || input.sort() != null)
            throw new IllegalArgumentException("selection, all y sort no se admiten en updates");
        if (input.dryRun()) com.rlibanez.eplsync.config.QueryLimits.page(input.pageNumber(), input.pageSize());
        if (input.dryRun()) return ResponseEntity.ok(planner.previewPage(input.filter(),
                Boolean.TRUE.equals(input.includeNotFound()), input.multipleHashes(), UpdatePlanner.Selection.UPDATES,input.pageNumber(),input.pageSize()));
        if (input.paginated()) throw new IllegalArgumentException("page y size solo paginan la previsualización");
        synchronized (bulk) {
            var job = planner.create(input.filter(), Boolean.TRUE.equals(input.includeNotFound()), input.update(), UpdatePlanner.Selection.UPDATES);
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_JOBS_MANAGE')")
    @GetMapping("/{jobId}")
    public UpdateCleanupService.View view(@PathVariable String jobId,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        validate(params,"page","size"); return cleanup.view(jobId,page,size);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_CLEANUP')")
    @PostMapping("/cleanup")
    public UpdateCleanupService.GlobalResult cleanAll(
            @RequestParam(defaultValue = "false") boolean retryUnconfirmed,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        validate(params, "retryUnconfirmed");
        synchronized (bulk) {
            log.info("Solicitud de limpieza global: retryUnconfirmed={}", retryUnconfirmed);
            return cleanup.cleanAll(retryUnconfirmed);
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_CLEANUP')")
    @PostMapping("/{jobId}/cleanup")
    public UpdateCleanupService.View clean(@PathVariable String jobId,
            @RequestParam(defaultValue = "false") boolean retryUnconfirmed,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        validate(params, "retryUnconfirmed");
        synchronized (bulk) {
            log.info("Solicitud de limpieza: jobId={}", jobId);
            return cleanup.clean(jobId, retryUnconfirmed);
        }
    }
    private void validate(org.springframework.util.MultiValueMap<String, String> params, String... allowed) {
        var names = java.util.Set.of(allowed);
        params.forEach((key, values) -> {
            if (!names.contains(key) || values.size() != 1 || values.getFirst().isBlank())
                throw new IllegalArgumentException("Parámetro desconocido, vacío o repetido: " + key);
        });
    }

}
