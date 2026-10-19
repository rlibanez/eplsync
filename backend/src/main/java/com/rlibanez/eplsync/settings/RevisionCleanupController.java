package com.rlibanez.eplsync.settings;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.rlibanez.eplsync.dto.PageResponse;

@RestController
@RequestMapping("/api/revision-cleanups")
@PreAuthorize("isAuthenticated()")
public class RevisionCleanupController {
    @org.springframework.beans.factory.annotation.Autowired private CalibreCleanupPreparation preparations;
    private final RevisionCleanup cleanup;
    public RevisionCleanupController(RevisionCleanup cleanup) {this.cleanup=cleanup;}
    @PostMapping({"/calibre-library/prepare","/calibre-library/preview"}) public CalibreCleanupPreparation.Status prepare(@RequestBody CalibreLibraryCleanup.Input input) {return preparations.begin(input);}
    @GetMapping("/{id}/preparation") public CalibreCleanupPreparation.Status preparation(@PathVariable String id) {return preparations.status(id);}
    @PostMapping("/{id}/library-cancel") public CalibreCleanupPreparation.Status cancelPreparation(@PathVariable String id) {return preparations.cancel(id);}
    @PostMapping("/{id}/library-selection") public CalibreCleanupPreparation.Status select(@PathVariable String id,@RequestBody CalibreCleanupPreparation.Selection input) {return preparations.select(id,input);}
    @PostMapping("/{id}/library-retained") public CalibreCleanupPreparation.Status retained(@PathVariable String id,@RequestBody CalibreCleanupPreparation.Retained input) {return preparations.retain(id,input);}
    @PostMapping("/{id}/library-confirm") public RevisionCleanup.Job confirmLibrary(@PathVariable String id,@RequestBody CalibreCleanupPreparation.Confirmation input) {return preparations.confirm(id,input);}
    @GetMapping("/{id}/library-items") public PageResponse<RevisionCleanup.Item> libraryItems(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="eplId,asc") String sort,@RequestParam long version) {return preparations.items(id,page,size,sort,version);}
    @GetMapping("/{id}/library-groups") public PageResponse<CalibreCleanupPreparation.Group> libraryGroups(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="5") int size,@RequestParam long version) {return preparations.groups(id,page,size,version);}
    @GetMapping("/{id}/library-copies") public PageResponse<CalibreLibraryCleanup.Copy> libraryCopies(@PathVariable String id,@RequestParam String group,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search,@RequestParam long version) {return preparations.copies(id,group,page,size,search,version);}
    @PostMapping("/calibre-removals/preview") public RevisionCleanup.Job calibreRemovals(@RequestBody RevisionCleanup.CalibreRemoval input) {return cleanup.previewCalibreRemoval(input);}
    @PostMapping("/removals") public RevisionCleanup.Job removals(@RequestBody RevisionCleanup.RemovalInput input) {return cleanup.removeSelected(input);}
    @PostMapping("/preview") public RevisionCleanup.Job preview(@RequestBody RevisionCleanup.PreviewInput input) {return cleanup.preview(input);}
    @GetMapping public PageResponse<RevisionCleanup.Job> jobs(@RequestParam String source,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return cleanup.jobs(source,page,size);}
    @GetMapping("/{id}") public RevisionCleanup.Job job(@PathVariable String id) {return cleanup.get(id);}
    @GetMapping("/{id}/items") public PageResponse<RevisionCleanup.Item> items(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam org.springframework.util.MultiValueMap<String,String> params) {return cleanup.items(id,page,size,params);}
    @PostMapping("/{id}/start") public RevisionCleanup.Job start(@PathVariable String id,@RequestBody RevisionCleanup.Confirmation input) {return cleanup.start(id,input);}
    @PostMapping("/{id}/retry") public RevisionCleanup.Job retry(@PathVariable String id,@RequestBody RevisionCleanup.RetryInput input) {return cleanup.retryUnconfirmed(id,input);}
    @PostMapping("/{id}/{command:cancel|continue|verify}") public RevisionCleanup.Job control(@PathVariable String id,@PathVariable String command) {return cleanup.control(id,command);}
}
