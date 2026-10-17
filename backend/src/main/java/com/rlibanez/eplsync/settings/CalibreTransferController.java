package com.rlibanez.eplsync.settings;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/calibre/transfers")
@PreAuthorize("hasAuthority('CALIBRE_SEND') or hasAuthority('CALIBRE_JOBS_MANAGE')")
public class CalibreTransferController {
    private final CalibreTransfers transfers;
    @org.springframework.beans.factory.annotation.Autowired private WorkListings listings;
    public CalibreTransferController(CalibreTransfers transfers) {this.transfers=transfers;}
    @org.springframework.beans.factory.annotation.Autowired private CatalogCalibreTransfers catalogTransfers;
    @PostMapping("/catalog/prepare")
    @PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CatalogCalibreTransfers.Prepared prepareCatalog(@RequestBody CatalogCalibreTransfers.Selection selection) {return catalogTransfers.prepare(selection);}
    @PostMapping("/catalog")
    @PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CalibreTransfers.Job createCatalog(@RequestBody CatalogCalibreTransfers.Request request) {return catalogTransfers.create(request);}
    public record Selection(List<String> ids) {}
    @PostMapping("/prepare")
    @PreAuthorize("hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CalibreTransfers.Prepared prepare(@RequestBody Selection selection) {return transfers.prepare(selection.ids());}
    @PostMapping
    @PreAuthorize("hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CalibreTransfers.Job create(@RequestBody CalibreTransfers.Request request) {return transfers.create(request);}
    @GetMapping
    @PreAuthorize("hasAuthority('CALIBRE_SEND') or hasAuthority('CALIBRE_JOBS_MANAGE') or (hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_DELETE'))")
    public com.rlibanez.eplsync.dto.PageResponse<?> jobs(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="eq") String statusOperator,@RequestParam(defaultValue="eq") String typeOperator,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String username,@RequestParam(required=false) java.time.Instant from,@RequestParam(required=false) java.time.Instant before,@RequestParam org.springframework.util.MultiValueMap<String,String> params) {return listings.list("calibre",page,size,state.isEmpty()?List.of():List.of(state),type,username,from,before,String.join(";",params.getOrDefault("sort",List.of("createdAt,desc"))),statusOperator,typeOperator);}

    @GetMapping("/{id}") public CalibreTransfers.Job job(@PathVariable String id) {return transfers.job(id);}
    @GetMapping("/{id}/items") public CalibreTransfers.Page<CalibreTransfers.Item> items(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String detail,@RequestParam(defaultValue="eq") String statusOperator,@RequestParam org.springframework.util.MultiValueMap<String,String> params) {return transfers.items(id,page,size,state,detail,params.getOrDefault("sort",List.of("position,asc")),statusOperator);}
    @PostMapping("/{id}/resolution")
    @PreAuthorize("hasAuthority('CALIBRE_JOBS_MANAGE') and hasAuthority('CALIBRE_SEND') and hasAuthority('DOWNLOADS_FILES_READ')")
    public CalibreTransfers.Job resolve(@PathVariable String id,@RequestBody CalibreTransfers.Resolution input) {return transfers.resolve(id,input);}
    @PostMapping("/{id}/{command}")
    @PreAuthorize("hasAuthority('CALIBRE_JOBS_MANAGE')")
    public CalibreTransfers.Job control(@PathVariable String id,@PathVariable String command) {return transfers.control(id,command);}
}
