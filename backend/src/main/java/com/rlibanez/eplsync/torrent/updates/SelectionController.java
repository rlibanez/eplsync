package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.torrent.bulk.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** New library entries and revision updates, selected against local client history. */
@RestController
@RequestMapping("/api/torrent")
public class SelectionController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SelectionController.class);
    private final UpdatePlanner planner;
    private final BulkStore bulk;

    public SelectionController(UpdatePlanner planner, BulkStore bulk) {
        this.planner = planner; this.bulk = bulk;
    }

    @GetMapping(value = "/books", params = "selection")
    public PageResponse<UpdatePlanner.Candidate> previewNew(@Valid @ModelAttribute CatalogBookFilter filter,
            @RequestParam String selection, @RequestParam(required = false) String multipleHashes,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam MultiValueMap<String, String> params) {
        SelectionQueries.validate(params, "selection", "multipleHashes", "page", "size");
        requireNew(selection);
        return preview(filter, false, multipleHashes, page, size, UpdatePlanner.Selection.NEW);
    }

    @PostMapping(value = "/books", params = "selection")
    public ResponseEntity<BulkStore.View> createNew(@Valid @ModelAttribute CatalogBookFilter filter,
            @RequestParam String selection, @RequestBody(required = false) BulkRequest request,
            @RequestParam MultiValueMap<String, String> params) {
        SelectionQueries.validate(params, "selection");
        requireNew(selection);
        var input = request == null ? null : new UpdateRequest(PreviousVersions.KEEP, request.options(),
                request.batchSize(), request.concurrency(), request.interval(), request.multipleHashes());
        return create(filter, false, input, UpdatePlanner.Selection.NEW);
    }

    @GetMapping("/refresh")
    public PageResponse<UpdatePlanner.Candidate> previewBoth(@Valid @ModelAttribute CatalogBookFilter filter,
            @RequestParam(defaultValue = "false") boolean includeNotFound,
            @RequestParam(required = false) String multipleHashes,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam MultiValueMap<String, String> params) {
        SelectionQueries.validate(params, "includeNotFound", "multipleHashes", "page", "size");
        return preview(filter, includeNotFound, multipleHashes, page, size, UpdatePlanner.Selection.BOTH);
    }

    @PostMapping("/refresh")
    public ResponseEntity<BulkStore.View> createBoth(@Valid @ModelAttribute CatalogBookFilter filter,
            @RequestParam(defaultValue = "false") boolean includeNotFound,
            @RequestBody(required = false) UpdateRequest request,
            @RequestParam MultiValueMap<String, String> params) {
        SelectionQueries.validate(params, "includeNotFound");
        return create(filter, includeNotFound, request, UpdatePlanner.Selection.BOTH);
    }

    private PageResponse<UpdatePlanner.Candidate> preview(CatalogBookFilter filter, boolean includeNotFound,
            String multipleHashes, int page, int size, UpdatePlanner.Selection selection) {
        if (page < 0 || size < 1) throw new IllegalArgumentException("page >= 0 y size > 0");
        return SelectionQueries.page(planner.preview(filter, includeNotFound, MultipleHashes.parse(multipleHashes), selection), page, size);
    }

    private ResponseEntity<BulkStore.View> create(CatalogBookFilter filter, boolean includeNotFound,
            UpdateRequest request, UpdatePlanner.Selection selection) {
        synchronized (bulk) {
            log.info("Solicitud de novedades/actualizaciones: selección={}, filtros={}, includeNotFound={}, previousVersions={}",
                    selection, SelectionQueries.safeLog(filter), includeNotFound,
                    request == null ? PreviousVersions.KEEP : request.policy());
            var job = planner.create(filter, includeNotFound, request, selection);
            log.info("Trabajo de novedades/actualizaciones creado: jobId={}, libros={}, torrents={}",
                    job.jobId(), job.selectedBooks(), job.selectedTorrents());
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }

    private void requireNew(String selection) {
        if (!"new".equals(selection)) throw new IllegalArgumentException("selection debe ser new");
    }
}
