package com.rlibanez.eplsync.tasks;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.settings.*;
import com.rlibanez.eplsync.torrent.updates.*;
import com.rlibanez.eplsync.exception.UserInputException;

/** Shared last complete manual or scheduled search per user. */
@Service
public class UpdateSearches {
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(UpdateSearches.class);
    private final JdbcTemplate jdbc;
    private final RevisionUpdates updates;
    private final UpdatePreferences preferences;
    private final UpdateCleanupService torrent;
    private final CalibreCatalogService calibre;
    private final CalibreUpdateFlow files;
    private final ServerSettings serverSettings;
    private final CalibreSettings calibreSettings;
    private final SavedUpdateResults saved;
    public UpdateSearches(JdbcTemplate jdbc,RevisionUpdates updates,UpdatePreferences preferences,UpdateCleanupService torrent,CalibreCatalogService calibre,CalibreUpdateFlow files,ServerSettings serverSettings,CalibreSettings calibreSettings) {
        this.jdbc=jdbc;this.updates=updates;this.preferences=preferences;this.torrent=torrent;this.calibre=calibre;this.files=files;this.serverSettings=serverSettings;this.calibreSettings=calibreSettings;this.saved=new SavedUpdateResults(jdbc);
    }
    public record Search(List<?> rows,long searchedAt,List<?> states,com.rlibanez.eplsync.events.EventContext.Origin origin,Map<String,Object> criteria) {}
    public record CalibreSearch(CalibreCatalogService.Snapshot snapshot,Search search) {}
    public Map<String,Object> latest(String type,String userId) { return saved.latest(type,userId); }
    public com.rlibanez.eplsync.dto.PageResponse<Map<String,Object>> page(String type,String userId,long generation,int page,int size,String sort,Map<String,String> filters) {
        return saved.page(type,userId,generation,page,size,sort,filters);
    }
    static Map<String,Object> summary(Search search) {
        return Map.of("searchedAt",search.searchedAt(),"states",search.states(),"origin",search.origin(),"criteria",search.criteria());
    }
    private boolean matches(String type,Map<?,?> criteria) {
        return Objects.equals(criteria.get("context"),UpdateSearchContext.current(jdbc,type))
            && (!Boolean.TRUE.equals(criteria.get("checkFiles")) || Objects.equals(criteria.get("downloadContext"),UpdateSearchContext.current(jdbc,"downloads")));
    }
    private void save(String type,String userId,Search search) {
        var downloadContext=search.criteria().get("downloadContext");
        int changed=jdbc.update("INSERT INTO update_search_results(user_id,search_type,searched_at,origin,criteria_json,results_json) SELECT ?,?,?,?,?,? WHERE ?=(SELECT setting_value FROM app_settings WHERE setting_key=?) AND (? IS NULL OR ?=(SELECT setting_value FROM app_settings WHERE setting_key=?)) ON CONFLICT(user_id,search_type) DO UPDATE SET searched_at=excluded.searched_at,origin=excluded.origin,criteria_json=excluded.criteria_json,results_json=excluded.results_json WHERE excluded.searched_at>=update_search_results.searched_at",userId,type,search.searchedAt(),search.origin().name(),saved.encode(search.criteria(),65536),saved.encode(search.rows(),SavedUpdateResults.MAX_BYTES),search.criteria().get("context"),UpdateSearchContext.key(type),downloadContext,downloadContext,UpdateSearchContext.key("downloads"));
        if(changed==0 && !matches(type,search.criteria()))
            throw new UserInputException("La conexión cambió durante la búsqueda; vuelve a buscar actualizaciones");
    }
    private void logCompleted(String type,String userId,Search search,long started) {
        var actor=com.rlibanez.eplsync.events.EventContext.actor();
        String origin=com.rlibanez.eplsync.events.EventContext.operationOriginLabel();
        Runnable summary=()->log.info("Búsqueda de actualizaciones finalizada: tipo={}, origen={}, userId={}, usuario={}, actualizaciones={}, duraciónMs={}",
            type,origin,userId,actor.username(),search.rows().size(),(System.nanoTime()-started)/1_000_000);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() { @Override public void afterCommit() {summary.run();} });
        else summary.run();
    }
    public Map<String,Object> downloads(String userId) {
        return Map.of("updates",searchDownloads(userId,preferences.get()).rows().size());
    }
    public Search searchDownloads(String userId,UpdatePreferences.Preferences input) {
        if(events==null) return performDownloads(userId,input);
        return events.runFailuresOnly(com.rlibanez.eplsync.events.EventJournal.Category.JOB,"TORRENT_UPDATE_SEARCH",Map.of(),()->performDownloads(userId,input),result->Map.of("updates",result.rows().size()));
    }
    private Search performDownloads(String userId,UpdatePreferences.Preferences input) {
        long started=System.nanoTime();
        RevisionUpdates.validate(input,0,1000,"eplId,asc");
        String context=serverSettings.updateSearchContext();
        torrent.synchronize(false,false);
        var rows=new ArrayList<RevisionUpdates.Row>();
        var budget=saved.budget();
        for(int page=0;;page++) {
            var result=updates.search(input,page,1000,"eplId,asc");result.items().forEach(budget::add);rows.addAll(result.items());
            if(rows.size()>100000) throw new UserInputException("La búsqueda supera el límite de 100000 resultados guardados");
            if(!result.meta().hasNext() || result.items().isEmpty()) break;
        }
        var search=new Search(List.copyOf(rows),Instant.now().toEpochMilli(),input.states(),com.rlibanez.eplsync.events.EventContext.origin(),Map.of("states",input.states(),"synchronize",true,"context",context));
        save("downloads",userId,search);
        logCompleted("CATALOGO",userId,search,started);
        return search;
    }
    public Map<String,Object> calibre(String userId) {
        var result=searchCalibre(userId);
        return Map.of("updates",result.search().rows().size(),"total",result.snapshot().total(),"identified",result.snapshot().identified());
    }
    public CalibreSearch searchCalibre(String userId) {
        if(events==null) return performCalibre(userId);
        return events.runFailuresOnly(com.rlibanez.eplsync.events.EventJournal.Category.JOB,"CALIBRE_UPDATE_SEARCH",Map.of(),()->performCalibre(userId),result->Map.of("updates",result.search().rows().size(),"total",result.snapshot().total(),"identified",result.snapshot().identified()));
    }
    private CalibreSearch performCalibre(String userId) {
        long started=System.nanoTime();
        String context=calibreSettings.updateSearchContext();
        boolean checkFiles=Permission.has(Permission.DOWNLOADS_FILES_READ);
        String downloadContext=checkFiles?serverSettings.updateSearchContext():null;
        var snapshot=calibre.sync();
        var budget=saved.budget();
        var rows=jdbc.query("SELECT b.calibre_id,b.title,b.authors,b.epl_id,b.revision,c.revision,c.cover_url,c.cover_available FROM calibre_books b JOIN catalog_books c ON c.epl_id=b.epl_id WHERE b.active=true"+CalibreCatalogService.updatesPredicate()+" ORDER BY b.calibre_id LIMIT 100001",(r,n)-> {
            var row=new LinkedHashMap<String,Object>();row.put("calibreId",r.getLong(1));row.put("title",r.getString(2));row.put("authors",r.getString(3));row.put("eplId",r.getLong(4));row.put("revision",r.getDouble(5));row.put("catalogRevision",r.getDouble(6));row.put("coverUrl",r.getString(7));row.put("coverAvailable",r.getObject(8)==null?null:r.getBoolean(8));row.put("comparison","OUTDATED");row.put("presence","PRESENT");row.put("downloadAvailability","UNKNOWN");budget.add(row);return row;
        });
        if(rows.size()>100000) throw new UserInputException("La búsqueda supera el límite de 100000 resultados guardados");
        if(checkFiles) {
            for(int offset=0;offset<rows.size();offset+=1000) {
                var batch=rows.subList(offset,Math.min(rows.size(),offset+1000));
                var availability=files.availability(batch.stream().map(r -> (Long)r.get("calibreId")).toList());
                var byId=new HashMap<Long,String>();availability.forEach(a -> byId.put(a.calibreId(),a.state()));
                batch.forEach(r -> r.put("downloadAvailability",byId.getOrDefault((Long)r.get("calibreId"),"UNKNOWN")));
            }
        }
        var criteria=new LinkedHashMap<String,Object>();
        criteria.put("updatesOnly",true);criteria.put("library",snapshot.library());criteria.put("checkFiles",checkFiles);criteria.put("context",context);
        if(checkFiles) criteria.put("downloadContext",downloadContext);
        var search=new Search(List.copyOf(rows),Instant.now().toEpochMilli(),List.of(),com.rlibanez.eplsync.events.EventContext.origin(),Map.copyOf(criteria));
        save("calibre",userId,search);
        logCompleted("CALIBRE",userId,search,started);
        return new CalibreSearch(snapshot,search);
    }
}
