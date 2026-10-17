package com.rlibanez.eplsync.settings;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/settings/work/{source}/deletions")
@PreAuthorize("(#source == 'downloads' and (hasAuthority('TORRENT_JOBS_MANAGE') or (hasAuthority('TORRENT_SYNC') and hasAuthority('DOWNLOADS_DELETE')))) or (#source == 'calibre' and (hasAuthority('CALIBRE_JOBS_MANAGE') or (hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_DELETE'))))")
public class WorkDeletionController {
    private final WorkDeletion service;
    public WorkDeletionController(WorkDeletion service) {this.service=service;}
    @PostMapping("/preview") public WorkDeletion.Result preview(@PathVariable String source,@RequestBody WorkDeletion.Selection selection) {return service.apply(source,selection,true);}
    @PostMapping public WorkDeletion.Result remove(@PathVariable String source,@RequestBody WorkDeletion.Selection selection) {return service.apply(source,selection,false);}
}
