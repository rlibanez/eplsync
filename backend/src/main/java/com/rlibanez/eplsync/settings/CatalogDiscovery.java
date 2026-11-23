package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.discovery.DiscoveryTerms;

import java.util.*;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import com.rlibanez.eplsync.dto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.jpa.domain.Specification;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Local, permission-scoped vocabulary. Filtering happens in SQLite before paging or sending. */
@Service
public class CatalogDiscovery {
    private final JdbcTemplate jdbc;
    private final DownloadTrackingService tracking;
    private final CalibreSettings settings;
    private final JsonMapper json=JsonMapper.builder().build();
    public CatalogDiscovery(JdbcTemplate jdbc,DownloadTrackingService tracking,CalibreSettings settings) {this.jdbc=jdbc;this.tracking=tracking;this.settings=settings;}
    public record Value(String value,String label,Map<String,Long> counts) {}
    public record Match(String value,String source,String criterion) {}
    private void authorize(CatalogBookFilter f) {
        Permission.require(Permission.CATALOG_READ);f.normalize();
        if(f.getDiscoverySource()==null) throw new com.rlibanez.eplsync.exception.UserInputException("Indica el origen de la búsqueda");
        if(!f.getDiscoverySource().equals("CALIBRE")) Permission.require(Permission.TORRENT_SYNC);
        if(!f.getDiscoverySource().equals("DOWNLOADS")) Permission.require(Permission.CALIBRE_READ);
    }
    private LinkedHashMap<String,Value> vocabulary(CatalogBookFilter f,String criterion) {
        authorize(f);
        var values=new LinkedHashMap<String,Value>();String field=DiscoveryTerms.column(criterion);
        for(String source:List.of("DOWNLOADS","CALIBRE")) {
            if(!f.getDiscoverySource().equals("BOTH") && !f.getDiscoverySource().equals(source)) continue;
            String where=source.equals("DOWNLOADS")
                ?"EXISTS(SELECT 1 FROM torrent_downloads d WHERE d.epl_id=b.epl_id AND d.client_instance_id=? AND d.status='DOWNLOADED')"
                :"EXISTS(SELECT 1 FROM calibre_books c WHERE c.epl_id=b.epl_id AND c.active=1)";
            var rows=jdbc.queryForList("SELECT b."+field+" value,count(DISTINCT b.epl_id) books FROM catalog_books b WHERE "+where+" GROUP BY b."+field+" ORDER BY b."+field+" COLLATE EPL_TEXT",source.equals("DOWNLOADS")?new Object[]{tracking.instanceId()}:new Object[]{});
            for(var row:rows) {
                var labels=DiscoveryTerms.labels(Objects.toString(row.get("value"),null),criterion);
                if(criterion.equals("AUTHOR") && !DiscoveryTerms.authorsAllowed(Objects.toString(row.get("value"),null),Boolean.TRUE.equals(f.getDiscoveryIgnoreAnthologies()),f.getDiscoveryMaxAuthors())) continue;
                for(String label:labels) {
                String key=DiscoveryTerms.key(label);var previous=values.get(key);var counts=new LinkedHashMap<String,Long>(previous==null?Map.of():previous.counts());counts.merge(source,((Number)row.get("books")).longValue(),Long::sum);
                values.put(key,new Value(key,previous==null?label:previous.label(),Map.copyOf(counts)));
            }
            }
        }
        return values;
    }
    public PageResponse<Value> options(String source,String criterion,String q,int page,int size) {
        return options(source,criterion,q,page,size,false,null);
    }
    public PageResponse<Value> options(String source,String criterion,String q,int page,int size,boolean ignoreAnthologies,Integer maxAuthors) {
        var result=optionsWithSelection(source,criterion,q,page,size,ignoreAnthologies,maxAuthors,true,null,null);
        return new PageResponse<>(result.items(),result.meta());
    }
    public record ValueOptions(List<Value> items,PageResponse.PageMeta meta,long selectedTotal) {}
    public ValueOptions optionsWithSelection(String source,String criterion,String q,int page,int size,boolean ignoreAnthologies,Integer maxAuthors,boolean allSelected,List<String> selected,List<String> excluded) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if(q==null || q.length()>256) throw new com.rlibanez.eplsync.exception.UserInputException("Búsqueda de valores inválida");
        for(var input:List.of(selected==null?List.<String>of():selected,excluded==null?List.<String>of():excluded))
            if(input.size()>10000 || input.stream().anyMatch(value->value==null || value.isBlank() || value.length()>512)) throw new com.rlibanez.eplsync.exception.UserInputException("Selección de valores inválida");
        var filter=new CatalogBookFilter();filter.setDiscoverySource(source);filter.setDiscoveryCriterion(criterion);filter.setDiscoveryIgnoreAnthologies(ignoreAnthologies);filter.setDiscoveryMaxAuthors(maxAuthors);
        var vocabulary=vocabulary(filter,criterion);
        var included=new HashSet<>(selected==null?Set.<String>of():selected.stream().map(DiscoveryTerms::key).toList());
        var exceptions=new HashSet<>(excluded==null?Set.<String>of():excluded.stream().map(DiscoveryTerms::key).toList());
        long selectedTotal=vocabulary.keySet().stream().filter(value->allSelected?!exceptions.contains(value):included.contains(value)).count();
        String needle=DiscoveryTerms.key(q);
        var values=vocabulary.values().stream().filter(v->DiscoveryTerms.key(v.label()).contains(needle)).sorted((x,y)->com.rlibanez.eplsync.ordering.TextOrdering.compare(x.label(),y.label())).toList();
        int pages=(values.size()+size-1)/size;long start=(long)page*size;
        return new ValueOptions(values.subList((int)Math.min(start,values.size()),(int)Math.min(start+size,values.size())),new PageResponse.PageMeta(page,size,values.size(),pages,page==0,page>=pages-1,page+1<pages,page>0),selectedTotal);
    }
    public record AuthorSummary(long totalBooks,long ignoredBooks,long ignoredAnthologies,long ignoredTooMany,long ignoredByBoth) {}
    public AuthorSummary authorSummary(String source,boolean ignoreAnthologies,Integer maxAuthors) {
        var filter=new CatalogBookFilter();filter.setDiscoverySource(source);filter.setDiscoveryIgnoreAnthologies(ignoreAnthologies);filter.setDiscoveryMaxAuthors(maxAuthors);authorize(filter);
        String downloads="EXISTS(SELECT 1 FROM torrent_downloads d WHERE d.epl_id=b.epl_id AND d.client_instance_id=? AND d.status='DOWNLOADED')";
        String calibre="EXISTS(SELECT 1 FROM calibre_books c WHERE c.epl_id=b.epl_id AND c.active=1)";
        String where=source.equals("DOWNLOADS")?downloads:source.equals("CALIBRE")?calibre:"("+downloads+" OR "+calibre+")";
        var rows=jdbc.queryForList("SELECT b.author value,count(*) books FROM catalog_books b WHERE "+where+" GROUP BY b.author",source.equals("CALIBRE")?new Object[]{}:new Object[]{tracking.instanceId()});
        long total=0,ignored=0,anthologies=0,tooMany=0,both=0;
        for(var row:rows) {
            String author=Objects.toString(row.get("value"),null);long books=((Number)row.get("books")).longValue();total+=books;
            boolean aa=!DiscoveryTerms.authorsAllowed(author,ignoreAnthologies,null);
            boolean limit=!DiscoveryTerms.authorsAllowed(author,false,maxAuthors);
            if(aa) anthologies+=books;if(limit) tooMany+=books;if(aa&&limit) both+=books;if(aa||limit) ignored+=books;
        }
        return new AuthorSummary(total,ignored,anthologies,tooMany,both);
    }
    private LinkedHashMap<String,Value> selectedVocabulary(CatalogBookFilter f,String criterion) {
        var values=vocabulary(f,criterion);
        if(f.discoverySelection(criterion)!=null) {var selected=new HashSet<>(f.discoverySelection(criterion).stream().map(DiscoveryTerms::key).toList());values.keySet().retainAll(selected);}
        values.keySet().removeAll(f.discoveryExclusions(criterion).stream().map(DiscoveryTerms::key).toList());
        return values;
    }
    public Specification<CatalogBook> fromFilter(CatalogBookFilter f) {
        authorize(f);
        var terms=new LinkedHashMap<String,String>();
        for(String criterion:f.getDiscoveryCriteria()) terms.put(criterion,json.writeValueAsString(selectedVocabulary(f,criterion).keySet()));
        String instance=tracking.instanceId();
        boolean pending=Boolean.TRUE.equals(f.getDiscoveryExcludePending());
        String destination=null,library=null;
        if(pending && Permission.has(Permission.CALIBRE_READ) && (Permission.has(Permission.CALIBRE_SEND)||Permission.has(Permission.CALIBRE_JOBS_MANAGE))) {try {var connection=settings.connection();destination=CalibreTransfers.destination(connection);library=connection.library();} catch(com.rlibanez.eplsync.exception.UserInputException ex) { /* No configured Calibre destination to exclude. */ }}
        final String target=destination,targetLibrary=library;
        return (root,query,cb)-> {
            var h=(HibernateCriteriaBuilder)cb;var predicates=new ArrayList<jakarta.persistence.criteria.Predicate>();
            var matches=new ArrayList<jakarta.persistence.criteria.Predicate>();
            for(var entry:terms.entrySet()) matches.add(cb.isTrue(h.sql("EXISTS(SELECT 1 FROM json_each(epl_discovery_tokens(?,?)) t WHERE t.value IN (SELECT value FROM json_each(?)))",Boolean.class,root.get(DiscoveryTerms.field(entry.getKey())),h.value(entry.getKey()),h.value(entry.getValue()))));
            predicates.add("ANY".equals(f.getDiscoveryMatch())?cb.or(matches.toArray(jakarta.persistence.criteria.Predicate[]::new)):cb.and(matches.toArray(jakarta.persistence.criteria.Predicate[]::new)));
            if(pending && Permission.has(Permission.TORRENT_JOBS_MANAGE)) predicates.add(cb.isTrue(h.sql("NOT EXISTS(SELECT 1 FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND j.target_fingerprint=? AND j.state NOT IN ('COMPLETED','CANCELLED') AND i.state IN ('PENDING','IN_FLIGHT'))",Boolean.class,root.get("eplId"),h.value(instance))));
            if(target!=null) predicates.add(cb.isTrue(h.sql("NOT EXISTS(SELECT 1 FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE i.epl_id=? AND j.destination=? AND j.library=? AND j.state NOT IN ('COMPLETED','CANCELLED') AND i.state IN ('PENDING','SENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','UNCERTAIN','CLEANUP_PENDING'))",Boolean.class,root.get("eplId"),h.value(target),h.value(targetLibrary))));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
    public List<CatalogBookResponse> explain(CatalogBookFilter filter,List<CatalogBookResponse> books) {
        if(filter.getDiscoverySource()==null || books.isEmpty()) return books;
        authorize(filter);
        var values=new LinkedHashMap<String,LinkedHashMap<String,Value>>();
        for(String criterion:filter.getDiscoveryCriteria()) values.put(criterion,selectedVocabulary(filter,criterion));
        return books.stream().map(book->{
            var matches=new ArrayList<Match>();
            for(var entry:values.entrySet()) {
                String criterion=entry.getKey();
                String text=switch(criterion) {case "AUTHOR"->book.author();case "COLLECTION"->book.collection();case "GENRE"->book.genres();case "LANGUAGE"->book.language()==null?null:book.language().getIsoCode();case "YEAR"->Objects.toString(book.publicationYear(),null);default->null;};
                for(String key:DiscoveryTerms.keys(text,criterion)) {var value=entry.getValue().get(key);if(value!=null) for(String source:value.counts().keySet().stream().sorted().toList()) matches.add(new Match(value.label(),source,criterion));}
            }
            return book.withDiscoveryMatches(matches);
        }).toList();
    }
}
