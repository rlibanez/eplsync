package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.service.CoverTaskService;
import com.rlibanez.eplsync.service.CoverCheckService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog/covers")
public class CoverTaskController {
    private final CoverTaskService tasks;
    public CoverTaskController(CoverTaskService tasks) { this.tasks = tasks; }
    public record Start(Boolean dryRun, Boolean onlyUnchecked, CoverTaskService.Options options) {}

    @GetMapping("/config")
    public CoverTaskService.Options config() { return tasks.defaults(); }

    public record Current(CoverTaskService.Status task) {}

    @GetMapping("/task")
    public ResponseEntity<Current> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Current(tasks.current()));
    }

    @PostMapping("/task")
    public ResponseEntity<CoverTaskService.Status> start(@RequestBody Start request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(tasks.start(Boolean.TRUE.equals(request.dryRun()), Boolean.TRUE.equals(request.onlyUnchecked()), request.options()));
    }

    @ExceptionHandler(CoverCheckService.BusyException.class)
    ResponseEntity<java.util.Map<String, String>> busy() {
        return ResponseEntity.status(409).body(java.util.Map.of("code", "CHECK_BUSY"));
    }
}
