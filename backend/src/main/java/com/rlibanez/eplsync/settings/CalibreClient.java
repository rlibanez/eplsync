package com.rlibanez.eplsync.settings;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.auth.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calibre content-server protocol, including Basic/Digest authentication. No redirects are followed. */
@Service
public class CalibreClient {
    public record Library(String id, String name) {}
    public record Check(boolean connected, List<Library> libraries, String defaultLibrary) {}
    public record Book(long calibreId, String title, List<String> authors, Long eplId, BigDecimal revision, boolean linked) {}
    public record Books(List<Book> items, long total, int page, int size) {}
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "calibre-request-timeout"); t.setDaemon(true); return t;
    });
    private final Semaphore permits = new Semaphore(2);
    private final JsonMapper json = JsonMapper.builder().build();
    public Check check(CalibreSettings.Connection connection) {
        return limited(() -> libraries(connection));
    }
    public Books books(CalibreSettings.Connection c, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new com.rlibanez.eplsync.exception.UserInputException("Página o tamaño de página inválidos (1–100 libros)");
        if (c.library().isBlank()) throw new com.rlibanez.eplsync.exception.UserInputException("Selecciona una biblioteca de Calibre");
        return limited(() -> {
            var library = segment(c.library());
            var search = get(c, "/ajax/search/" + library + "?offset=" + ((long)page * size) + "&num=" + size);
            var ids = search.path("book_ids");
            long total = search.path("total_num").asLong(-1);
            if (!ids.isArray() || ids.size() > size || total < 0) throw failure("Calibre ha devuelto un listado inválido");
            List<Long> keys = new ArrayList<>();
            for (var id : ids) {
                if (!id.isIntegralNumber() || id.asLong() <= 0) throw failure("Calibre ha devuelto un identificador inválido");
                keys.add(id.asLong());
            }
            if (keys.isEmpty()) return new Books(List.of(), total, page, size);
            var data = get(c, "/ajax/books/" + library + "?ids=" + String.join(",", keys.stream().map(String::valueOf).toList()));
            List<Book> books = new ArrayList<>();
            for (long id : keys) {
                var b = data.path(String.valueOf(id));
                if (!b.isObject()) throw failure("Calibre no ha devuelto los metadatos solicitados");
                List<String> authors = new ArrayList<>();
                if (b.path("authors").isArray()) for (var author : b.path("authors")) authors.add(author.asString());
                var identifiers = b.path("identifiers");
                Long epl = positiveId(identifiers.path("epl").asString(""));
                BigDecimal revision = revision(identifiers.path("revision").asString(""));
                books.add(new Book(id, b.path("title").asString(""), authors, epl, revision, epl != null && revision != null));
            }
            return new Books(books, total, page, size);
        });
    }
    private Check libraries(CalibreSettings.Connection c) {
        var info = get(c, "/ajax/library-info");
        var map = info.path("library_map");
        if (!map.isObject() || map.size() > 1000) throw failure("La URL no corresponde a un servidor de contenido de Calibre válido");
        List<Library> list = new ArrayList<>();
        for (var entry : map.properties()) list.add(new Library(entry.getKey(), entry.getValue().asString()));
        return new Check(true, list, info.path("default_library").asString(""));
    }
    private <T> T limited(java.util.function.Supplier<T> action) {
        if (!permits.tryAcquire()) throw new Busy();
        try { return action.get(); } finally { permits.release(); }
    }
    private JsonNode get(CalibreSettings.Connection c, String path) {
        return request(c,new HttpGet(URI.create(c.url()+path)),false);
    }
    public static final class PermanentFailure extends ResponseStatusException {
        public PermanentFailure(String reason) {super(HttpStatus.BAD_GATEWAY,reason);}
    }
    public static class TransientFailure extends ResponseStatusException {
        public TransientFailure(HttpStatus status,String reason,Throwable cause) {super(status,reason,cause);}
    }
    public static final class Busy extends TransientFailure {
        public Busy() {super(HttpStatus.TOO_MANY_REQUESTS,"La capacidad de consultas de Calibre está ocupada; se reintentará automáticamente",null);}
    }
    public static class UnconfirmedUpload extends RuntimeException {
        public UnconfirmedUpload() {this(null);}
        public UnconfirmedUpload(Throwable cause) { super("No se ha confirmado el envío; comprueba Calibre antes de repetirlo",cause); }
    }
    private JsonNode request(CalibreSettings.Connection c,org.apache.hc.client5.http.classic.methods.HttpUriRequestBase request,boolean upload) {
        var uri = URI.create(c.url());
        var credentials = new BasicCredentialsProvider();
        if (!c.username().isEmpty()) credentials.setCredentials(new AuthScope(new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort())),
                new UsernamePasswordCredentials(c.username(), c.password().toCharArray()));
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(manager).setDefaultCredentialsProvider(credentials)
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(10)).build())
                .disableRedirectHandling().disableAutomaticRetries().disableContentCompression().build()) {
            request.setHeader("Accept", "application/json");
            var deadline = TIMER.schedule(request::cancel, upload ? 60 : 10, TimeUnit.SECONDS);
            try (var response = client.executeOpen(null, request, null)) {
                int status = response.getCode();
                if (status == 401 || status == 403) throw failure("Calibre ha rechazado el acceso; revisa el usuario, la contraseña y los permisos de biblioteca");
                if (status >= 300 && status < 400) throw failure("Calibre ha respondido con una redirección; configura la URL final del servidor de contenido");
                if(upload && (status>=500 || status==200 && response.getEntity()==null)) throw new UnconfirmedUpload(new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(status),"Respuesta de envío no confirmada"));
                if (!upload && (status==429 || status>=500)) throw new TransientFailure(HttpStatus.valueOf(status),"Calibre no está disponible temporalmente (HTTP "+status+")",null);
                if (!upload && status==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"El libro ya no existe en Calibre");
                if (status != 200 || response.getEntity() == null) throw failure("Calibre no ha devuelto una respuesta válida (HTTP " + status + ")");
                var type = response.getFirstHeader("Content-Type");
                if (type == null || !type.getValue().toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    if(upload) throw new UnconfirmedUpload();
                    throw failure("La URL no corresponde al servidor de contenido de Calibre; comprueba el puerto"); }
                try (var body = response.getEntity().getContent()) {
                    byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                    if(upload && bytes.length>MAX_BYTES) throw new UnconfirmedUpload();
                    if (bytes.length > MAX_BYTES) throw failure("La respuesta de Calibre supera el límite de 2 MiB; reduce el tamaño de página");
                    return json.readTree(bytes);
                }
            } finally { deadline.cancel(false); }
        } catch (ResponseStatusException | UnconfirmedUpload ex) { throw ex; }
        catch (Exception ex) { if(upload) throw new UnconfirmedUpload(ex); throw new TransientFailure(HttpStatus.BAD_GATEWAY,"No se puede consultar Calibre; revisa la URL, que el servidor de contenido esté activo y la conexión de red",ex); }
    }
    /** Search live identifiers and EPUB format, including later matches with the same revision. */
    public Long findRevision(CalibreSettings.Connection c,long eplId,BigDecimal revision) {
        return limited(() -> {
            String path="/ajax/search/"+segment(c.library())+"?num=100&query="+segment("identifiers:=epl:"+eplId);
            for(int offset=0;offset<10000;offset+=100) {
                var search=get(c,path+"&offset="+offset);
                var ids=search.path("book_ids");long total=search.path("total_num").asLong(-1);
                if(!ids.isArray() || ids.size()>100 || total<0 || total>10000 || (ids.isEmpty() && offset<total)) throw failure("Calibre devolvió una búsqueda incompleta de identificadores");
                if(!ids.isEmpty()) {
                    var keys=new ArrayList<String>();
                    for(var id:ids) { if(!id.isIntegralNumber() || id.asLong()<=0) throw failure("Calibre devolvió un identificador inválido");keys.add(id.asString()); }
                    var books=get(c,"/ajax/books/"+segment(c.library())+"?ids="+String.join(",",keys));
                    for(String id:keys) {
                        var book=books.path(id);
                        if(!book.isObject()) throw failure("Calibre no devolvió los metadatos solicitados");
                        var identifiers=book.path("identifiers");
                        var actual=revision(identifiers.path("revision").asString(""));
                        if(java.util.Objects.equals(positiveId(identifiers.path("epl").asString("")),eplId) && actual!=null && actual.compareTo(revision)==0 && epubFormat(book)) return Long.valueOf(id);
                    }
                }
                if(offset+ids.size()>=total) return null;
            }
            throw failure("La búsqueda de revisiones de Calibre supera el límite permitido");
        });
    }
    public long uploadEpub(CalibreSettings.Connection c,java.nio.file.Path file,String name,String token) {
        return limited(() -> {
            var request=new org.apache.hc.client5.http.classic.methods.HttpPost(c.url()+"/cdb/add-book/"+segment(token)+"/1/"+segment(name)+"/"+segment(c.library()));
            request.setEntity(new org.apache.hc.core5.http.io.entity.FileEntity(file.toFile(),org.apache.hc.core5.http.ContentType.create("application/epub+zip")));
            var result=request(c,request,true);
            if(!result.path("book_id").isIntegralNumber() || result.path("book_id").asLong()<=0) throw new UnconfirmedUpload();
            return result.path("book_id").asLong();
        });
    }
    public Book book(CalibreSettings.Connection c,long id) {
        return limited(() -> {
            var b=get(c,"/ajax/book/"+id+"/"+segment(c.library()));
            if(!b.isObject() || b.path("title").isMissingNode()) throw failure("Calibre no devolvió los metadatos del libro enviado");
            var authors=new ArrayList<String>();for(var a:b.path("authors")) authors.add(a.asString());
            var epl=positiveId(b.path("identifiers").path("epl").asString(""));
            var rev=revision(b.path("identifiers").path("revision").asString(""));
            return new Book(id,b.path("title").asString(""),authors,epl,rev,epl!=null && rev!=null);
        });
    }
    public boolean hasEpub(CalibreSettings.Connection c,long id) {
        return limited(() -> epubFormat(get(c,"/ajax/book/"+id+"/"+segment(c.library()))));
    }
    private static boolean epubFormat(JsonNode book) {
        if(!book.path("formats").isArray()) throw failure("Calibre no devolvió los formatos del libro; no se puede verificar el EPUB");
        for(var format:book.path("formats")) if(format.asString().equalsIgnoreCase("EPUB")) return true;
        return false;
    }
    public List<String> formats(CalibreSettings.Connection c,long id) {
        return limited(() -> {
            var values=get(c,"/ajax/book/"+id+"/"+segment(c.library())).path("formats");
            if(!values.isArray() || values.size()>32) throw failure("Calibre no devolvió los formatos; no se puede preparar el borrado");
            var formats=new ArrayList<String>();
            for(var value:values) {
                if(!value.isString() || value.asString().length()>32) throw failure("Formato de Calibre inválido");
                formats.add(value.asString().toUpperCase(java.util.Locale.ROOT));
            }
            return List.copyOf(formats);
        });
    }
    /** Delete calls are never retried by the HTTP client. The queue verifies their result separately. */
    public void remove(CalibreSettings.Connection c,long id,boolean entireBook) {
        limited(() -> {
            var post=new org.apache.hc.client5.http.classic.methods.HttpPost(c.url()+(entireBook ? "/cdb/delete-books/" : "/cdb/set-fields/")+id+"/"+segment(c.library()));
            post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(entireBook ? "{}" : "{\"changes\":{\"removed_formats\":[\"EPUB\"]}}",org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
            request(c,post,false);return null;
        });
    }
    /** Fetch to a bounded temporary file, without following redirects or buffering the EPUB in memory. */
    public void downloadEpub(CalibreSettings.Connection c,long id,java.nio.file.Path target,long maxBytes) {
        limited(() -> {
            var uri=URI.create(c.url()+"/get/epub/"+id+"/"+segment(c.library()));
            var credentials=new BasicCredentialsProvider();
            if(!c.username().isEmpty()) credentials.setCredentials(new AuthScope(new HttpHost(uri.getScheme(),uri.getHost(),uri.getPort())),
                    new UsernamePasswordCredentials(c.username(),c.password().toCharArray()));
            var manager=PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
            try(var http=HttpClients.custom().setConnectionManager(manager).setDefaultCredentialsProvider(credentials)
                    .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(10)).build())
                    .disableRedirectHandling().disableAutomaticRetries().disableContentCompression().build()) {
                var request=new HttpGet(uri);
                request.setHeader("Accept","application/epub+zip");
                var deadline=TIMER.schedule(request::cancel,60,TimeUnit.SECONDS);
                try(var response=http.executeOpen(null,request,null)) {
                    int status=response.getCode();
                    if(status==401 || status==403) throw failure("Calibre ha rechazado el acceso; revisa el usuario, la contraseña y los permisos de biblioteca");
                    if(status==404) throw failure("Calibre ID "+id+": el libro no existe o no tiene formato EPUB");
                    if(status>=300 && status<400) throw failure("Calibre ha respondido con una redirección; configura la URL final del servidor de contenido");
                    if(status!=200 || response.getEntity()==null) throw failure("Calibre ID "+id+": no se puede descargar el EPUB (HTTP "+status+")");
                    if(response.getEntity().getContentLength()>maxBytes) throw failure("La descarga de Calibre supera el límite de tamaño");
                    try(var body=response.getEntity().getContent();var output=java.nio.file.Files.newOutputStream(target)) {
                        byte[] buffer=new byte[65536];long total=0;int count;
                        while((count=body.read(buffer))!=-1) {
                            total+=count;
                            if(total>maxBytes || Thread.currentThread().isInterrupted()) throw failure("La descarga de Calibre supera el límite de tamaño o ha sido cancelada");
                            output.write(buffer,0,count);
                        }
                        if(total==0) throw failure("Calibre ID "+id+": el EPUB está vacío");
                    }
                    try(var zip=new java.util.zip.ZipFile(target.toFile())) {
                        var mimetype=zip.getEntry("mimetype");
                        if(mimetype==null || mimetype.getSize()!=20 || zip.getEntry("META-INF/container.xml")==null)
                            throw failure("Calibre ID "+id+": la respuesta no es un EPUB válido");
                        try(var body=zip.getInputStream(mimetype)) {
                            if(!java.util.Arrays.equals(body.readNBytes(21),"application/epub+zip".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                                throw failure("Calibre ID "+id+": la respuesta no es un EPUB válido");
                        }
                    }
                } finally { deadline.cancel(false); }
            } catch(ResponseStatusException ex) { throw ex; }
            catch(Exception ex) { throw failure("Calibre ID "+id+": no se puede descargar el EPUB; revisa el servidor y la conexión"); }
            return null;
        });
    }
    static Long positiveId(String text) {
        try { long value = Long.parseLong(text); return value > 0 ? value : null; } catch (Exception ex) { return null; }
    }
    static BigDecimal revision(String text) {
        if (!text.matches("[0-9]{1,18}(\\.[0-9]{1,18})?")) return null;
        try { var value = new BigDecimal(text); return value.signum() > 0 ? value : null; } catch (Exception ex) { return null; }
    }
    private static String segment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static ResponseStatusException failure(String text) { return new PermanentFailure(text); }
}
