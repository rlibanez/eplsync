package com.rlibanez.eplsync.jobs;

import java.util.List;
import com.rlibanez.eplsync.settings.JobRetries;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/job-retries/{kind}/{id}")
@org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
public class JobRetryController {
    private final JobRetries retries;
    public JobRetryController(JobRetries retries) {this.retries=retries;}
    public record Selection(List<String> items,boolean failedOnly) {}
    public record Confirmation(String token,boolean confirmChanges) {}
    @GetMapping public JobRetries.History history(@PathVariable String kind,@PathVariable String id) {return retries.history(kind,id);}
    @PostMapping("/preview") public JobRetries.Preview preview(@PathVariable String kind,@PathVariable String id,@RequestBody Selection selection) {return retries.preview(kind,id,selection);}
    @PostMapping public JobRetries.Link create(@PathVariable String kind,@PathVariable String id,@RequestBody Confirmation confirmation) {return retries.create(kind,id,confirmation);}
}
