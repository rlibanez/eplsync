package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CalibreHardcover {
    public enum Field {SLUG,BOOK_ID,EDITION_ID,ISBN10,ISBN13,ASIN}
    public record Configuration(boolean configured,List<Field> fields,Map<Field,String> mappings) {}
    public record Matches(String token,long calibreId,String title,List<String> authors,List<HardcoverClient.Match> items,boolean hasNext,int page,List<String> notices,String queryIsbn,String queryAsin,Map<String,String> identifiers,String coverUrl,boolean noMatch,String error) {}
    public record Value(Field field,String identifier,String existing,String value,String state) {}
    public record Preview(String token,long calibreId,String title,List<Value> values,boolean saved) {}
    private record Source(String owner,Instant expires,long calibreId,CalibreSettings.Connection connection,CalibreClient.IdentifierMetadata metadata,Set<Long> matches,boolean useTitle,boolean useAuthor,List<String> isbns,String asin,List<String> notices,String queryTitle,List<String> queryAuthors,LookupCache cache,java.util.concurrent.atomic.AtomicBoolean fallback) {}
    /** Bounded, short-lived data owned by one authenticated search, never shared across libraries. */
    private static final class LookupCache {
        private final LinkedHashMap<String,Object> entries=new LinkedHashMap<>(16,0.75f,true);
        @SuppressWarnings("unchecked")
        synchronized <T> T get(String key,java.util.function.Supplier<T> load) {
            Object cached=entries.get(key);if(cached!=null) return (T)cached;
            T value=Objects.requireNonNull(load.get());remember(key,value);return value;
        }
        synchronized Object peek(String key) {return entries.get(key);}
        synchronized void remember(String key,Object value) {
            entries.put(key,value);while(entries.size()>128) entries.remove(entries.keySet().iterator().next());
        }
    }
    private static void rememberBook(Source source,HardcoverClient.Match book) {
        if(!book.slug().isBlank()) {source.cache().remember("book:"+book.id(),book);source.cache().remember("slug:"+book.slug(),book);}
    }
    private static void rememberEdition(Source source,long book,HardcoverClient.Edition edition) {
        source.cache().remember("edition:"+book+":"+edition.id(),edition);
        if(source.cache().peek("book:"+book) instanceof HardcoverClient.Match match) source.cache().remember("edition-book:"+edition.id(),new HardcoverClient.EditionBook(match,edition));
    }
    private record Plan(Source source,Instant expires,List<Value> values,Map<String,String> additions,Set<String> replacements) {}
    private final Map<String,Source> sources=new ConcurrentHashMap<>();
    private final Map<String,Plan> plans=new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HardcoverClient hardcover;
    private final CalibreClient calibre;
    private final CalibreSettings settings;
    private final CalibreTransfers transfers;
    private final CalibreCatalogService catalog;
    private final SessionAccess sessions;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreHardcover.class);
    public CalibreHardcover(JdbcTemplate jdbc,PlatformTransactionManager manager,HardcoverClient hardcover,CalibreClient calibre,CalibreSettings settings,CalibreTransfers transfers,CalibreCatalogService catalog,SessionAccess sessions) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.hardcover=hardcover;this.calibre=calibre;this.settings=settings;this.transfers=transfers;this.catalog=catalog;this.sessions=sessions;
    }
    private String owner() {Permission.require(Permission.CALIBRE_READ);Permission.require(Permission.CALIBRE_SEND);return sessions.current().id();}
    public Configuration configuration() {
        String owner=owner();var stored=jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,"hardcover.mapping."+owner);
        Map<Field,String> mappings=stored.isEmpty()?Map.of(Field.SLUG,"hardcover",Field.BOOK_ID,"hardcover_book",Field.EDITION_ID,"hardcover_edition"):json.readValue(stored.getFirst(),new TypeReference<Map<Field,String>>(){});
        return new Configuration(hardcover.configured(),List.of(Field.values()),mappings);
    }
    public Configuration configure(Map<Field,String> input) {
        String owner=owner();var mappings=validate(input);
        tx.executeWithoutResult(s->jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value","hardcover.mapping."+owner,json.writeValueAsString(mappings)));
        return configuration();
    }
    static Map<Field,String> validate(Map<Field,String> input) {
        if(input==null || input.isEmpty() || input.size()>Field.values().length) throw new UserInputException("Selecciona al menos un dato de Hardcover");
        var result=new EnumMap<Field,String>(Field.class);var names=new HashSet<String>();
        for(var entry:input.entrySet()) {
            String name=entry.getValue();
            if(entry.getKey()==null || name==null || !name.matches("[a-z][a-z0-9_-]{0,63}") || Set.of("epl","revision","uuid","calibre").contains(name)) throw new UserInputException("Usa nombres de identificador en minúsculas, sin espacios; EPL y revisión están protegidos");
            if(!names.add(name)) throw new UserInputException("Cada dato debe usar un identificador de Calibre distinto");result.put(entry.getKey(),name);
        }
        return Map.copyOf(result);
    }
    private void prune() {
        var now=Instant.now();sources.entrySet().removeIf(e->e.getValue().expires().isBefore(now));plans.entrySet().removeIf(e->e.getValue().expires().isBefore(now));
        if(sources.size()+plans.size()>=2000) throw new UserInputException("Hay demasiadas comprobaciones abiertas; cierra las anteriores o espera unos minutos");
    }
    private Source source(String token) {
        String owner=owner();var s=sources.get(token==null?"":token);
        if(s==null || !s.owner().equals(owner) || s.expires().isBefore(Instant.now())) throw new UserInputException("La búsqueda ha caducado o pertenece a otro usuario; vuelve a buscar");
        if(!s.connection().equals(settings.connection())) throw new UserInputException("La conexión o biblioteca de Calibre cambió; vuelve a buscar");return s;
    }
    public Matches search(long id,String token,int page) {
        return search(id,token,page,true,true);
    }
    public Matches search(long id,String token,int page,boolean useTitle,boolean useAuthor) {
        return search(id,token,page,useTitle,useAuthor,false,false);
    }
    public Matches search(long id,String token,int page,boolean useTitle,boolean useAuthor,boolean useIsbn,boolean useAsin) {
        return search(id,token,page,useTitle,useAuthor,useIsbn,useAsin,null,null);
    }
    public Matches search(long id,String token,int page,boolean useTitle,boolean useAuthor,boolean useIsbn,boolean useAsin,String queryTitle,String queryAuthor) {
        return search(id,token,page,useTitle,useAuthor,useIsbn,useAsin,queryTitle,queryAuthor,null,null);
    }
    public Matches search(long id,String token,int page,boolean useTitle,boolean useAuthor,boolean useIsbn,boolean useAsin,String queryTitle,String queryAuthor,String queryIsbn,String queryAsin) {
        String owner=owner();
        if(queryIsbn!=null && queryIsbn.length()>1000 || queryAsin!=null && queryAsin.length()>50) throw new UserInputException("Los identificadores de búsqueda son demasiado largos");
        if(queryTitle!=null && queryTitle.length()>500 || queryAuthor!=null && queryAuthor.length()>1000) throw new UserInputException("La consulta personalizada es demasiado larga");
        if(!useTitle && !useAuthor && !useIsbn && !useAsin) throw new UserInputException("Selecciona al menos un campo de búsqueda");
        if(!hardcover.configured()) throw new UserInputException("Configura EPLSYNC_HARDCOVER_API_KEY en el servidor para utilizar Hardcover");
        prune();Source s;
        if(token==null || token.isBlank()) {
            if(page!=1) throw new UserInputException("Empieza la búsqueda por la primera página");
            var c=settings.connection();var book=calibre.identifierMetadata(c,id);
            var notices=new ArrayList<String>();var isbns=new LinkedHashSet<String>();String asin="";
            if(useIsbn) {
                if(queryIsbn!=null) {
                    for(String part:queryIsbn.split("[,;\\r\\n]+")) {
                        String value=part.replaceAll("[-\\s]","").toUpperCase(Locale.ROOT);
                        if(value.isBlank()) continue;
                        if(!validValue(Field.ISBN10,value) && !validValue(Field.ISBN13,value)) throw new UserInputException("El ISBN introducido no es válido; revisa su formato y dígito de control");
                        isbns.add(value);
                    }
                    if(isbns.size()>10) throw new UserInputException("Introduce como máximo 10 ISBN para este libro");
                } else {
                for(String name:List.of("isbn","isbn10","isbn13","isbn_10","isbn_13")) {
                    String value=book.identifiers().getOrDefault(name,"").replaceAll("[-\\s]","").toUpperCase(Locale.ROOT);
                    if(!value.isBlank() && (validValue(Field.ISBN10,value) || validValue(Field.ISBN13,value))) isbns.add(value);
                }
                }
                if(isbns.isEmpty()) notices.add("ISBN_UNAVAILABLE");
            }
            if(useAsin) {
                String value=(queryAsin==null?book.identifiers().getOrDefault("asin",""):queryAsin).strip().toUpperCase(Locale.ROOT);
                if(queryAsin!=null && !value.isBlank() && !validValue(Field.ASIN,value)) throw new UserInputException("El ASIN introducido no es válido; debe contener 10 letras o números");
                if(validValue(Field.ASIN,value)) asin=value;else notices.add("ASIN_UNAVAILABLE");
            }
            String titleQuery=queryTitle==null?book.title():queryTitle.strip();
            List<String> authorQuery=queryAuthor==null?book.authors():(queryAuthor.isBlank()?List.of():List.of(queryAuthor.strip()));
            boolean title=useTitle && titleQuery!=null && !titleQuery.isBlank();
            boolean author=useAuthor && authorQuery!=null && authorQuery.stream().anyMatch(v->!v.isBlank());
            if(useTitle && !title) notices.add("TITLE_UNAVAILABLE");
            if(useAuthor && !author) notices.add("AUTHOR_UNAVAILABLE");
            if(!isbns.isEmpty() || !asin.isBlank()) notices.add("EXACT_IDENTIFIERS");
            else if(!title && !author) notices.add("NO_CRITERIA");
            s=new Source(owner,Instant.now().plusSeconds(900),id,c,book,ConcurrentHashMap.newKeySet(),title,author,List.copyOf(isbns),asin,List.copyOf(notices),titleQuery,authorQuery,new LookupCache(),new java.util.concurrent.atomic.AtomicBoolean());token=UUID.randomUUID().toString();
        } else {s=source(token);if(id!=s.calibreId()) throw new UserInputException("La búsqueda corresponde a otro libro");}
        Source search=s;
        sources.put(token,s);
        HardcoverClient.Search result;
        String error=null;
        try {result=s.cache().get("search:"+page,()->{
            if(!search.isbns().isEmpty() || !search.asin().isBlank()) return hardcover.searchIdentifiers(search.isbns(),search.asin(),page);
            if(!search.useTitle() && !search.useAuthor()) return new HardcoverClient.Search(List.of(),false);
            if(search.fallback().get()) return hardcover.searchFallback(search.queryTitle(),search.queryAuthors(),page,search.useTitle(),search.useAuthor());
            return search.useTitle() && search.useAuthor()?hardcover.search(search.queryTitle(),search.queryAuthors(),page):hardcover.search(search.queryTitle(),search.queryAuthors(),page,search.useTitle(),search.useAuthor());
        });} catch(org.springframework.web.server.ResponseStatusException ex) {
            error=ex.getReason()==null?"No se pudo consultar Hardcover":ex.getReason();
            result=new HardcoverClient.Search(List.of(),false);
        } catch(UserInputException ex) {
            error=ex.getMessage();result=new HardcoverClient.Search(List.of(),false);
        }
        for(var match:result.items()) {s.matches().add(match.id());rememberBook(s,match);}
        var notices=new ArrayList<>(s.notices());if(result.fallback()) {s.fallback().set(true);notices.add("FALLBACK_SEARCH");}
        sources.put(token,s);return new Matches(token,id,s.metadata().title(),s.metadata().authors(),result.items(),result.hasNext(),page,List.copyOf(notices),String.join(", ",s.isbns()),s.asin(),s.metadata().identifiers(),Permission.has(Permission.CALIBRE_FILES_READ)?"/api/calibre/hardcover/cover?token="+token:null,reviewed(s),error);
    }
    public CalibreClient.Cover cover(String token) {
        Permission.require(Permission.CALIBRE_FILES_READ);var s=source(token);
        return s.cache().get("calibre-cover",()->calibre.cover(s.connection(),s.calibreId()));
    }
    public HardcoverClient.Match resolveSlug(String token,String slug) {
        var s=source(token);String value=slug==null?null:slug.strip();
        var book=s.cache().get("slug:"+value,()->hardcover.bookBySlug(value));
        s.matches().add(book.id());rememberBook(s,book);return book;
    }
    public HardcoverClient.Match resolveBook(String token,long id) {
        var s=source(token);var book=s.cache().get("book:"+id,()->hardcover.book(id));
        s.matches().add(book.id());rememberBook(s,book);return book;
    }
    public HardcoverClient.EditionBook resolveEdition(String token,long editionId) {
        var s=source(token);var linked=s.cache().get("edition-book:"+editionId,()->hardcover.editionBook(editionId));
        s.matches().add(linked.book().id());rememberBook(s,linked.book());rememberEdition(s,linked.book().id(),linked.edition());return linked;
    }
    private Source selected(String token,long book) {var s=source(token);if(!s.matches().contains(book)) throw new UserInputException("Selecciona un libro de los resultados de búsqueda");return s;}
    public HardcoverClient.Editions editions(String token,long book,int page) {return editions(token,book,page,"");}
    public HardcoverClient.Editions editions(String token,long book,int page,String language) {
        var s=selected(token,book);
        var result=s.cache().get("editions:"+book+":"+page+":"+language,()->language.isEmpty()?hardcover.editions(book,page):hardcover.editions(book,page,language));
        for(var edition:result.items()) rememberEdition(s,book,edition);return result;
    }
    public List<HardcoverClient.Language> editionLanguages(String token,long book) {
        var s=selected(token,book);return s.cache().get("languages:"+book,()->hardcover.editionLanguages(book));
    }
    public Preview preview(String token,long bookId,Long editionId,Map<Field,String> input) {
        return preview(token,bookId,editionId,input,Set.of());
    }
    public Preview preview(String token,long bookId,Long editionId,Map<Field,String> input,Set<Field> replaceFields) {
        return preview(token,bookId,editionId,input,replaceFields,Set.of());
    }
    public Preview preview(String token,Long bookId,Long editionId,Map<Field,String> input,Set<Field> replaceFields,Set<Field> removeFields) {
        return preview(token,bookId,editionId,input,replaceFields,removeFields,Map.of());
    }
    public Preview preview(String token,Long bookId,Long editionId,Map<Field,String> input,Set<Field> replaceFields,Set<Field> removeFields,Map<Field,String> values) {
        prune();var s=bookId==null?source(token):selected(token,bookId);var mapping=validate(input);
        if(bookId==null && editionId!=null) throw new UserInputException("Selecciona la obra de la edición");
        var book=bookId==null?null:s.cache().get("book:"+bookId,()->hardcover.book(bookId));
        HardcoverClient.Edition edition = null;
        if (bookId != null && editionId != null) {
            long selectedBookId = bookId;
            long selectedEditionId = editionId;
            edition = s.cache().get("edition:" + selectedBookId + ":" + selectedEditionId,
                    () -> hardcover.edition(selectedBookId, selectedEditionId));
        }
        var current=calibre.identifierMetadata(s.connection(),s.calibreId());
        if(!current.equals(s.metadata())) throw new UserInputException("Los metadatos de Calibre cambiaron; vuelve a buscar");
        var replace=replaceFields==null?Set.<Field>of():replaceFields;
        if(replace.stream().anyMatch(Objects::isNull) || !mapping.keySet().containsAll(replace)) throw new UserInputException("Solo puedes sustituir identificadores seleccionados en esta previsualización");
        var remove=removeFields==null?Set.<Field>of():removeFields;
        if(remove.stream().anyMatch(Objects::isNull) || !mapping.keySet().containsAll(remove)) throw new UserInputException("Solo puedes eliminar identificadores seleccionados en esta previsualización");
        var removals=new HashSet<>(remove);
        var overrides=values==null?Map.<Field,String>of():values;
        if(!mapping.keySet().containsAll(overrides.keySet()) || overrides.values().stream().anyMatch(value->value==null || value.length()>2048)) throw new UserInputException("Valores de identificadores inválidos");
        var replacements=new HashSet<String>();
        var rows=new ArrayList<Value>();var additions=new LinkedHashMap<String,String>();
        for(var f:Field.values()) if(mapping.containsKey(f)) {
            String value=switch(f) {case SLUG->book==null?"":book.slug();case BOOK_ID->book==null?"":Long.toString(book.id());case EDITION_ID->edition==null?"":Long.toString(edition.id());case ISBN10->edition==null?"":edition.isbn10();case ISBN13->edition==null?"":edition.isbn13();case ASIN->edition==null?"":edition.asin();};
            value=overrides.getOrDefault(f,value).strip();
            if(removals.contains(f)) value="";
            if(f==Field.ISBN10 || f==Field.ISBN13) value=value.replaceAll("[-\\s]","").toUpperCase(Locale.ROOT);
            if(f==Field.ASIN) value=value.toUpperCase(Locale.ROOT);
            String name=mapping.get(f),existing=current.identifiers().getOrDefault(name,"");
            if(overrides.containsKey(f) && !value.equals(existing) && !value.isBlank() && !validValue(f,value)) throw new UserInputException("Valor inválido para "+name);
            if(overrides.containsKey(f) && !value.isBlank() && book!=null) {
                if(f==Field.SLUG && !value.equals(book.slug()) || f==Field.BOOK_ID && !value.equals(Long.toString(book.id()))) throw new UserInputException("Los identificadores no corresponden a la obra seleccionada");
                if(f==Field.EDITION_ID && (edition==null || !value.equals(Long.toString(edition.id())))) throw new UserInputException("El identificador no corresponde a la edición seleccionada");
            }
            String state=value.equals(existing)?(value.isBlank()?"MISSING":"UNCHANGED"):value.isBlank()?"REMOVE":!validValue(f,value)?"INVALID":!existing.isBlank()?"REPLACE":"READY";
            rows.add(new Value(f,name,existing,value,state));if(state.equals("READY") || state.equals("REPLACE") || state.equals("REMOVE")) additions.put(name,value);
            if(state.equals("REPLACE") || state.equals("REMOVE")) replacements.add(name);
        }
        String confirmation=UUID.randomUUID().toString();plans.put(confirmation,new Plan(s,Instant.now().plusSeconds(600),List.copyOf(rows),Map.copyOf(additions),Set.copyOf(replacements)));
        return new Preview(confirmation,s.calibreId(),s.metadata().title(),List.copyOf(rows),false);
    }
    static boolean validValue(Field field,String value) {
        if(field==Field.BOOK_ID || field==Field.EDITION_ID) return value.matches("[1-9][0-9]{0,9}") && Long.parseLong(value)<=Integer.MAX_VALUE;
        if(field==Field.SLUG) return value.length()<=512 && value.matches("[^\\s/:?#]+");
        if(field==Field.ASIN) return value.matches("[A-Z0-9]{10}");
        if(field==Field.ISBN10) {
            if(!value.matches("[0-9]{9}[0-9X]")) return false;
            int sum=0;for(int n=0;n<10;n++) sum+=(10-n)*(value.charAt(n)=='X'?10:value.charAt(n)-'0');return sum%11==0;
        }
        if(field==Field.ISBN13) {
            if(!value.matches("97[89][0-9]{10}")) return false;
            int sum=0;for(int n=0;n<13;n++) sum+=(n%2==0?1:3)*(value.charAt(n)-'0');return sum%10==0;
        }
        return true;
    }
    public Preview apply(String token) {
        String owner=owner();var p=plans.get(token==null?"":token);
        if(p==null || !p.source().owner().equals(owner) || p.expires().isBefore(Instant.now())) throw new UserInputException("La previsualización ha caducado o pertenece a otro usuario");
        if(p.values().stream().anyMatch(value->value.state().equals("INVALID"))) throw new UserInputException("Corrige los identificadores inválidos antes de guardar");
        return transfers.exclusive(()->catalog.exclusive(()->{
            if(!p.source().connection().equals(settings.connection())) throw new UserInputException("La conexión o biblioteca cambió; vuelve a previsualizar");
            if(p.replacements().isEmpty()) calibre.addIdentifiers(p.source().connection(),p.source().calibreId(),p.source().metadata(),p.additions());
            else calibre.updateIdentifiers(p.source().connection(),p.source().calibreId(),p.source().metadata(),p.additions(),p.replacements());
            var identifiers=new LinkedHashMap<>(p.source().metadata().identifiers());
            p.additions().forEach((key,value)->{if(value.isBlank()) identifiers.remove(key);else identifiers.put(key,value);});
            tx.executeWithoutResult(status->{
                jdbc.update("UPDATE calibre_books SET identifiers=? WHERE active=true AND calibre_id=?",json.writeValueAsString(identifiers),p.source().calibreId());
                if(p.values().stream().anyMatch(value->(value.field()==Field.SLUG || value.field()==Field.BOOK_ID || value.field()==Field.EDITION_ID) && !value.value().isBlank() && Set.of("READY","REPLACE","UNCHANGED").contains(value.state()))) clearReview(p.source());
            });
            log.info("Identificadores de Hardcover comprobados en Calibre: calibreId={}, campos={}, userId={}",p.source().calibreId(),p.additions().size(),owner);
            return new Preview(token,p.source().calibreId(),p.source().metadata().title(),p.values(),true);
        }));
    }
    private boolean reviewed(Source source) {
        return jdbc.queryForObject("SELECT count(*) FROM calibre_hardcover_reviews WHERE destination=? AND library=? AND calibre_id=? AND calibre_uuid=? AND title=? AND authors=?",Long.class,source.connection().url(),source.connection().library(),source.calibreId(),source.metadata().uuid(),source.metadata().title(),String.join(", ",source.metadata().authors()))>0;
    }
    public void review(String token,boolean noMatch) {
        String user=owner();var source=source(token);
        transfers.exclusive(()->catalog.exclusive(()->{
            var current=calibre.identifierMetadata(source.connection(),source.calibreId());
            if(!current.equals(source.metadata())) throw new UserInputException("Los metadatos de Calibre cambiaron; vuelve a buscar");
            if(current.uuid()==null || current.uuid().isBlank()) throw new UserInputException("No se puede identificar el libro de Calibre de forma segura");
            tx.executeWithoutResult(status->{
                if(!source.connection().equals(settings.connection())) throw new UserInputException("La conexión o biblioteca cambió; vuelve a buscar");
                if(noMatch) jdbc.update("INSERT INTO calibre_hardcover_reviews(destination,library,calibre_id,calibre_uuid,title,authors,reviewed_at,reviewed_by) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(destination,library,calibre_id) DO UPDATE SET calibre_uuid=excluded.calibre_uuid,title=excluded.title,authors=excluded.authors,reviewed_at=excluded.reviewed_at,reviewed_by=excluded.reviewed_by",source.connection().url(),source.connection().library(),source.calibreId(),current.uuid(),current.title(),String.join(", ",current.authors()),Instant.now().toString(),user);
                else clearReview(source);
                jdbc.update("UPDATE calibre_books SET calibre_uuid=? WHERE active=true AND calibre_id=? AND title=? AND authors=?",current.uuid(),source.calibreId(),current.title(),String.join(", ",current.authors()));
            });
            log.info("Revisión de Hardcover: calibreId={}, sinCorrespondencia={}, userId={}",source.calibreId(),noMatch,user);
            return null;
        }));
    }
    private void clearReview(Source source) {
        jdbc.update("DELETE FROM calibre_hardcover_reviews WHERE destination=? AND library=? AND calibre_id=?",source.connection().url(),source.connection().library(),source.calibreId());
    }
    public void discard(String token) {
        String owner=owner();var s=sources.get(token==null?"":token);if(s!=null && s.owner().equals(owner)) {
            sources.remove(token,s);plans.entrySet().removeIf(e->e.getValue().source()==s);
        }
    }
}
