package com.rlibanez.eplsync.settings;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/calibre/transfers")
@PreAuthorize("hasAuthority('CALIBRE_SEND') or hasAuthority('CALIBRE_JOBS_MANAGE')")
public class CalibreTransferController {
    private final CalibreTransfers transfers;
    public CalibreTransferController(CalibreTransfers transfers) {this.transfers=transfers;}
    public record Selection(List<String> ids) {}
    @PostMapping("/prepare")
    @PreAuthorize("hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CalibreTransfers.Prepared prepare(@RequestBody Selection selection) {return transfers.prepare(selection.ids());}
    @PostMapping
    @PreAuthorize("hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CalibreTransfers.Job create(@RequestBody CalibreTransfers.Request request) {return transfers.create(request);}
    @GetMapping
    public CalibreTransfers.Page<CalibreTransfers.Job> jobs(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String username,@RequestParam(required=false) java.time.Instant from,@RequestParam(required=false) java.time.Instant before,@RequestParam org.springframework.util.MultiValueMap<String,String> params) {return transfers.jobs(page,size,state,username,from,before,params.getOrDefault("sort",List.of("createdAt,desc")));}
    @GetMapping("/{id}") public CalibreTransfers.Job job(@PathVariable String id) {return transfers.job(id);}
    @GetMapping("/{id}/items") public CalibreTransfers.Page<CalibreTransfers.Item> items(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String state,@RequestParam org.springframework.util.MultiValueMap<String,String> params) {return transfers.items(id,page,size,state,params.getOrDefault("sort",List.of("position,asc")));}
    @PostMapping("/{id}/{command}")
    @PreAuthorize("hasAuthority('CALIBRE_JOBS_MANAGE')")
    public CalibreTransfers.Job control(@PathVariable String id,@PathVariable String command) {return transfers.control(id,command);}
}
