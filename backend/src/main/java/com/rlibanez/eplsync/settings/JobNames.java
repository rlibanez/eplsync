package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.exception.UserInputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobNames {
    public record Name(String name) {}
    private final JdbcTemplate jdbc;
    private final RevisionCleanup cleanup;
    public JobNames(JdbcTemplate jdbc,RevisionCleanup cleanup) {this.jdbc=jdbc;this.cleanup=cleanup;}
    private String table(String source,String id,boolean editing) {
        if(source.equals("cleanup")) {
            var job=cleanup.get(id);
            if(editing) Permission.require(job.source().equals("downloads")?Permission.DOWNLOADS_DELETE:Permission.CALIBRE_DELETE);
            return "revision_cleanup_jobs";
        }
        if(source.equals("downloads")) {Permission.require(Permission.TORRENT_JOBS_MANAGE);return "torrent_bulk_jobs";}
        if(!source.equals("calibre")) throw new UserInputException("Tipo de trabajo inválido");
        if(editing) Permission.require(Permission.CALIBRE_JOBS_MANAGE);
        else if(!Permission.has(Permission.CALIBRE_SEND)) Permission.require(Permission.CALIBRE_JOBS_MANAGE);
        return "calibre_jobs";
    }
    public Name get(String source,String id) {
        var rows=jdbc.queryForList("SELECT name FROM "+table(source,id,false)+" WHERE id=?",id);
        if(rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return new Name((String)rows.getFirst().get("name"));
    }
    @org.springframework.transaction.annotation.Transactional
    public Name save(String source,String id,Name input) {
        String table=table(source,id,true);
        if(input==null || input.name()==null) throw new UserInputException("Indica el nombre del trabajo");
        String name=input.name().strip();
        if(name.length()>150 || name.codePoints().anyMatch(Character::isISOControl)) throw new UserInputException("El nombre admite hasta 150 caracteres y no puede contener saltos de línea ni caracteres de control");
        if(jdbc.update("UPDATE "+table+" SET name=? WHERE id=?",name.isEmpty()?null:name,id)!=1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return new Name(name.isEmpty()?null:name);
    }
}
