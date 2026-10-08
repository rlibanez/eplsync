package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.reports.ReportSnapshots;
import com.rlibanez.eplsync.dto.PageResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/torrent/downloads/reports")
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('TORRENT_SYNC')")
public class SyncReportController {
    private final ReportSnapshots reports;
    public SyncReportController(ReportSnapshots reports) {this.reports=reports;}
    @GetMapping("/{id}/{section}")
    public PageResponse<?> page(@PathVariable String id,@PathVariable String section,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="") String sort,@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="ALL") String action,@RequestParam(defaultValue="ALL") String outcome) {
        if (!Set.of("books","ignored").contains(section) || search.length()>256
                || !Set.of("ALL","CHANGED","CREATE","UPDATE","UNCHANGED").contains(action)
                || !Set.of("ALL","newlyCompleted","notFound","newlyNotFound").contains(outcome))
            throw new com.rlibanez.eplsync.exception.UserInputException("Filtros de informe inválidos");
        var filters=new ArrayList<ReportSnapshots.Filter>();
        if(!search.isBlank()) filters.add(new ReportSnapshots.Filter("search","LIKE","%"+search.trim()+"%"));
        if(section.equals("books")) {
            if(!action.equals("ALL")) filters.add(new ReportSnapshots.Filter("action",action.equals("CHANGED") ? "<>" : "=",action.equals("CHANGED")?"UNCHANGED":action));
            if(!outcome.equals("ALL")) filters.add(new ReportSnapshots.Filter(outcome.equals("notFound")?"resultingStatus":outcome,"=",outcome.equals("notFound")?"NOT_FOUND":1));
            return reports.page(id,section,page,size,sort.isBlank()?List.of():List.of(sort.split(";")),Set.of("eplId","title","hash","action","previousStatus","resultingStatus","changedFields","newlyCompleted","newlyNotFound","search"),filters,DownloadTrackingService.SyncItem.class);
        }
        return reports.page(id,section,page,size,sort.isBlank()?List.of():List.of(sort.split(";")),Set.of("name","hash","reason","search"),filters,DownloadTrackingService.IgnoredTorrent.class);
    }
}
