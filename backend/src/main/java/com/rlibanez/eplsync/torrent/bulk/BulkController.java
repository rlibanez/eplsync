package com.rlibanez.eplsync.torrent.bulk;

import java.util.Objects;

import com.rlibanez.eplsync.dto.PageResponse;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_JOBS_MANAGE')")
@RestController
@RequestMapping("/api/torrent")
public class BulkController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkController.class);
    private final BulkStore store;
    public BulkController(BulkStore store) { this.store = store; }

    @GetMapping("/jobs")
    public PageResponse<BulkStore.View> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(required=false) BulkJob.Type type, @RequestParam(required=false) String username,
            @RequestParam(required=false) java.time.Instant from, @RequestParam(required=false) java.time.Instant before,
            jakarta.servlet.http.HttpServletRequest request) {
        var allowed = java.util.Set.of("page", "size", "status", "sort", "type", "username", "from", "before");
        if (!allowed.containsAll(request.getParameterMap().keySet()))
            throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro desconocido; se admiten page, size, status, sort, type, username, from y before");
        com.rlibanez.eplsync.config.TableOrdering.validateParameters(request);
        java.util.List<BulkJob.State> states = null;
        if (status != null) {
            try {
                states = java.util.Arrays.stream(status.split(",", -1))
                        .map(value -> Objects.requireNonNull(value).trim()).map(BulkJob.State::valueOf).distinct().toList();
            } catch (IllegalArgumentException ex) {
                throw new com.rlibanez.eplsync.exception.UserInputException("status debe contener estados de job válidos separados por comas");
            }
        }
        sort = com.rlibanez.eplsync.config.TableOrdering.request(request, "createdAt,desc");
        synchronized (store) { return store.list(page, size, states, sort,type,username,from,before); }
    }

    @GetMapping("/jobs/{id}")
    public BulkStore.View get(@PathVariable String id) {
        synchronized (store) { return store.view(id); }
    }

    @GetMapping("/jobs/{id}/items")
    public PageResponse<BulkStore.ItemView> items(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "position,asc") String sort, @RequestParam(required = false) String status, jakarta.servlet.http.HttpServletRequest request) {
        if (!java.util.Set.of("page", "size", "status", "sort").containsAll(request.getParameterMap().keySet()))
            throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro desconocido; se admiten page, size, status y sort");
        com.rlibanez.eplsync.config.TableOrdering.validateParameters(request);
        java.util.List<BulkItem.State> states = null;
        if (status != null) {
            try {
                states = java.util.Arrays.stream(status.split(",", -1)).map(value -> Objects.requireNonNull(value).trim())
                        .map(BulkItem.State::valueOf).distinct().toList();
            } catch (IllegalArgumentException ex) {
                throw new com.rlibanez.eplsync.exception.UserInputException("status debe contener estados de elemento válidos separados por comas");
            }
        }
        sort = com.rlibanez.eplsync.config.TableOrdering.request(request, "position,asc");
        synchronized (store) { return store.details(id, page, size, states, sort); }
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
