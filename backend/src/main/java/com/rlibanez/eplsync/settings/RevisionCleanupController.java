package com.rlibanez.eplsync.settings;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.rlibanez.eplsync.dto.PageResponse;

@RestController
@RequestMapping("/api/revision-cleanups")
@PreAuthorize("isAuthenticated()")
public class RevisionCleanupController {
    private final RevisionCleanup cleanup;
    public RevisionCleanupController(RevisionCleanup cleanup) {this.cleanup=cleanup;}
    @PostMapping("/preview") public RevisionCleanup.Job preview(@RequestBody RevisionCleanup.PreviewInput input) {return cleanup.preview(input);}
    @GetMapping public PageResponse<RevisionCleanup.Job> jobs(@RequestParam String source,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return cleanup.jobs(source,page,size);}
    @GetMapping("/{id}") public RevisionCleanup.Job job(@PathVariable String id) {return cleanup.get(id);}
    @GetMapping("/{id}/items") public PageResponse<RevisionCleanup.Item> items(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return cleanup.items(id,page,size);}
    @PostMapping("/{id}/start") public RevisionCleanup.Job start(@PathVariable String id,@RequestBody RevisionCleanup.Confirmation input) {return cleanup.start(id,input);}
    @PostMapping("/{id}/retry") public RevisionCleanup.Job retry(@PathVariable String id,@RequestBody RevisionCleanup.RetryInput input) {return cleanup.retryUnconfirmed(id,input);}
    @PostMapping("/{id}/{command:cancel|continue|verify}") public RevisionCleanup.Job control(@PathVariable String id,@PathVariable String command) {return cleanup.control(id,command);}
}
