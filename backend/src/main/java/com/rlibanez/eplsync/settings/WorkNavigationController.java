package com.rlibanez.eplsync.settings;

import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.rlibanez.eplsync.config.TableOrdering;
import com.rlibanez.eplsync.exception.UserInputException;

@RestController
@RequestMapping("/api/settings/work")
@PreAuthorize("isAuthenticated()")
public class WorkNavigationController {
    private final WorkListings listings;
    public WorkNavigationController(WorkListings listings) {this.listings=listings;}
    @GetMapping("/{source}/{id}/neighbors")
    @PreAuthorize("(#source == 'downloads' and (hasAuthority('TORRENT_JOBS_MANAGE') or (hasAuthority('TORRENT_SYNC') and hasAuthority('DOWNLOADS_DELETE')))) or (#source == 'calibre' and (hasAuthority('CALIBRE_SEND') or hasAuthority('CALIBRE_JOBS_MANAGE') or (hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_DELETE'))))")
    public WorkListings.Neighbors neighbors(@PathVariable String source,@PathVariable String id,
            @RequestParam(defaultValue="eq") String typeOperator,@RequestParam(defaultValue="eq") String statusOperator,@RequestParam(required=false) String status,@RequestParam(required=false) String state,
            @RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String username,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant before,
            jakarta.servlet.http.HttpServletRequest request) {
        if(!Set.of("typeOperator","statusOperator","sort","status","state","type","username","from","before").containsAll(request.getParameterMap().keySet())) throw new UserInputException("Parámetro de navegación desconocido");
        TableOrdering.validateParameters(request);
        String states=source.equals("downloads")?status:state;
        return listings.neighbors(source,id,states==null || states.isEmpty()?List.of():Arrays.asList(states.split(",",-1)),type,username,from,before,TableOrdering.request(request,"createdAt,desc"),statusOperator,typeOperator);
    }
}
