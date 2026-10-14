package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.dto.CatalogBookResponse;
import com.rlibanez.eplsync.exception.UserInputException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Bounded page-by-page synchronization; readers never see an incomplete library. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class CalibreCatalogService {
    static final String KEY = "calibre.snapshot";
    private static final int MAX_BOOKS = 500_000;
    private final JdbcTemplate jdbc;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final TransactionTemplate tx;
    private final Semaphore gate = new Semaphore(1);
    private final JsonMapper json = JsonMapper.builder().build();
    public record Snapshot(String library, Instant syncedAt, long total, long identified,Boolean complete) {
        public Snapshot(String library,Instant syncedAt,long total,long identified) {this(library,syncedAt,total,identified,true);}
    }
    public void observe(CalibreSettings.Connection connection,CalibreClient.Book book) {
        if(!gate.tryAcquire()) throw new UserInputException("La biblioteca se está sincronizando; reanuda el trabajo para verificar el envío");
        try {
            tx.executeWithoutResult(s -> {
                var current=settings.connection();
                if(!connection.equals(current)) return; // A job may explicitly target another accessible library.
                String authors=String.join(", ",book.authors());
                if(book.title().length()>4096 || authors.length()>16384) throw new UserInputException("Los metadatos de Calibre superan el tamaño permitido");
                var previous=snapshot();
                var generations=jdbc.queryForList("SELECT generation FROM calibre_books WHERE active=true LIMIT 1",String.class);
                String generation=generations.isEmpty()?UUID.randomUUID().toString():generations.getFirst();
                jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?,?,true,?,?,?,?,?) ON CONFLICT(generation,calibre_id) DO UPDATE SET epl_id=excluded.epl_id,revision=excluded.revision,title=excluded.title,authors=excluded.authors",
                    generation+":"+book.calibreId(),generation,book.calibreId(),book.eplId(),book.revision()==null?null:book.revision().doubleValue(),book.title(),authors);
                long total=jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Long.class);
                long identified=jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true AND epl_id IS NOT NULL",Long.class);
                var value=new Snapshot(connection.library(),previous==null?Instant.now():previous.syncedAt(),total,identified,previous!=null && !Boolean.FALSE.equals(previous.complete()));
                jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",KEY,json.writeValueAsString(value));
            });
        } finally {gate.release();}
    }
    void removeObserved(CalibreSettings.Connection connection,long id) {
        if(!gate.tryAcquire()) throw new UserInputException("La biblioteca se está sincronizando; reanuda para actualizar la copia local");
        try {tx.executeWithoutResult(s -> {
            if(!connection.equals(settings.connection())) return;
            jdbc.update("DELETE FROM calibre_books WHERE active=true AND calibre_id=?",id);
            var previous=snapshot();
            if(previous!=null) {
                long total=jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Long.class);
                long identified=jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true AND epl_id IS NOT NULL",Long.class);
                var value=new Snapshot(previous.library(),previous.syncedAt(),total,identified,previous.complete());
                jdbc.update("UPDATE app_settings SET setting_value=? WHERE setting_key=?",json.writeValueAsString(value),KEY);
            }
        });} finally {gate.release();}
    }
    public CalibreCatalogService(JdbcTemplate jdbc, CalibreSettings settings, CalibreClient client, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.settings=settings; this.client=client; this.tx=new TransactionTemplate(manager);
        // Interrupted staging generations have no public references.
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM calibre_books WHERE active=false"));
    }
    public Snapshot snapshot() {
        var rows=jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?",String.class,KEY);
        return rows.isEmpty() ? null : json.readValue(rows.getFirst(),Snapshot.class);
    }
    public <T> T exclusive(java.util.function.Supplier<T> action) {
        if (!gate.tryAcquire()) throw new UserInputException("Hay una sincronización de Calibre en curso; espera antes de reiniciar la base de datos");
        try { return action.get(); } finally { gate.release(); }
    }
    public Snapshot sync() {
        if (!gate.tryAcquire()) throw new UserInputException("Ya hay una sincronización de Calibre en curso");
        String generation=UUID.randomUUID().toString();
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(10).toNanos();
        try {
            var connection=settings.connection();
            long total=-1, processed=0, identified=0;
            for(int page=0; total < 0 || processed < total; page++) {
                checkDeadline(deadline);
                var result=client.books(connection,page,100);
                checkDeadline(deadline);
                if(result.total()>MAX_BOOKS) throw new UserInputException("La biblioteca de Calibre supera el límite de 500000 libros");
                if(total>=0 && total!=result.total()) throw new UserInputException("La biblioteca de Calibre cambió durante la sincronización; vuelve a intentarlo");
                total=result.total();
                if(result.items().size()!=Math.min(100,total-processed))
                    throw new UserInputException("Calibre devolvió una página incompleta; vuelve a sincronizar la biblioteca");
                for(var book:result.items()) if(book.eplId()!=null) identified++;
                tx.executeWithoutResult(s -> {
                    checkDeadline(deadline);
                    var counts=jdbc.batchUpdate("INSERT OR IGNORE INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?,?,false,?,?,?,?,?)",
                        result.items(),100,(statement,book) -> {
                            statement.setString(1,generation+":"+book.calibreId());
                            statement.setString(2,generation);
                            statement.setLong(3,book.calibreId());
                            statement.setObject(4,book.eplId());
                            statement.setObject(5,book.revision()==null ? null : book.revision().doubleValue());
                            if(book.title().length()>4096 || String.join(", ",book.authors()).length()>16384) throw new UserInputException("Los metadatos de Calibre superan el tamaño permitido");
                            statement.setString(6,book.title());
                            statement.setString(7,String.join(", ",book.authors()));
                        });
                    for(var batch:counts) for(int count:batch) if(count==0)
                        throw new UserInputException("Calibre devolvió identificadores duplicados; vuelve a sincronizar la biblioteca");
                });
                processed+=result.items().size();
            }
            var snapshot=new Snapshot(connection.library(),Instant.now(),total,identified);
            tx.executeWithoutResult(s -> {
                // Acquire the database write gate before the settings monitor, as settings.save does.
                if(!connection.equals(settings.connection())) throw new UserInputException("La conexión de Calibre cambió; vuelve a sincronizar la biblioteca");
                checkDeadline(deadline);
                jdbc.update("DELETE FROM calibre_books WHERE active=true");
                jdbc.update("UPDATE calibre_books SET active=true WHERE generation=?",generation);
                jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",KEY,json.writeValueAsString(snapshot));
            });
            return snapshot;
        } finally {
            try { tx.executeWithoutResult(s -> jdbc.update("DELETE FROM calibre_books WHERE generation=? AND active=false",generation)); }
            finally { gate.release(); }
        }
    }
    private static void checkDeadline(long deadline) {
        if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)
            throw new UserInputException("La sincronización de Calibre se ha interrumpido o ha superado el límite de 10 minutos");
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public List<CatalogBookResponse> enrich(List<CatalogBookResponse> books) {
        var snapshot=snapshot();
        if(snapshot==null || books.isEmpty()) return books;
        var grouped=new HashMap<Long,List<CatalogBookResponse.CalibreItem>>();
        var totals=new HashMap<Long,Long>();
        var revisions=new HashMap<Long,Double>();
        var ids=books.stream().map((CatalogBookResponse entryValue) -> java.util.Objects.requireNonNull(entryValue).eplId()).distinct().toList();
        for(int start=0;start<ids.size();start+=500) {
            var batch=ids.subList(start,Math.min(start+500,ids.size()));
            String placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
            jdbc.query("SELECT * FROM (SELECT epl_id,calibre_id,revision,COUNT(*) OVER(PARTITION BY epl_id) AS total,MAX(revision) OVER(PARTITION BY epl_id) AS latest,ROW_NUMBER() OVER(PARTITION BY epl_id ORDER BY revision DESC,calibre_id) AS rn FROM calibre_books WHERE active=true AND epl_id IN ("+placeholders+")) WHERE rn<=20",
                row -> {
                    long id=row.getLong("epl_id");
                    grouped.computeIfAbsent(id,k -> new ArrayList<>()).add(new CatalogBookResponse.CalibreItem(row.getLong("calibre_id"),row.getObject("revision")==null ? null : ((Number)row.getObject("revision")).doubleValue()));
                    totals.put(id,row.getLong("total"));
                    if(row.getObject("latest")!=null) revisions.put(id,((Number)row.getObject("latest")).doubleValue());
                },batch.toArray());
        }
        return books.stream().map(book -> book.withCalibre(new CatalogBookResponse.Calibre(
            snapshot.library(),snapshot.syncedAt(),totals.getOrDefault(book.eplId(),0L),revisions.get(book.eplId()),grouped.getOrDefault(book.eplId(),List.of())))).toList();
    }
}
