package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.settings.CalibreSettings;
import com.rlibanez.eplsync.security.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Export the selected entries in the local published Calibre snapshot, never remote files. */
@Service
public class CalibreDataExport {
    public record Selection(@NotNull @Size(min=1,max=100000) List<@NotNull @Positive Long> ids) {}
    public record Request(@NotNull @Valid Selection filters,@NotNull CatalogDataExport.Format format,
            CatalogDataExport.Separator separator,@NotNull @Size(min=1,max=13) List<@NotNull String> columns,
            @Size(max=256) String ordering,
            @Size(max=32) @Pattern(regexp="[a-zA-Z0-9]+(?:[._-][a-zA-Z0-9]+)*") String extension) {
        public String filename() {return "calibre."+(extension==null?format.defaultExtension():extension);}
    }
    private static final Map<String,String> COLUMNS=Map.ofEntries(
        Map.entry("calibreId","calibre_id"),Map.entry("eplId","epl_id"),Map.entry("title","title"),Map.entry("authors","authors"),
        Map.entry("formats","formats"),Map.entry("revision","revision"),Map.entry("catalogRevision","catalog_revision"),
        Map.entry("comparison","comparison"),Map.entry("presence","presence"),Map.entry("hardcover","hardcover"),
        Map.entry("hardcoverBook","hardcoverBook"),Map.entry("hardcoverEdition","hardcoverEdition"),Map.entry("hardcoverNoMatch","hardcover_no_match"));
    private final JdbcTemplate jdbc;
    private final CalibreSettings settings;
    private final CatalogDataExport writer;
    private final TransactionTemplate read;
    private final java.util.concurrent.Semaphore active;
    private final JsonMapper json=JsonMapper.builder().build();
    public CalibreDataExport(JdbcTemplate jdbc,CalibreSettings settings,CatalogDataExport writer,PlatformTransactionManager manager,MagnetExportService exports) {
        this.jdbc=jdbc;this.settings=settings;this.writer=writer;active=exports.exportSlot();read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setTimeout((int)MagnetExportService.PREPARATION_TIME.toSeconds());
    }
    private CatalogDataExport.Request options(Request r) {
        if(new HashSet<>(r.columns()).size()!=r.columns().size() || !COLUMNS.keySet().containsAll(r.columns())) throw new UserInputException("Columnas de exportación inválidas o repetidas");
        return new CatalogDataExport.Request(new com.rlibanez.eplsync.filter.CatalogBookFilter(),r.format(),r.separator(),r.columns(),null,r.extension());
    }
    private String order(Request r) {
        var result=new ArrayList<String>();
        var fields=new HashMap<>(COLUMNS);fields.put("hardcover","coalesce(hardcover,'')||char(31)||coalesce(hardcoverBook,'')||char(31)||coalesce(hardcoverEdition,'')");
        String ordering=r.ordering()==null?"title,asc":r.ordering();
        if(ordering.split(";",-1).length>8) throw new UserInputException("Ordenación inválida");
        for(String value:ordering.split(";",-1)) {
            var parts=value.split(",",-1);
            if(parts.length!=2 || !fields.containsKey(parts[0]) || !Set.of("asc","desc").contains(parts[1])) throw new UserInputException("Ordenación inválida");
            String column=fields.get(parts[0]);
            if(Set.of("title","authors","hardcover").contains(parts[0])) column+=" COLLATE EPL_TEXT";
            result.add(column+" "+parts[1]);
        }
        result.add("calibre_id ASC");return String.join(",",result);
    }
    private String query(Request r,List<Object> args) {
        var c=settings.view();args.add(c.url());args.add(c.library());
        var names=new HashMap<>(Map.of("SLUG","hardcover","BOOK_ID","hardcover_book","EDITION_ID","hardcover_edition"));
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof Account account) {
            var custom=jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,"hardcover.mapping."+account.id());
            if(!custom.isEmpty()) {
                var map=json.readValue(custom.getFirst(),new tools.jackson.core.type.TypeReference<Map<String,String>>(){});
                for(String key:names.keySet()) if(map.containsKey(key)) names.put(key,map.get(key));
            }
        }
        // Parameters, not SQL interpolation: identifier mappings are user configurable.
        args.add(names.get("SLUG"));args.add(names.get("BOOK_ID"));args.add(names.get("EDITION_ID"));args.add(json.writeValueAsString(r.filters().ids()));
        return "SELECT * FROM (SELECT b.*,c.revision catalog_revision,"
            +"CASE WHEN b.epl_id IS NULL THEN 'UNIDENTIFIED' WHEN c.epl_id IS NULL THEN 'MISSING' ELSE 'PRESENT' END presence,"
            +"CASE WHEN c.epl_id IS NULL OR b.revision IS NULL THEN 'UNKNOWN' WHEN b.revision<c.revision THEN 'OUTDATED' WHEN b.revision>c.revision THEN 'NEWER' ELSE 'CURRENT' END comparison,"
            +"EXISTS(SELECT 1 FROM calibre_hardcover_reviews r WHERE r.destination=? AND r.library=? AND r.calibre_id=b.calibre_id AND r.calibre_uuid=b.calibre_uuid AND r.title=b.title AND r.authors=b.authors) hardcover_no_match,"
            +"(SELECT value FROM json_each(b.identifiers) WHERE key=?) hardcover,"
            +"(SELECT value FROM json_each(b.identifiers) WHERE key=?) hardcoverBook,"
            +"(SELECT value FROM json_each(b.identifiers) WHERE key=?) hardcoverEdition "
            +"FROM calibre_books b LEFT JOIN catalog_books c ON c.epl_id=b.epl_id WHERE b.active=true AND b.calibre_id IN (SELECT value FROM json_each(?))) ORDER BY "+order(r);
    }
    private int write(Request r,CatalogDataExport.Request options,java.io.OutputStream output,boolean preview) {
        long deadline=System.nanoTime()+MagnetExportService.PREPARATION_TIME.toNanos();long[] bytes={0};int[] count={0};
        java.util.function.Consumer<String> append=text->{
            if(System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.REQUEST_TIMEOUT,"La exportación superó el tiempo máximo permitido");
            byte[] data=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);bytes[0]+=data.length;
            if(bytes[0]>MagnetExportService.MAX_BYTES) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE,"La exportación supera el máximo de 128 MiB");
            try {output.write(data);}catch(java.io.IOException ex) {throw new java.io.UncheckedIOException(ex);}
        };
        var args=new ArrayList<Object>();String sql=query(r,args)+(preview?" LIMIT 5":"");append.accept(writer.start(options));
        jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)row->{
            var values=new ArrayList<Object>();
            for(String column:r.columns()) {
                Object value=row.getObject(COLUMNS.get(column));
                if(column.equals("formats") && value!=null) {
                    var formats=json.readValue(value.toString(),new tools.jackson.core.type.TypeReference<List<String>>(){});
                    value=r.format()==CatalogDataExport.Format.JSON?formats:String.join(", ",formats);
                } else if(column.equals("hardcoverNoMatch")) value=row.getBoolean("hardcover_no_match");
                values.add(value);
            }
            append.accept(writer.line(options,values,count[0]++==0));
        },args.toArray());
        append.accept(CatalogDataExport.end(options));return count[0];
    }
    public CatalogDataExport.Preview preview(Request r) {
        var options=options(r);return read.execute(status->{var out=new java.io.ByteArrayOutputStream();int rows=write(r,options,out,true);return new CatalogDataExport.Preview(out.toString(java.nio.charset.StandardCharsets.UTF_8),rows);});
    }
    public MagnetExportService.Prepared prepare(Request r) {
        var options=options(r);order(r);
        if(!active.tryAcquire()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Ya hay una exportación en curso");
        Path directory=null;boolean prepared=false;
        try {
            directory=Files.createTempDirectory("eplsync-calibre-export-");Path file=directory.resolve("export");
            try(var out=new java.io.BufferedOutputStream(Files.newOutputStream(file))) {read.executeWithoutResult(status->write(r,options,out,false));}
            var result=new MagnetExportService.Prepared(directory,file,active,MagnetExportService.TRANSFER_TIME);prepared=true;return result;
        } catch(java.io.IOException | java.io.UncheckedIOException ex) {throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE,"No se pudo preparar la exportación; comprueba el espacio temporal disponible",ex);}
        finally {if(!prepared) {MagnetExportService.clean(directory);active.release();}}
    }
}
