package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/torrent")
public class BulkController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkController.class);
    private final BulkStore store;
    public BulkController(BulkStore store) { this.store = store; }

    @PostMapping("/books")
    public ResponseEntity<BulkStore.View> create(@Valid @ModelAttribute CatalogBookFilter filter,
            Pageable pageable, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(defaultValue = "false") boolean all,
            @RequestBody(required = false) BulkRequest request, jakarta.servlet.http.HttpServletRequest servletRequest) {
        var known = new java.util.HashSet<String>(java.util.List.of("page", "size", "sort", "all"));
        for (var field : CatalogBookFilter.class.getDeclaredFields()) known.add(field.getName());
        for (String parameter : servletRequest.getParameterMap().keySet())
            if (!known.contains(parameter)) throw new IllegalArgumentException("Parámetro de selección no admitido: " + parameter);
        if ((page != null && page < 0) || (size != null && (size < 1 || size > 2000)))
            throw new IllegalArgumentException("page >= 0 y size entre 1 y 2000");
        log.info("Solicitud bulk: filtros={}, page={}, size={}, sort={}, all={}",
                safeLog(filter), page, size, safeLog(pageable.getSort()), all);
        synchronized (store) {
            var job = store.create(filter, pageable, page != null || size != null, all, request);
            log.info("Trabajo bulk creado: jobId={}, libros={}, torrents={}, política={}, batchSize={}, concurrency={}, interval={}",
                    job.jobId(), job.selectedBooks(), job.selectedTorrents(), job.multipleHashes(), job.batchSize(), job.concurrency(), job.interval());
            return ResponseEntity.accepted().location(URI.create("/api/torrent/jobs/" + job.jobId())).body(job);
        }
    }

    @GetMapping("/jobs")
    public PageResponse<BulkStore.View> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
            jakarta.servlet.http.HttpServletRequest request) {
        var allowed = java.util.Set.of("page", "size", "status");
        if (!allowed.containsAll(request.getParameterMap().keySet()))
            throw new IllegalArgumentException("Parámetro desconocido; se admiten page, size y status");
        for (var values : request.getParameterMap().values())
            if (values.length != 1 || values[0].isBlank()) throw new IllegalArgumentException("Parámetro vacío o repetido");
        java.util.List<BulkJob.State> states = null;
        if (status != null) {
            try {
                states = java.util.Arrays.stream(status.split(",", -1))
                        .map(String::trim).map(BulkJob.State::valueOf).distinct().toList();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("status debe contener estados de job válidos separados por comas");
            }
        }
        synchronized (store) { return store.list(page, size, states); }
    }

    @GetMapping("/jobs/{id}")
    public BulkStore.View get(@PathVariable String id) {
        synchronized (store) { return store.view(id); }
    }

    @GetMapping("/jobs/{id}/items")
    public PageResponse<BulkStore.ItemView> items(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        synchronized (store) { return store.details(id, page, size); }
    }

    @PostMapping("/jobs/{id}/pause")
    public BulkStore.View pause(@PathVariable String id) { return control(id, "pause"); }
    @PostMapping("/jobs/{id}/resume")
    public BulkStore.View resume(@PathVariable String id) { return control(id, "resume"); }
    @PostMapping("/jobs/{id}/cancel")
    public BulkStore.View cancel(@PathVariable String id) { return control(id, "cancel"); }
    private BulkStore.View control(String id, String action) {
        synchronized (store) {
            var result = store.control(id, action);
            log.info("Control bulk: jobId={}, acción={}, estado={}", safeLog(id), action, result.status());
            return result;
        }
    }
    private static String safeLog(Object value) {
        String text = String.valueOf(value).replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        return text.substring(0, Math.min(text.length(), 2000));
    }
}
