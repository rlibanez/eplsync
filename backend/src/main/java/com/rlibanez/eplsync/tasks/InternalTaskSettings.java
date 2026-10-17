package com.rlibanez.eplsync.tasks;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.rlibanez.eplsync.exception.UserInputException;

/** Live periods for required job checks. These are not user-created cron tasks. */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class InternalTaskSettings {
    public static final String CALIBRE = "calibre-download-check", CLEANUP = "torrent-cleanup";
    private static final String PREFIX = "tasks.internal.";
    public record Period(String id, String interval) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private volatile Map<String,Duration> periods;
    private final Map<String,Duration> installation;
    public InternalTaskSettings(JdbcTemplate jdbc, PlatformTransactionManager manager,
        @Value("${eplsync.calibre.transfers.download-check-interval:30s}") String calibre,
        @Value("${eplsync.torrent.cleanup.interval:30s}") String cleanup) {
        this.jdbc=jdbc; tx=new TransactionTemplate(manager);
        var values=new HashMap<String,Duration>();
        values.put(CALIBRE,parse(calibre));values.put(CLEANUP,parse(cleanup));
        installation=Map.copyOf(values);
        jdbc.query("SELECT setting_key,setting_value FROM app_settings WHERE setting_key IN (?,?)", (org.springframework.jdbc.core.RowCallbackHandler) r -> {
            values.put(r.getString(1).substring(PREFIX.length()),parse(r.getString(2)));
        },PREFIX+CALIBRE,PREFIX+CLEANUP);
        periods=Map.copyOf(values);
    }
    public static Duration parse(String text) {
        try {
            var value=org.springframework.boot.convert.DurationStyle.detectAndParse(text.strip(), java.time.temporal.ChronoUnit.SECONDS);
            if(value.compareTo(Duration.ofSeconds(10))<0 || value.compareTo(Duration.ofDays(365))>0) throw new IllegalArgumentException();
            return value;
        } catch(RuntimeException ex) {throw new UserInputException("Introduce un intervalo entre 10s y 365 días, por ejemplo 30s o 1m");}
    }
    public Duration interval(String id) {
        var value=periods.get(id);if(value==null) throw new UserInputException("Proceso interno desconocido");return value;
    }
    public synchronized void resetAfterCommit() {periods=installation;}
    public List<Period> view() {return List.of(new Period(CLEANUP,format(interval(CLEANUP))),new Period(CALIBRE,format(interval(CALIBRE))));}
    private static String format(Duration value) {return value.toMillis()%1000==0 ? value.toSeconds()+"s" : value.toMillis()+"ms";}
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.config.SqliteWriteCoordinator writer;
    private <T> T coordinated(java.util.function.Supplier<T> work) {return writer==null?work.get():writer.write(work);}
    public Period restore(String id) {return coordinated(()->restoreLocked(id));}
    private synchronized Period restoreLocked(String id) {
        interval(id);
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM app_settings WHERE setting_key=?",PREFIX+id));
        var copy=new HashMap<>(periods);copy.put(id,installation.get(id));periods=Map.copyOf(copy);
        return new Period(id,format(interval(id)));
    }
    public Period save(String id,String text) {return coordinated(()->saveLocked(id,text));}
    private synchronized Period saveLocked(String id,String text) {
        interval(id); var value=parse(text);var stored=format(value);
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",PREFIX+id,stored));
        var copy=new HashMap<>(periods);copy.put(id,value);periods=Map.copyOf(copy);
        return new Period(id,stored);
    }
}
