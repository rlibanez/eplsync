package com.rlibanez.eplsync.settings;

import java.net.URI;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, bounded GraphQL client. The endpoint is never supplied by the browser. */
@Service
public class HardcoverClient {
    public record Match(long id,String slug,String title,List<String> authors,String coverUrl,Long ratingsCount) {
        public Match(long id,String slug,String title,List<String> authors) {this(id,slug,title,authors,"",null);}
        public Match(long id,String slug,String title,List<String> authors,String coverUrl) {this(id,slug,title,authors,coverUrl,null);}
    }
    public record Search(List<Match> items,boolean hasNext) {}
    public record Edition(long id,String title,String language,String publisher,String isbn10,String isbn13,String asin,String coverUrl,Long usersCount) {
        public Edition(long id,String title,String language,String publisher,String isbn10,String isbn13,String asin) {this(id,title,language,publisher,isbn10,isbn13,asin,"",null);}
        public Edition(long id,String title,String language,String publisher,String isbn10,String isbn13,String asin,String coverUrl) {this(id,title,language,publisher,isbn10,isbn13,asin,coverUrl,null);}
    }
    public record Editions(List<Edition> items,boolean hasNext) {}
    public record Language(String code,String name) {}
    public record EditionBook(Match book,Edition edition) {}
    private final java.util.function.Supplier<String> key;
    private final URI endpoint;
    private final Semaphore requests=new Semaphore(1);
    private final HardcoverUsage usage=new HardcoverUsage();
    private long nextRequest;
    private static final java.util.concurrent.ScheduledExecutorService TIMER=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"hardcover-timeout");thread.setDaemon(true);return thread;});
    private final JsonMapper json=JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired
    public HardcoverClient(HardcoverSettings settings) {this(settings,URI.create("https://api.hardcover.app/v1/graphql"));}
    HardcoverClient(HardcoverSettings settings,URI endpoint) {this(settings::apiKey,endpoint);}
    public HardcoverClient(String key) {this(key,URI.create("https://api.hardcover.app/v1/graphql"));}
    HardcoverClient(String key,URI endpoint) {this(()->HardcoverSettings.normalize(key),endpoint);}
    private HardcoverClient(java.util.function.Supplier<String> key,URI endpoint) {this.key=key;this.endpoint=endpoint;}
    public boolean configured() {return !key.get().isBlank();}
    HardcoverUsage.Snapshot usage() {
        try {return usage.snapshot(key.get());}
        catch(com.rlibanez.eplsync.exception.UserInputException ex) {return null;}
    }
    HardcoverUsage.Snapshot usageFor(String candidate) {return usage.snapshot(HardcoverSettings.normalize(candidate));}

    public Search search(String title,List<String> authors,int page) {
        return search(title,authors,page,true,true);
    }
    public Search search(String title,List<String> authors,int page,boolean useTitle,boolean useAuthor) {
        page(page);if((useTitle && (title==null || title.isBlank())) || (useAuthor && (authors==null || authors.isEmpty() || authors.stream().allMatch(String::isBlank)))) throw invalid("Se necesita el título y el autor del libro de Calibre");
        if(!useTitle && !useAuthor) throw invalid("Selecciona al menos un campo de búsqueda");
        String fields=useTitle?(useAuthor?"title,author_names":"title"):"author_names";
        String query=((useTitle?title:"")+" "+(useAuthor?String.join(" ",authors):"")).strip();if(query.length()>1500) throw invalid("El título y los autores son demasiado largos");
        String sort="_text_match:desc,ratings_count:desc";
        // Only a quoted literal enters the optional filter; unusual delimiters retain normal relevance.
        if(useTitle && title.codePoints().noneMatch(c->c=='`' || c=='\\' || Character.isISOControl(c))) sort="_eval(title:=`"+title.strip()+"`):desc,"+sort;
        var data=query("query Search($q:String!,$page:Int!,$sort:String!){search(query:$q,query_type:\"Book\",fields:\""+fields+"\",weights:\""+(useTitle && useAuthor?"3,1":"1")+"\",typos:\""+(useTitle && useAuthor?"1,1":"1")+"\",sort:$sort,per_page:20,page:$page){results}}",Map.of("q",query,"page",page,"sort",sort));
        var results=data.path("search").path("results");if(results.isString()) results=json.readTree(results.asString());
        if(!results.path("hits").isArray() || results.path("hits").size()>20) throw failure("Hardcover devolvió resultados de búsqueda inválidos");
        var items=new ArrayList<Match>();
        for(var hit:results.path("hits")) {
            var d=hit.path("document");long id=id(d.path("id"));var names=new ArrayList<String>();for(var name:d.path("author_names")) names.add(text(name));
            items.add(new Match(id,text(d.path("slug")),text(d.path("title")),List.copyOf(names),cover(d),count(d,"ratings_count")));
        }
        return new Search(List.copyOf(items),(long)page*20<results.path("found").asLong(0));
    }
    public Search searchIdentifiers(List<String> isbns,String asin,int page) {
        page(page);
        var conditions=new ArrayList<Map<String,Object>>();
        if(!isbns.isEmpty()) conditions.add(Map.of("_or",List.of(Map.of("isbn_10",Map.of("_in",isbns)),Map.of("isbn_13",Map.of("_in",isbns)))));
        if(!asin.isBlank()) conditions.add(Map.of("asin",Map.of("_eq",asin)));
        if(conditions.isEmpty()) throw invalid("No hay identificadores válidos para buscar");
        var rows=query("query IdentifierBooks($where:books_bool_exp!,$offset:Int!){books(where:$where,order_by:[{ratings_count:desc},{reviews_count:desc},{id:asc}],limit:21,offset:$offset){id slug title ratings_count image{url} contributions{author{name}}}}",Map.of("where",Map.of("editions",Map.of("_and",conditions)),"offset",(page-1)*20)).path("books");
        if(!rows.isArray() || rows.size()>21) throw failure("Hardcover devolvió libros inválidos");
        var items=new ArrayList<Match>();
        for(int n=0;n<Math.min(20,rows.size());n++) {
            var b=rows.get(n);var authors=new ArrayList<String>();for(var c:b.path("contributions")) authors.add(text(c.path("author").path("name")));
            items.add(new Match(id(b.path("id")),text(b.path("slug")),text(b.path("title")),List.copyOf(authors),cover(b),count(b,"ratings_count")));
        }
        return new Search(List.copyOf(items),rows.size()>20);
    }
    public Match bookBySlug(String slug) {
        if(slug==null || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length()>250) throw invalid("Introduce un slug de Hardcover válido");
        var rows=query("query BookSlug($slug:String!){books(where:{slug:{_eq:$slug}},limit:2){id}}",Map.of("slug",slug)).path("books");
        if(!rows.isArray() || rows.size()!=1) throw invalid("No se encontró una obra inequívoca con ese slug de Hardcover");
        return book(id(rows.get(0).path("id")));
    }
    public Match book(long id) {
        positive(id);var rows=query("query Book($id:Int!){books(where:{id:{_eq:$id}},limit:1){id slug title ratings_count image{url} contributions{author{name}}}}",Map.of("id",id)).path("books");
        if(!rows.isArray() || rows.size()!=1 || id(rows.get(0).path("id"))!=id) throw invalid("El libro seleccionado ya no está disponible en Hardcover");
        var b=rows.get(0);var authors=new ArrayList<String>();for(var c:b.path("contributions")) authors.add(text(c.path("author").path("name")));
        return new Match(id,text(b.path("slug")),text(b.path("title")),List.copyOf(authors),cover(b),count(b,"ratings_count"));
    }
    private static final String EDITION_FIELDS="id book_id users_count image{url} title isbn_10 isbn_13 asin language{code2} publisher{name}";
    public List<Language> editionLanguages(long book) {
        positive(book);
        var rows=query("query EditionLanguages($book:Int!){editions(where:{book_id:{_eq:$book}},distinct_on:language_id,order_by:{language_id:asc},limit:1001){language{code2 code3 language}}}",Map.of("book",book)).path("editions");
        if(!rows.isArray() || rows.size()>1000) throw failure("Hardcover devolvió idiomas inválidos");
        var languages=new TreeMap<String,Language>();
        for(var row:rows) {var l=row.path("language");String code=text(l.path("code2"));if(code.isBlank()) code=text(l.path("code3"));code=code.toLowerCase(Locale.ROOT);if(code.matches("[a-z]{2,3}")) languages.put(code,new Language(code,text(l.path("language"))));}
        return List.copyOf(languages.values());
    }
    public Editions editions(long book,int page) {return editions(book,page,"");}
    public Editions editions(long book,int page,String language) {
        positive(book);page(page);
        if(language==null || !language.isEmpty() && !language.matches("[a-z]{2,3}")) throw invalid("Selecciona un idioma de edición válido");
        var where=new LinkedHashMap<String,Object>();where.put("book_id",Map.of("_eq",book));
        if(!language.isEmpty()) where.put("language",Map.of("_or",List.of(Map.of("code2",Map.of("_eq",language)),Map.of("code3",Map.of("_eq",language)))));
        var rows=query("query Editions($where:editions_bool_exp!,$offset:Int!){editions(where:$where,order_by:[{users_count:desc},{id:asc}],limit:21,offset:$offset){"+EDITION_FIELDS+"}}",Map.of("where",where,"offset",(page-1)*20)).path("editions");
        if(!rows.isArray() || rows.size()>21) throw failure("Hardcover devolvió ediciones inválidas");
        var result=new ArrayList<Edition>();for(int n=0;n<Math.min(20,rows.size());n++) {if(rows.get(n).path("book_id").asLong(0)!=book) throw invalid("Hardcover devolvió una edición de otro libro");result.add(edition(rows.get(n)));}
        return new Editions(List.copyOf(result),rows.size()>20);
    }
    public EditionBook editionBook(long id) {
        positive(id);
        var rows=query("query EditionBook($id:Int!){editions(where:{id:{_eq:$id}},limit:1){"+EDITION_FIELDS+"}}",Map.of("id",id)).path("editions");
        if(!rows.isArray() || rows.size()!=1 || id(rows.get(0).path("id"))!=id) throw invalid("La edición indicada no existe en Hardcover");
        var edition=edition(rows.get(0));long bookId=id(rows.get(0).path("book_id"));
        return new EditionBook(book(bookId),edition);
    }
    public Edition edition(long book,long edition) {
        positive(book);positive(edition);
        var rows=query("query Edition($book:Int!,$id:Int!){editions(where:{book_id:{_eq:$book},id:{_eq:$id}},limit:1){"+EDITION_FIELDS+"}}",Map.of("book",book,"id",edition)).path("editions");
        if(!rows.isArray() || rows.size()!=1 || id(rows.get(0).path("id"))!=edition || rows.get(0).path("book_id").asLong(0)!=book) throw invalid("La edición no pertenece al libro seleccionado o ya no está disponible");
        return edition(rows.get(0));
    }
    private Edition edition(JsonNode e) {return new Edition(id(e.path("id")),text(e.path("title")),text(e.path("language").path("code2")),text(e.path("publisher").path("name")),text(e.path("isbn_10")),text(e.path("isbn_13")),text(e.path("asin")),cover(e),count(e,"users_count"));}
    private static Long count(JsonNode item,String field) {var value=item.path(field);return value.isNumber() && value.asLong(-1)>=0 ? value.asLong() : null;}
    private static String cover(JsonNode item) {
        String value=text(item.path("image").path("url"));
        if(value.isBlank()) value=text(item.path("cached_image").path("url"));
        try {var uri=URI.create(value);if(value.length()<=2048 && "https".equalsIgnoreCase(uri.getScheme()) && "assets.hardcover.app".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443)) return uri.toString();}catch(IllegalArgumentException ignored) {}
        return "";
    }
    JsonNode query(String query,Map<String,?> variables) {
        return query(query,variables,key.get());
    }
    public void checkKey(String value) {
        var result=query("query HardcoverConnection{books(limit:1){id}}",Map.of(),HardcoverSettings.normalize(value));
        if(!result.path("books").isArray()) throw failure("Hardcover devolvió una respuesta de conexión inválida");
    }
    private JsonNode query(String query,Map<String,?> variables,String apiKey) {
        if(apiKey.isBlank()) throw invalid("Configura la API key de Hardcover en Ajustes o mediante EPLSYNC_HARDCOVER_API_KEY");
        if(!requests.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Hardcover está ocupado; espera antes de volver a consultar");
        try {
            long wait=nextRequest-System.nanoTime();if(wait>0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);nextRequest=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            var manager=PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
            try(var client=HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(10)).build()).disableRedirectHandling().disableAutomaticRetries().disableContentCompression().build()) {
                var post=new HttpPost(endpoint);post.setHeader("Authorization","Bearer "+apiKey);post.setHeader("Accept","application/json");post.setEntity(new StringEntity(json.writeValueAsString(Map.of("query",query,"variables",variables)),ContentType.APPLICATION_JSON));
                var deadline=TIMER.schedule(post::cancel,10,java.util.concurrent.TimeUnit.SECONDS);
                try(var response=client.executeOpen(null,post,null)) {
                    int status=response.getCode();
                    if(status==200 || status==429) {
                        var rate=response.getFirstHeader("RateLimit");var policy=response.getFirstHeader("RateLimit-Policy");
                        usage.capture(apiKey,rate==null?null:rate.getValue(),policy==null?null:policy.getValue());
                    }

                    if(status==401 || status==403) throw invalid("Hardcover ha rechazado la clave o sus permisos; revisa la API key en Ajustes > Calibre > Hardcover");
                    if(status==429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Se ha alcanzado el límite de consultas de Hardcover; espera antes de reintentar");
                    if(status!=200 || response.getEntity()==null) throw failure("Hardcover no está disponible o no ha devuelto una respuesta válida");
                    var type=response.getFirstHeader("Content-Type");if(type==null || !type.getValue().toLowerCase(Locale.ROOT).startsWith("application/json")) throw failure("Hardcover devolvió un formato de respuesta inesperado");
                    byte[] bytes=response.getEntity().getContent().readNBytes(2097153);if(bytes.length>2097152) throw failure("La respuesta de Hardcover supera el tamaño permitido");
                    var body=json.readTree(bytes);if(!body.path("errors").isMissingNode() && !body.path("errors").isNull() && (!body.path("errors").isArray() || body.path("errors").size()>0)) throw failure("Hardcover no pudo completar la consulta; no se utilizarán resultados parciales");
                    if(!body.path("data").isObject()) throw failure("Hardcover devolvió datos inválidos");return body.path("data");
                } finally {deadline.cancel(false);}
            }
        } catch(ResponseStatusException | com.rlibanez.eplsync.exception.UserInputException e) {throw e;}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw invalid("Consulta de Hardcover cancelada");}
        catch(Exception e) {throw failure("No se pudo consultar Hardcover; comprueba la conexión y vuelve a intentarlo");}
        finally {requests.release();}
    }
    private static String text(JsonNode value) {String result=value.asString("");if(result.length()>2000) throw failure("Hardcover devolvió un texto demasiado largo");return result;}
    private static long id(JsonNode value) {long id=value.asLong(0);positive(id);return id;}
    private static void positive(long id) {if(id<=0 || id>Integer.MAX_VALUE) throw invalid("Identificador de Hardcover inválido");}
    private static void page(int page) {if(page<1 || page>10000) throw invalid("Página de Hardcover inválida");}
    private static com.rlibanez.eplsync.exception.UserInputException invalid(String message) {return new com.rlibanez.eplsync.exception.UserInputException(message);}
    private static ResponseStatusException failure(String message) {return new ResponseStatusException(HttpStatus.BAD_GATEWAY,message);}
}
