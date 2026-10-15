package com.rlibanez.eplsync.settings;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.rlibanez.eplsync.exception.UserInputException;

/** Short-lived, session-bound downloads. No credentials or permanent copies are retained. */
@Service
public class CalibreEpubDownloads {
    static final long MAX_TOTAL=512L*1024*1024, MAX_FILE=256L*1024*1024;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final CalibreDownloadFiles temporary=new CalibreDownloadFiles(Path.of(System.getProperty("java.io.tmpdir"),"eplsync-calibre-downloads"));
    private int active;
    private boolean closed;
    // Held through preparation, pending delivery and streaming: at most 2 GiB temporary storage including ZIP creation.
    private final Semaphore slots=new Semaphore(2);
    private final Map<String,Pending> pending=new HashMap<>();
    private record Item(long id,String title) {}
    private record Pending(String session,String destination,Path file,String name,Instant expires) {}
    public record Ready(String url,int fileCount,boolean archive) {}
    @org.springframework.beans.factory.annotation.Autowired
    public CalibreEpubDownloads(CalibreSettings settings,CalibreClient client,JdbcTemplate jdbc) {
        this(settings,client,jdbc,java.time.Clock.systemUTC());
    }
    CalibreEpubDownloads(CalibreSettings settings,CalibreClient client,JdbcTemplate jdbc,java.time.Clock clock) {
        this.settings=settings;this.client=client;this.jdbc=jdbc;this.clock=clock;
    }
    @jakarta.annotation.PostConstruct void cleanAbandoned() { temporary.cleanAbandoned(); }
    public Ready prepare(String session,List<Long> ids) {
        begin();
        try { return prepareFiles(session,ids); }
        finally { end(); }
    }
    private Ready prepareFiles(String session,List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>1000 || ids.stream().anyMatch(id -> id==null || id<=0))
            throw new UserInputException("Selecciona entre 1 y 1000 libros de Calibre");
        expire();
        if(!slots.tryAcquire()) throw error(HttpStatus.TOO_MANY_REQUESTS,"Hay dos descargas de Calibre en preparación o pendientes; inténtalo de nuevo en unos segundos");
        Path output=null;
        var sources=new ArrayList<Path>();
        boolean retained=false;
        try {
            var connection=settings.connection();
            if(connection.library().isBlank()) throw new UserInputException("Selecciona una biblioteca de Calibre en Ajustes > Calibre");
            var selected=ids.stream().distinct().toList();
            String placeholders=String.join(",",Collections.nCopies(selected.size(),"?"));
            var books=jdbc.query("SELECT calibre_id,title FROM calibre_books WHERE active=true AND calibre_id IN ("+placeholders+")",
                    (r,n)->new Item(r.getLong(1),r.getString(2)),selected.toArray());
            if(books.size()!=selected.size()) throw error(HttpStatus.CONFLICT,"Algún libro ya no está en la biblioteca sincronizada; actualiza la tabla y vuelve a seleccionarlo");
            var byId=new HashMap<Long,Item>();books.forEach(b->byId.put(b.id(),b));
            long total=0, deadline=System.nanoTime()+java.time.Duration.ofMinutes(5).toNanos();
            for(long id:selected) {
                if(System.nanoTime()>deadline || Thread.currentThread().isInterrupted())
                    throw error(HttpStatus.CONFLICT,"La preparación de Calibre ha tardado demasiado; selecciona menos libros");
                var file=temporary.create(false);sources.add(file);
                client.downloadEpub(connection,id,file,Math.min(MAX_FILE,MAX_TOTAL-total));
                total+=Files.size(file);
                if(total>MAX_TOTAL) throw error(HttpStatus.CONFLICT,"La selección supera el límite de 512 MiB; selecciona menos libros");
            }
            boolean archive=selected.size()>1;
            String name;
            if(archive) {
                output=temporary.create(true);
                try(var zip=new ZipOutputStream(Files.newOutputStream(output),java.nio.charset.StandardCharsets.UTF_8)) {
                    zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                    for(int i=0;i<selected.size();i++) {
                        zip.putNextEntry(new ZipEntry(filename(byId.get(selected.get(i)))));
                        Files.copy(sources.get(i),zip);zip.closeEntry();
                    }
                }
                name="calibre-epubs.zip";
            } else { output=sources.removeFirst();name=filename(byId.get(selected.getFirst())); }
            if(!destination(connection).equals(destination(settings.connection())))
                throw error(HttpStatus.CONFLICT,"La configuración de Calibre ha cambiado; vuelve a iniciar la descarga");
            String token=UUID.randomUUID().toString();
            synchronized(this) {
                if(closed) throw error(HttpStatus.SERVICE_UNAVAILABLE,"Las descargas de Calibre se están cerrando");
                pending.put(token,new Pending(session,destination(connection),output,name,clock.instant().plusSeconds(120)));
            }
            retained=true;
            return new Ready("/api/calibre/epub-downloads/"+token,selected.size(),archive);
        } catch(IOException ex) { throw error(HttpStatus.SERVICE_UNAVAILABLE,"No se puede preparar la descarga de Calibre; revisa el espacio y los permisos del almacenamiento temporal"); }
        finally {
            sources.forEach(CalibreEpubDownloads::delete);
            if(!retained) { delete(output);slots.release(); }
        }
    }
    public void download(String session,String token,jakarta.servlet.http.HttpServletResponse response) throws IOException {
        begin();
        try { stream(session,token,response); }
        finally { end(); }
    }
    private void stream(String session,String token,jakarta.servlet.http.HttpServletResponse response) throws IOException {
        Pending batch;
        synchronized(this) {
            expire();batch=pending.get(token);
            if(batch==null || !batch.session().equals(session)) throw error(HttpStatus.NOT_FOUND,"La descarga ha caducado o no pertenece a esta sesión; vuelve a seleccionar los libros");
            pending.remove(token);
        }
        try {
            if(!batch.destination().equals(destination(settings.connection()))) throw error(HttpStatus.CONFLICT,"La configuración de Calibre ha cambiado; vuelve a iniciar la descarga");
            try(var input=Files.newInputStream(batch.file())) {
                response.setContentType(batch.name().endsWith(".zip")?"application/zip":"application/epub+zip");
                response.setHeader("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(batch.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString());
                response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
                response.setContentLengthLong(Files.size(batch.file()));input.transferTo(response.getOutputStream());
            }
        } finally { delete(batch.file());slots.release(); }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    public synchronized void expire() {
        var iterator=pending.values().iterator();
        while(iterator.hasNext()) { var batch=iterator.next();if(!batch.expires().isAfter(clock.instant())) { delete(batch.file());iterator.remove();slots.release(); } }
    }
    private synchronized void begin() {
        if(closed) throw error(HttpStatus.SERVICE_UNAVAILABLE,"Las descargas de Calibre se están cerrando");
        active++;
    }
    private synchronized void end() { if(--active==0 && closed) temporary.close(); }
    @jakarta.annotation.PreDestroy public synchronized void close() {
        closed=true;pending.values().forEach(p->delete(p.file()));pending.clear();
        if(active==0) temporary.close();
    }
    private static String destination(CalibreSettings.Connection c) {
        try {
            var json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(List.of(c.url(),c.username(),c.password(),c.library()));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json));
        } catch(Exception ex) { throw new IllegalStateException("Cannot identify Calibre connection",ex); }
    }
    static String filename(Item book) {
        String title=book.title()==null ? "Calibre" : book.title().replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_").strip();
        // Bound UTF-8 filename size and include the unique Calibre ID to distinguish revisions.
        title=title.substring(0,title.offsetByCodePoints(0,Math.min(40,title.codePointCount(0,title.length()))));
        return (title.isBlank()?"Calibre":title)+" ["+book.id()+"].epub";
    }
    private static void delete(Path file) { if(file!=null) try { Files.deleteIfExists(file); } catch(IOException ex) { org.slf4j.LoggerFactory.getLogger(CalibreEpubDownloads.class).warn("No se pudo eliminar un archivo temporal de descarga de Calibre"); } }
    private static ResponseStatusException error(HttpStatus status,String text) { return new ResponseStatusException(status,text); }
}
