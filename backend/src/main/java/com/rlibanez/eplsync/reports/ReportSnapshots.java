package com.rlibanez.eplsync.reports;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Temporary, immutable, owner-scoped reports. Separate SQLite files do not occupy the application pool. */
@Service
public class ReportSnapshots implements AutoCloseable {
    public static final int MAX_PAGE_SIZE = 1000;
    private static final long TTL_SECONDS = 1800;
    private final tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final java.util.concurrent.Semaphore queries = new java.util.concurrent.Semaphore(1);
    private final LinkedHashMap<String, Writer> reports = new LinkedHashMap<>();
    public synchronized Writer create() {
        expire();
        while (reports.size() >= 4) {
            var oldest = reports.values().stream().filter(w -> w.ready).findFirst();
            if (oldest.isEmpty()) throw new TorrentOperationException(HttpStatus.CONFLICT, "Hay demasiados informes en preparación");
            discard(oldest.get());
        }
        try {
            var writer = new Writer(); reports.put(writer.id, writer); return writer;
        } catch (Exception ex) { throw failure(ex); }
    }
    private String owner() { var actor = EventContext.actor(); return actor.kind()+":"+actor.id()+":"+actor.username(); }
    private void expire() {
        for (var report : List.copyOf(reports.values()))
            if (report.ready && report.completed.isBefore(Instant.now().minusSeconds(TTL_SECONDS))) discard(report);
    }
    private void discard(Writer writer) {
        reports.remove(writer.id);
        writer.dispose();
    }
    private TorrentOperationException failure(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("No se pudo almacenar o consultar el informe temporal", ex);
        return new StorageException();
    }
    public static class StorageException extends TorrentOperationException {
        public StorageException() {super(HttpStatus.INSUFFICIENT_STORAGE,"No se pudo almacenar o consultar el informe temporal (límite: 128 MiB); vuelve a ejecutar la operación");}
    }
    public final class Writer implements AutoCloseable {
        public final String id = UUID.randomUUID().toString();
        private Instant completed;
        private final String owner = owner();
        private final Path path;
        private Connection connection;
        private PreparedStatement insert;
        private volatile boolean ready;
        private Writer() throws Exception {
            path = Files.createTempFile("eplsync-report-", ".sqlite");
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:"+path);
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA page_size=4096"); statement.execute("PRAGMA journal_mode=OFF"); statement.execute("PRAGMA temp_store=FILE");
                    statement.execute("PRAGMA cache_size=-1024"); statement.execute("PRAGMA max_page_count=32768");
                    statement.execute("CREATE TABLE details (seq INTEGER PRIMARY KEY, section TEXT NOT NULL, payload TEXT NOT NULL)");
                    statement.execute("CREATE TABLE url_cache (url TEXT PRIMARY KEY, payload TEXT NOT NULL)");
                }
                connection.setAutoCommit(false);
                insert = connection.prepareStatement("INSERT INTO details(section,payload) VALUES(?,?)");
            } catch (Exception ex) { dispose(); throw ex; }
        }
        public void add(String section, Object value) {
            try {
                String json = mapper.writeValueAsString(value);
                if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException("Report entry exceeds 64 KiB");
                insert.setString(1,section); insert.setString(2,json); insert.executeUpdate();
            } catch (Exception ex) { throw failure(ex); }
        }
        public record Entry<T>(long sequence, T value) {}
        public <T> List<Entry<T>> batch(long after, int size, Class<T> type) {
            if (size < 1 || size > 500) throw new IllegalArgumentException("Invalid report batch size");
            try (var statement=connection.prepareStatement("SELECT seq,payload FROM details WHERE seq>? ORDER BY seq LIMIT ?")) {
                statement.setLong(1,after);statement.setInt(2,size);
                var result=new ArrayList<Entry<T>>();
                try(var rows=statement.executeQuery()) {
                    while(rows.next()) result.add(new Entry<>(rows.getLong(1),mapper.readValue(rows.getString(2),type)));
                }
                return result;
            } catch(Exception ex) {throw failure(ex);}
        }
        public void update(long sequence,Object value) {
            try(var statement=connection.prepareStatement("UPDATE details SET payload=? WHERE seq=?")) {
                String json=mapper.writeValueAsString(value);
                if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw new IllegalArgumentException("Report entry exceeds 64 KiB");
                statement.setString(1,json);statement.setLong(2,sequence);statement.executeUpdate();
            } catch(Exception ex) {throw failure(ex);}
        }
        public <T> T cached(String url,Class<T> type) {
            try(var statement=connection.prepareStatement("SELECT payload FROM url_cache WHERE url=?")) {
                statement.setString(1,url);
                try(var rows=statement.executeQuery()) {return rows.next()?mapper.readValue(rows.getString(1),type):null;}
            } catch(Exception ex) {throw failure(ex);}
        }
        public void cache(String url,Object value) {
            try(var statement=connection.prepareStatement("INSERT INTO url_cache(url,payload) VALUES(?,?)")) {
                if(url.length()>16384) throw new IllegalArgumentException("Cover URL exceeds 16 KiB");
                statement.setString(1,url);statement.setString(2,mapper.writeValueAsString(value));statement.executeUpdate();
            } catch(Exception ex) {throw failure(ex);}
        }
        public void abort() {synchronized(ReportSnapshots.this) {discard(this);}}
        public void finish() {
            try { connection.commit(); insert.close(); connection.close(); connection=null; completed=Instant.now(); ready=true; }
            catch (Exception ex) { throw failure(ex); }
        }
        private void dispose() {
            try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
            try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) {}
        }
        @Override public void close() { synchronized (ReportSnapshots.this) { if (!ready) discard(this); } }
    }
    public record Filter(String field, String operator, Object value) {}
    /** Every field and operator is validated before use; values are always bound parameters. */
    public <T> PageResponse<T> page(String id, String section, int page, int size,
            List<String> sorts, Set<String> fields, List<Filter> filters, Class<T> type) {
        if (!queries.tryAcquire()) throw new TorrentOperationException(HttpStatus.TOO_MANY_REQUESTS,"Hay otra consulta de informes en curso; vuelve a intentarlo");
        try { synchronized(this) { return readPage(id,section,page,size,sorts,fields,filters,type); } }
        finally {queries.release();}
    }
    private <T> PageResponse<T> readPage(String id,String section,int page,int size,List<String> sorts,
            Set<String> fields,List<Filter> filters,Class<T> type) {
        if (page < 0 || page > 1000000 || size < 1 || size > MAX_PAGE_SIZE || sorts.size() > 8 || filters.size() > 8)
            throw new com.rlibanez.eplsync.exception.UserInputException("Página inválida; size debe estar entre 1 y 1000");
        expire(); var report = reports.get(id);
        if (report == null || !report.ready || !report.owner.equals(owner()))
            throw new TorrentOperationException(HttpStatus.GONE, "El informe ha caducado o no está disponible; vuelve a ejecutar la operación");
        var where = new StringBuilder("section=?"); var args = new ArrayList<Object>(); args.add(section);
        for (var filter : filters) {
            var expression = expression(filter.field(),fields);
            if (!Set.of("=", "<>", "LIKE").contains(filter.operator())) throw new IllegalArgumentException("Invalid report operator");
            where.append(" AND ").append(expression).append(' ').append(filter.operator()).append(" ?"); args.add(filter.value());
        }
        var order = new ArrayList<String>();
        for (var sort : sorts) {
            String[] parts=sort.split(",");
            if(parts.length!=2 || !Set.of("asc","desc").contains(parts[1])) throw new com.rlibanez.eplsync.exception.UserInputException("Ordenación inválida");
            order.add(expression(parts[0],fields)+(Set.of("title","name").contains(parts[0])?" COLLATE EPL_TEXT ":" ")+parts[1]);
        }
        order.add("seq ASC");
        try (var connection=DriverManager.getConnection("jdbc:sqlite:"+report.path)) {
            com.rlibanez.eplsync.ordering.TextOrdering.register(connection);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            org.sqlite.ProgressHandler.setHandler(connection,1000,new org.sqlite.ProgressHandler() {
                @Override protected int progress() {return Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline?1:0;}
            });
            try (var settings=connection.createStatement()) {
                settings.execute("PRAGMA query_only=ON"); settings.execute("PRAGMA cache_size=-1024"); settings.execute("PRAGMA temp_store=FILE");
            }
            long total;
            try (var count=connection.prepareStatement("SELECT count(*) FROM details WHERE "+where)) {
                count.setQueryTimeout(10); bind(count,args); try(var rows=count.executeQuery()) {rows.next();total=rows.getLong(1);}
            }
            var items=new ArrayList<T>();
            try(var select=connection.prepareStatement("SELECT payload FROM details WHERE "+where+" ORDER BY "+String.join(",",order)+" LIMIT ? OFFSET ?")) {
                select.setQueryTimeout(10); bind(select,args); select.setInt(args.size()+1,size);select.setLong(args.size()+2,(long)page*size);
                try(var rows=select.executeQuery()) {while(rows.next()) items.add(mapper.readValue(rows.getString(1),type));}
            }
            int pages=(int)((total+size-1)/size);
            return new PageResponse<>(items,new PageResponse.PageMeta(page,size,total,pages,page==0,page+1>=pages,page+1<pages,page>0));
        } catch (Exception ex) {throw failure(ex);}
    }
    private String expression(String field, Set<String> fields) {
        if(!fields.contains(field) || !field.matches("[A-Za-z][A-Za-z0-9]*")) throw new com.rlibanez.eplsync.exception.UserInputException("Campo de informe inválido");
        if(field.equals("search")) return "(coalesce(json_extract(payload,'$.title'),'') || ' ' || coalesce(json_extract(payload,'$.name'),'') || ' ' || coalesce(json_extract(payload,'$.eplId'),'') || ' ' || coalesce(json_extract(payload,'$.hash'),''))";
        return "json_extract(payload,'$."+field+"')";
    }
    private void bind(PreparedStatement statement,List<Object> args) throws SQLException {
        for(int i=0;i<args.size();i++) statement.setObject(i+1,args.get(i));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public synchronized void removeExpired() {expire();}
    @jakarta.annotation.PreDestroy public synchronized void close() { for(var report:List.copyOf(reports.values())) discard(report); }
}
