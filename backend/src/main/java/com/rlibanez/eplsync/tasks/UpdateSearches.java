package com.rlibanez.eplsync.tasks;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.settings.*;
import com.rlibanez.eplsync.torrent.updates.*;
import com.rlibanez.eplsync.exception.UserInputException;
import tools.jackson.databind.json.JsonMapper;

/** Shared last complete manual or scheduled search per user. */
@Service
public class UpdateSearches {
    private final JdbcTemplate jdbc;
    private final RevisionUpdates updates;
    private final UpdatePreferences preferences;
    private final UpdateCleanupService torrent;
    private final CalibreCatalogService calibre;
    private final CalibreUpdateFlow files;
    private final JsonMapper json=JsonMapper.builder().build();
    public UpdateSearches(JdbcTemplate jdbc,RevisionUpdates updates,UpdatePreferences preferences,UpdateCleanupService torrent,CalibreCatalogService calibre,CalibreUpdateFlow files) {
        this.jdbc=jdbc;this.updates=updates;this.preferences=preferences;this.torrent=torrent;this.calibre=calibre;this.files=files;
    }
    public record Search(List<?> rows,long searchedAt,List<?> states,com.rlibanez.eplsync.events.EventContext.Origin origin,Map<String,Object> criteria) {}
    public record CalibreSearch(CalibreCatalogService.Snapshot snapshot,Search search) {}
    @SuppressWarnings("unchecked")
    public Map<String,Object> latest(String type,String userId) {
        if(!Set.of("downloads","calibre").contains(type)) throw new UserInputException("Tipo de búsqueda desconocido");
        var rows=jdbc.query("SELECT searched_at,origin,criteria_json,results_json FROM update_search_results WHERE user_id=? AND search_type=?",(r,n)-> {
            var criteria=json.readValue(r.getString("criteria_json"),Map.class);
            var result=new LinkedHashMap<String,Object>();
            result.put("rows",json.readValue(r.getString("results_json"),List.class));
            result.put("searchedAt",r.getLong("searched_at"));result.put("origin",r.getString("origin"));
            result.put("criteria",criteria);result.put("states",criteria.getOrDefault("states",List.of()));
            return result;
        },userId,type);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void save(String type,String userId,Search search) {
        jdbc.update("INSERT INTO update_search_results(user_id,search_type,searched_at,origin,criteria_json,results_json) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,search_type) DO UPDATE SET searched_at=excluded.searched_at,origin=excluded.origin,criteria_json=excluded.criteria_json,results_json=excluded.results_json WHERE excluded.searched_at>=update_search_results.searched_at",userId,type,search.searchedAt(),search.origin().name(),json.writeValueAsString(search.criteria()),json.writeValueAsString(search.rows()));
    }
    public Map<String,Object> downloads(String userId) {
        return Map.of("updates",searchDownloads(userId,preferences.get()).rows().size());
    }
    public Search searchDownloads(String userId,UpdatePreferences.Preferences input) {
        RevisionUpdates.validate(input,0,1000,"eplId,asc");
        torrent.synchronize(false,false);
        var rows=new ArrayList<RevisionUpdates.Row>();
        for(int page=0;;page++) {
            var result=updates.search(input,page,1000,"eplId,asc");rows.addAll(result.items());
            if(rows.size()>100000) throw new UserInputException("La búsqueda supera el límite de 100000 resultados guardados");
            if(!result.meta().hasNext() || result.items().isEmpty()) break;
        }
        var search=new Search(List.copyOf(rows),Instant.now().toEpochMilli(),input.states(),com.rlibanez.eplsync.events.EventContext.origin(),Map.of("states",input.states(),"synchronize",true));
        save("downloads",userId,search);
        return search;
    }
    public Map<String,Object> calibre(String userId) {
        var result=searchCalibre(userId);
        return Map.of("updates",result.search().rows().size(),"total",result.snapshot().total(),"identified",result.snapshot().identified());
    }
    public CalibreSearch searchCalibre(String userId) {
        var snapshot=calibre.sync();
        var rows=jdbc.query("SELECT b.calibre_id,b.title,b.authors,b.epl_id,b.revision,c.revision,c.cover_url,c.cover_available FROM calibre_books b JOIN catalog_books c ON c.epl_id=b.epl_id WHERE b.active=true"+CalibreCatalogService.updatesPredicate()+" ORDER BY b.calibre_id LIMIT 100001",(r,n)-> {
            var row=new LinkedHashMap<String,Object>();row.put("calibreId",r.getLong(1));row.put("title",r.getString(2));row.put("authors",r.getString(3));row.put("eplId",r.getLong(4));row.put("revision",r.getDouble(5));row.put("catalogRevision",r.getDouble(6));row.put("coverUrl",r.getString(7));row.put("coverAvailable",r.getObject(8)==null?null:r.getBoolean(8));row.put("comparison","OUTDATED");row.put("presence","PRESENT");row.put("downloadAvailability","UNKNOWN");return row;
        });
        if(rows.size()>100000) throw new UserInputException("La búsqueda supera el límite de 100000 resultados guardados");
        if(Permission.has(Permission.DOWNLOADS_FILES_READ)) {
            for(int offset=0;offset<rows.size();offset+=1000) {
                var batch=rows.subList(offset,Math.min(rows.size(),offset+1000));
                var availability=files.availability(batch.stream().map(r -> (Long)r.get("calibreId")).toList());
                var byId=new HashMap<Long,String>();availability.forEach(a -> byId.put(a.calibreId(),a.state()));
                batch.forEach(r -> r.put("downloadAvailability",byId.getOrDefault((Long)r.get("calibreId"),"UNKNOWN")));
            }
        }
        var search=new Search(List.copyOf(rows),Instant.now().toEpochMilli(),List.of(),com.rlibanez.eplsync.events.EventContext.origin(),Map.of("updatesOnly",true,"library",snapshot.library(),"checkFiles",Permission.has(Permission.DOWNLOADS_FILES_READ)));
        save("calibre",userId,search);
        return new CalibreSearch(snapshot,search);
    }
}
