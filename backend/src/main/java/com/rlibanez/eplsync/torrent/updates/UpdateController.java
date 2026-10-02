package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import jakarta.validation.Valid;

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
        if (input.includeDetails() != null || input.selection() != null || input.all() != null || input.sort() != null)
            throw new IllegalArgumentException("selection, all y sort no se admiten en updates");
        if (input.dryRun()) return ResponseEntity.ok(SelectionQueries.page(planner.preview(input.filter(),
                Boolean.TRUE.equals(input.includeNotFound()), input.multipleHashes(), UpdatePlanner.Selection.UPDATES),
                input.pageNumber(), input.pageSize()));
        if (input.paginated()) throw new IllegalArgumentException("page y size solo paginan la previsualización");
        synchronized (bulk) {
            var job = planner.create(input.filter(), Boolean.TRUE.equals(input.includeNotFound()), input.update(), UpdatePlanner.Selection.UPDATES);
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }

    @GetMapping("/{jobId}")
    public UpdateCleanupService.View view(@PathVariable String jobId,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        validate(params); return cleanup.view(jobId);
    }

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
