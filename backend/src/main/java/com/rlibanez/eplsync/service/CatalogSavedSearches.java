package com.rlibanez.eplsync.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class CatalogSavedSearches {
    public record Search(String id, String name, Map<String,List<String>> filters) {}
    public record Input(String name, Map<String,List<String>> filters) {}
    private static final Set<String> KEYS=Set.of("eplIdOperator","collectionOperator","genresOperator","revisionOperator","titleOperator","authorOperator","languageOperator","statusOperator","publicationStatusOperator","torrentStatusOperator","title","author","language","eplId","revision","genres","collection","status","publicationStatus","pagesFrom","pagesTo","publicationYear","publicationYearFrom","publicationYearTo","publicationDate","publicationDateFrom","publicationDateTo","addedFrom","addedTo","torrentStatus","torrentPresence","torrentRevision","calibrePresence","calibreRevision");
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    public CatalogSavedSearches(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Transactional(readOnly=true)
    public List<Search> list(String user) {
        return jdbc.query("SELECT id,name,filters FROM catalog_saved_searches WHERE user_id=? ORDER BY name COLLATE NOCASE,id",
            (rs,n)->new Search(rs.getString(1),rs.getString(2),decode(rs.getString(3))),user);
    }
    private Map<String,List<String>> decode(String value) {
        return json.readValue(value,new tools.jackson.core.type.TypeReference<Map<String,List<String>>>() {});
    }
    @Transactional
    public Search save(String user, String id, Input input) {
        if(input==null || input.name()==null || input.name().isBlank() || input.name().strip().length()>120 || input.name().contains("\0"))
            throw new UserInputException("Indica un nombre de búsqueda de entre 1 y 120 caracteres");
        if(id!=null && jdbc.queryForObject("SELECT count(*) FROM catalog_saved_searches WHERE id=? AND user_id=?",Long.class,id,user)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String name=input.name().strip();
        if(jdbc.queryForObject("SELECT count(*) FROM catalog_saved_searches WHERE user_id=? AND name=? AND id<>?",Long.class,user,name,id==null ? "" : id)>0)
            throw new UserInputException("Ya existe una búsqueda guardada con ese nombre");
        Map<String,List<String>> filters=input.filters();
        if(filters==null || !KEYS.containsAll(filters.keySet())) throw new UserInputException("Solo se pueden guardar filtros de la búsqueda avanzada");
        for(var values:filters.values()) if(values==null || values.stream().anyMatch(v->v==null || v.contains("\0")))
            throw new UserInputException("Los filtros contienen valores inválidos");
        String encoded=json.writeValueAsString(filters);
        if(encoded.length()>4_000_000) throw new UserInputException("La búsqueda guardada es demasiado grande");
        String searchId=id==null ? UUID.randomUUID().toString() : id;
        if(id==null) jdbc.update("INSERT INTO catalog_saved_searches(id,user_id,name,filters) VALUES(?,?,?,?)",searchId,user,name,encoded);
        else jdbc.update("UPDATE catalog_saved_searches SET name=?,filters=? WHERE id=? AND user_id=?",name,encoded,id,user);
        return new Search(searchId,name,filters);
    }
    @Transactional
    public void delete(String user,String id) {
        if(jdbc.update("DELETE FROM catalog_saved_searches WHERE id=? AND user_id=?",id,user)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
