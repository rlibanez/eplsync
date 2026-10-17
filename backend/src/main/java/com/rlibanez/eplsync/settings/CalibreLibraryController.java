package com.rlibanez.eplsync.settings;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.rlibanez.eplsync.exception.UserInputException;

@RestController
@RequestMapping("/api/calibre/books")
@PreAuthorize("hasAuthority('CALIBRE_READ')")
public class CalibreLibraryController {
    private final JdbcTemplate jdbc;
    private final CalibreSettings settings;
    public CalibreLibraryController(JdbcTemplate jdbc,CalibreSettings settings) { this.jdbc=jdbc; this.settings=settings; }
    @GetMapping("/{calibreId}/open")
    @Transactional(readOnly=true)
    public org.springframework.http.ResponseEntity<Void> open(@PathVariable long calibreId) {
        if(calibreId<=0 || jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true AND calibre_id=?",Long.class,calibreId)==0)
            return org.springframework.http.ResponseEntity.notFound().build();
        var view=settings.view();
        String base=view.browserUrl().isBlank() ? view.url() : view.browserUrl();
        base=CalibreSettings.normalize(base);
        String library=java.net.URLEncoder.encode(view.library(),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        var target=java.net.URI.create(base+"/#book_id="+calibreId+"&library_id="+library+"&panel=book_details");
        return org.springframework.http.ResponseEntity.status(302).cacheControl(org.springframework.http.CacheControl.noStore()).location(target).build();
    }
    public record Book(long calibreId, String title, String authors, Long eplId, Double revision,
                       Double catalogRevision, String comparison, String presence, String coverUrl, Boolean coverAvailable) {}
    public record Meta(int page,int size,long totalItems,long totalPages,boolean hasPrevious,boolean hasNext) {}
    public record Page(List<Book> items, Meta meta) {}
    @GetMapping
    @Transactional(readOnly=true)
    public Page books(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
                      @RequestParam(defaultValue="eq") String titleOperator,@RequestParam(defaultValue="eq") String authorOperator,@RequestParam(defaultValue="") String title,@RequestParam(defaultValue="") String author,@RequestParam(required=false) Long eplId,@RequestParam(defaultValue="") String comparison,
                      @RequestParam(defaultValue="") String presence,@RequestParam(defaultValue="false") boolean updatesOnly, jakarta.servlet.http.HttpServletRequest request) {
        boolean excludeTitle=com.rlibanez.eplsync.ordering.StateOperator.excluded(titleOperator),excludeAuthor=com.rlibanez.eplsync.ordering.StateOperator.excluded(authorOperator);
        var sort=request.getParameterValues("sort")==null ? List.of("title,asc") : Arrays.asList(request.getParameterValues("sort"));
        if(page<0 || size<1 || size>1000 || title.length()>256 || author.length()>256 || (eplId!=null && eplId<=0) || sort.size()>8)
            throw new UserInputException("Paginación o búsqueda de Calibre inválida");
        String latest=updatesOnly ? CalibreCatalogService.updatesPredicate() : "";
        String base=" FROM (SELECT b.*, c.revision AS catalog_revision, c.cover_url, c.cover_available, CASE WHEN b.epl_id IS NULL THEN 'UNIDENTIFIED' WHEN c.epl_id IS NULL THEN 'MISSING' ELSE 'PRESENT' END AS presence, CASE WHEN c.epl_id IS NULL OR b.revision IS NULL THEN 'UNKNOWN' WHEN b.revision<c.revision THEN 'OUTDATED' WHEN b.revision>c.revision THEN 'NEWER' ELSE 'CURRENT' END AS comparison FROM calibre_books b LEFT JOIN catalog_books c ON c.epl_id=b.epl_id WHERE b.active=true"+latest+") WHERE 1=1";
        var args=new ArrayList<Object>();
        if(!title.isBlank()) { base+=" AND instr(lower(coalesce(title,'')),lower(?))"+(excludeTitle?"=0":">0"); args.add(title.trim()); }
        if(!author.isBlank()) { base+=" AND instr(lower(coalesce(authors,'')),lower(?))"+(excludeAuthor?"=0":">0"); args.add(author.trim()); }
        if(eplId!=null) { base+=" AND epl_id=?"; args.add(eplId); }
        for (var filter : Map.of("comparison", comparison, "presence", presence).entrySet()) {
            if (filter.getValue().isEmpty()) continue;
            var allowed = filter.getKey().equals("comparison") ? Set.of("UNKNOWN","OUTDATED","NEWER","CURRENT") : Set.of("UNIDENTIFIED","MISSING","PRESENT");
            var selected = Arrays.asList(filter.getValue().split(",", -1));
            if (selected.size() > allowed.size() || !allowed.containsAll(selected))
                throw new UserInputException("Filtro de Calibre inválido: " + filter.getKey());
            base += " AND " + filter.getKey() + " IN (" + String.join(",", Collections.nCopies(selected.size(), "?")) + ")";
            args.addAll(selected);
        }
        var fields=Map.of("title","title COLLATE EPL_TEXT","authors","authors COLLATE EPL_TEXT","eplId","epl_id","revision","revision","catalogRevision","catalog_revision","comparison","comparison","presence","presence","calibreId","calibre_id");
        var order=new ArrayList<String>();
        for(String value:sort) {
            var parts=value.split(",");
            if(parts.length!=2 || !fields.containsKey(parts[0]) || !Set.of("asc","desc").contains(parts[1])) throw new UserInputException("Ordenación inválida");
            order.add(fields.get(parts[0])+" "+parts[1]);
        }
        order.add("calibre_id ASC");
        long total=jdbc.queryForObject("SELECT count(*)"+base,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var items=jdbc.query("SELECT *"+base+" ORDER BY "+String.join(",",order)+" LIMIT ? OFFSET ?",(r,n)->new Book(r.getLong("calibre_id"),r.getString("title"),r.getString("authors"),r.getObject("epl_id")==null?null:r.getLong("epl_id"),r.getObject("revision")==null?null:r.getDouble("revision"),r.getObject("catalog_revision")==null?null:r.getDouble("catalog_revision"),r.getString("comparison"),r.getString("presence"),r.getString("cover_url"),r.getObject("cover_available")==null?null:r.getBoolean("cover_available")),args.toArray());
        long pages=(total+size-1)/size;
        return new Page(items,new Meta(page,size,total,pages,page>0,page+1<pages));
    }
}
