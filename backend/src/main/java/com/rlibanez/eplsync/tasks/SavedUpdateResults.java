package com.rlibanez.eplsync.tasks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.*;
import com.rlibanez.eplsync.config.*;
import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.exception.UserInputException;
import tools.jackson.databind.json.JsonMapper;

/** Bounded JSON snapshots; SQLite filters and pages them before Java deserializes any rows. */
final class SavedUpdateResults {
    static final int MAX_BYTES=16*1024*1024;
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    SavedUpdateResults(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static void type(String type) {
        if(!Set.of("downloads","calibre").contains(type)) throw new UserInputException("Tipo de búsqueda desconocido");
    }
    private static String source() {
        return " FROM update_search_results s WHERE user_id=? AND search_type=? AND length(CAST(results_json AS BLOB))<=?"
            +" AND json_extract(criteria_json,'$.context')=(SELECT setting_value FROM app_settings WHERE setting_key=?)"
            +" AND (coalesce(json_extract(criteria_json,'$.checkFiles'),0)=0 OR json_extract(criteria_json,'$.downloadContext')=(SELECT setting_value FROM app_settings WHERE setting_key='updates.search-context.downloads'))";
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> latest(String type,String user) {
        type(type);
        var results=jdbc.query("SELECT searched_at,origin,criteria_json"+source(),(r,n)-> {
            var criteria=json.readValue(r.getString(3),Map.class);
            var result=new LinkedHashMap<String,Object>();
            result.put("searchedAt",r.getLong(1));result.put("origin",r.getString(2));result.put("criteria",criteria);
            result.put("states",criteria.getOrDefault("states",List.of()));return result;
        },user,type,MAX_BYTES,UpdateSearchContext.key(type));
        return results.isEmpty()?null:results.getFirst();
    }
    String encode(Object value,int maximum) {
        var bytes=new LimitedOutput(maximum);
        try { json.writeValue(bytes,value); }
        catch(RuntimeException ex) {
            if(bytes.exceeded) throw new UserInputException("La búsqueda supera el límite de 16 MiB de resultados guardados; se conserva la búsqueda anterior");
            throw ex;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    Budget budget() { return new Budget(); }
    final class Budget {
        private int bytes=2,rows;
        void add(Object row) {
            int comma=rows++==0?0:1;
            int length=encode(row,MAX_BYTES-bytes-comma).getBytes(StandardCharsets.UTF_8).length;
            bytes+=length+comma;
            if(bytes>MAX_BYTES) throw new UserInputException("La búsqueda supera el límite de 16 MiB de resultados guardados; se conserva la búsqueda anterior");
        }
    }
    private static final class LimitedOutput extends ByteArrayOutputStream {
        final int maximum;boolean exceeded;
        LimitedOutput(int maximum) { this.maximum=maximum; }
        private void check(int length) {
            if(length>maximum-count) { exceeded=true;throw new IllegalStateException("Saved search JSON limit exceeded"); }
        }
        @Override public synchronized void write(int b) {check(1);super.write(b);}
        @Override public synchronized void write(byte[] b,int offset,int length) {check(length);super.write(b,offset,length);}
    }
    @SuppressWarnings("unchecked")
    PageResponse<Map<String,Object>> page(String type,String user,long generation,int page,int size,String sort,Map<String,String> filters) {
        type(type);QueryLimits.page(page,size);
        if(generation<0) throw new UserInputException("Fecha de búsqueda inválida");
        Set<String> fields=type.equals("downloads")?Set.of("eplId","title","status","registeredRevision","availableRevision")
            :Set.of("calibreId","eplId","title","authors","revision","catalogRevision","comparison","presence","downloadAvailability");
        var orders=TableOrdering.parse(sort,fields);
        var order=new ArrayList<String>();
        for(var entry:orders) {
            String field=entry.getProperty();
            order.add("json_extract(value,'$."+field+"')"+(Set.of("title","authors","status","comparison","presence","downloadAvailability").contains(field)?" COLLATE EPL_TEXT":"")+" "+entry.getDirection().name());
        }
        order.add("CAST(key AS INTEGER) ASC");
        var arguments=new ArrayList<Object>(List.of(user,type,MAX_BYTES,UpdateSearchContext.key(type),generation));
        var where=new StringBuilder(" WHERE 1=1");
        for(var filter:filters.entrySet()) {
            String field=filter.getKey(),value=filter.getValue();
            if(value==null || value.isBlank()) continue;
            if(value.length()>500) throw new UserInputException("Filtro demasiado largo");
            switch(field) {
                case "title","author" -> {
                    if(!type.equals("calibre")) throw new UserInputException("Filtro desconocido");
                    where.append(" AND epl_saved_contains(json_extract(value,'$.").append(field.equals("author")?"authors":"title").append("'),?)=1");arguments.add(value);
                }
                case "eplId" -> {
                    if(!type.equals("calibre") || !value.matches("[0-9]{1,18}")) throw new UserInputException("EPL ID inválido");
                    where.append(" AND json_extract(value,'$.eplId')=?");arguments.add(Long.parseLong(value));
                }
                case "status" -> {
                    if(!type.equals("downloads")) throw new UserInputException("Filtro desconocido");
                    var statuses=value.split(",",-1);
                    for(var status:statuses) try {com.rlibanez.eplsync.torrent.downloads.DownloadStatus.valueOf(status);}
                    catch(IllegalArgumentException ex) {throw new UserInputException("Estado inválido");}
                    where.append(" AND json_extract(value,'$.status') IN (").append(String.join(",",Collections.nCopies(statuses.length,"?"))).append(")");arguments.addAll(List.of(statuses));
                }
                case "availability" -> {
                    if(!type.equals("calibre") || !Set.of("READY","MISSING","UNAVAILABLE","UNKNOWN").contains(value)) throw new UserInputException("Disponibilidad inválida");
                    where.append(" AND coalesce(json_extract(value,'$.downloadAvailability'),'UNKNOWN')=?");arguments.add(value);
                }
                default -> throw new UserInputException("Filtro desconocido");
            }
        }
        String sql="WITH snapshot AS MATERIALIZED (SELECT results_json"+source()+" AND searched_at=?), filtered AS MATERIALIZED (SELECT j.key,j.value FROM snapshot,json_each(snapshot.results_json) j"+where+")"
            +" SELECT (SELECT count(*) FROM snapshot),(SELECT count(*) FROM filtered),(SELECT json_group_array(json(value)) FROM (SELECT value FROM filtered ORDER BY "+String.join(",",order)+" LIMIT ? OFFSET ?))";
        arguments.add(size);arguments.add((long)page*size);
        return jdbc.execute((ConnectionCallback<PageResponse<Map<String,Object>>>)connection -> {
            org.sqlite.Function.create(connection.unwrap(org.sqlite.SQLiteConnection.class),"epl_saved_contains",new org.sqlite.Function() {
                @Override protected void xFunc() throws java.sql.SQLException {
                    String value=value_text(0),needle=value_text(1);
                    result(value!=null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))?1:0);
                }
            });
            try(var statement=connection.prepareStatement(sql)) {
                for(int i=0;i<arguments.size();i++) statement.setObject(i+1,arguments.get(i));
                try(var result=statement.executeQuery()) {
                    result.next();
                    if(result.getInt(1)==0 && generation>0) throw new UserInputException("La búsqueda guardada ha cambiado; actualiza la vista");
                    long total=result.getLong(2);int pages=(int)((total+size-1)/size);
                    List<Map<String,Object>> rows=json.readValue(result.getString(3),List.class);
                    return new PageResponse<>(rows,new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page<pages-1,page>0));
                }
            }
        });
    }
}
