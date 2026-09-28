package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

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

    @GetMapping
    public PageResponse<UpdatePlanner.Candidate> preview(@RequestParam(required = false) Long eplId,
            @RequestParam(defaultValue = "false") boolean includeNotFound,
            @RequestParam(required = false) String multipleHashes,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        validate(params, "eplId", "includeNotFound", "multipleHashes", "page", "size");
        if (page < 0 || size < 1) throw new IllegalArgumentException("page >= 0 y size > 0");
        var result = planner.preview(eplId, includeNotFound, MultipleHashes.parse(multipleHashes));
        long offset = (long) page * size;
        var selected = offset >= result.size() ? List.<UpdatePlanner.Candidate>of()
                : result.subList((int) offset, (int) Math.min(offset + size, result.size()));
        int pages = (int) ((result.size() + (long) size - 1) / size);
        return new PageResponse<>(selected, new PageResponse.PageMeta(page, size, result.size(), pages,
                page == 0, page >= pages - 1, page < pages - 1, page > 0));
    }

    @PostMapping
    public ResponseEntity<BulkStore.View> create(@RequestParam(required = false) Long eplId,
            @RequestParam(defaultValue = "false") boolean includeNotFound,
            @RequestBody(required = false) UpdateRequest request,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        validate(params, "eplId", "includeNotFound");
        synchronized (bulk) {
            log.info("Solicitud de actualización: eplId={}, includeNotFound={}, previousVersions={}", eplId,
                    includeNotFound, request == null ? PreviousVersions.KEEP : request.policy());
            var job = planner.create(eplId, includeNotFound, request);
            log.info("Trabajo de actualización creado: jobId={}, libros={}", job.jobId(), job.selectedBooks());
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }

    @GetMapping("/{jobId}")
    public UpdateCleanupService.View view(@PathVariable String jobId,
            @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        validate(params); return cleanup.view(jobId);
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
