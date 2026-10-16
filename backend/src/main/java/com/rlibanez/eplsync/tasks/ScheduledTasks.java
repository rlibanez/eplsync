package com.rlibanez.eplsync.tasks;

import java.time.*;
import java.util.*;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class ScheduledTasks {
    public enum Operation { TORRENT_SYNC, CALIBRE_SYNC, CATALOG_IMPORT, TORRENT_UPDATE_SEARCH, CALIBRE_UPDATE_SEARCH, TORRENT_AUTO_UPDATE, CALIBRE_AUTO_UPDATE;
        public String pairedOperation() {
            return switch(this) {
                case TORRENT_SYNC -> TORRENT_UPDATE_SEARCH.name(); case TORRENT_UPDATE_SEARCH -> TORRENT_SYNC.name();
                case CALIBRE_SYNC -> CALIBRE_UPDATE_SEARCH.name(); case CALIBRE_UPDATE_SEARCH -> CALIBRE_SYNC.name();
                default -> name();
            };
        }
        public Set<Permission> permissions() {
            return switch(this) {
                case TORRENT_AUTO_UPDATE -> Set.of(Permission.TORRENT_SYNC,Permission.TORRENT_SEND,Permission.CATALOG_READ,Permission.TORRENT_JOBS_MANAGE);
                case CALIBRE_AUTO_UPDATE -> Set.of(Permission.TORRENT_SYNC,Permission.TORRENT_SEND,Permission.TORRENT_JOBS_MANAGE,Permission.CALIBRE_SYNC,Permission.CALIBRE_READ,Permission.CALIBRE_SEND,Permission.CALIBRE_JOBS_MANAGE,Permission.DOWNLOADS_FILES_READ,Permission.CATALOG_READ);
                case TORRENT_UPDATE_SEARCH -> Set.of(Permission.TORRENT_SYNC,Permission.CATALOG_READ);
                case CALIBRE_UPDATE_SEARCH -> Set.of(Permission.CALIBRE_SYNC,Permission.CALIBRE_READ,Permission.CATALOG_READ);
                default -> Set.of(permission());
            };
        }
        public Permission permission() {return switch(this) {case TORRENT_SYNC,TORRENT_AUTO_UPDATE -> Permission.TORRENT_SYNC; case CALIBRE_SYNC,CALIBRE_AUTO_UPDATE -> Permission.CALIBRE_SYNC; case CATALOG_IMPORT -> Permission.CATALOG_IMPORT; case TORRENT_UPDATE_SEARCH -> Permission.TORRENT_SYNC; case CALIBRE_UPDATE_SEARCH -> Permission.CALIBRE_SYNC;};}
    }
    public record Input(String name,Operation operation,String cron,String ownerId,boolean enabled,Boolean telegramNotifications) {
        public Input(String name,Operation operation,String cron,String ownerId,boolean enabled) {this(name,operation,cron,ownerId,enabled,true);}
    }
    public record Task(String id,String name,Operation operation,String cron,String timezone,String ownerId,String ownerUsername,
        boolean enabled,boolean running,Instant nextRun,Instant lastRun,Instant lastFinished,String lastResult,String lastMessage,boolean telegramNotifications) {}
    private final String timezone;
    public String timezone() {return timezone;}
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final TransactionTemplate tx;
    private final java.util.concurrent.locks.ReentrantReadWriteLock lifecycle=new java.util.concurrent.locks.ReentrantReadWriteLock();
    private static final String SELECT="SELECT t.*,coalesce(u.username,t.owner_username) AS display_username FROM scheduled_tasks t LEFT JOIN users u ON u.id=t.owner_id";
    public ScheduledTasks(JdbcTemplate jdbc,AccountStore accounts,PlatformTransactionManager manager, @org.springframework.beans.factory.annotation.Value("${TZ:UTC}") String timezone) {this.timezone=ZoneId.of(timezone.isBlank()?"UTC":timezone.strip()).getId();this.jdbc=jdbc;this.accounts=accounts;tx=new TransactionTemplate(manager);}
    public List<Task> list() {return jdbc.query(SELECT+" ORDER BY t.created_at,t.id",ScheduledTasks::map);}
    public Task find(String id) {return jdbc.query(SELECT+" WHERE t.id=?",ScheduledTasks::map,id).stream().findFirst().orElseThrow(()->new UserInputException("La tarea ya no existe"));}
    private static Instant instant(java.sql.ResultSet row,String name) throws java.sql.SQLException {long value=row.getLong(name);return row.wasNull()?null:Instant.ofEpochMilli(value);}
    private static Task map(java.sql.ResultSet r,int ignored) throws java.sql.SQLException {return new Task(r.getString("id"),r.getString("name"),Operation.valueOf(r.getString("operation")),r.getString("cron"),r.getString("timezone"),r.getString("owner_id"),r.getString("display_username"),r.getBoolean("enabled"),r.getBoolean("running"),instant(r,"next_run"),instant(r,"last_run"),instant(r,"last_finished"),r.getString("last_result"),r.getString("last_message"),r.getBoolean("telegram_notifications"));}
    public Account owner(String id,Operation operation) {
        var account=id==null?null:accounts.find(id);
        if(account==null || !account.status().equals("ACTIVE") || account.mustChangePassword() || !account.permissions().containsAll(operation.permissions()))
            throw new UserInputException("El usuario asociado no está disponible o no tiene permiso para esta operación");
        return account;
    }
    private static CronExpression expression(String cron) {
        if(cron==null || cron.length()>256) throw new IllegalArgumentException();
        var value=cron.strip();
        if(value.split("\\s+").length==5) value="0 "+value;
        return CronExpression.parse(value);
    }
    public static Instant next(String cron,String timezone,Instant after) {
        try {
            var value=expression(cron).next(after.atZone(ZoneId.of(timezone)));
            if(value==null) throw new IllegalArgumentException();
            return value.toInstant();
        } catch(RuntimeException ex) {throw new UserInputException("Cron o zona horaria inválidos; utiliza cinco campos o seis incluyendo los segundos");}
    }
    public record CronPreview(String expression,String timezone,List<Instant> executions) {}
    public CronPreview preview(String cron,Instant after) {
        // Uses the same parser and zone as task execution, including daylight-saving transitions.
        var dates=new ArrayList<Instant>();
        var cursor=after;
        for(int i=0;i<5;i++) {cursor=next(cron,timezone,cursor);dates.add(cursor);}
        return new CronPreview(expression(cron).toString(),timezone,List.copyOf(dates));
    }
    public Task save(String id,Input input) {
        if(input.name()==null || input.name().isBlank() || input.name().strip().length()>120 || input.operation()==null) throw new UserInputException("Introduce un nombre de hasta 120 caracteres y una operación válida");
        var owner=owner(id==null?input.ownerId():find(id).ownerId(),input.operation());var now=Instant.now();var next=next(input.cron(),timezone,now);
        return Objects.requireNonNull(tx.execute(s -> {
            String target=id==null?UUID.randomUUID().toString():id;
            if((input.operation()==Operation.TORRENT_AUTO_UPDATE || input.operation()==Operation.CALIBRE_AUTO_UPDATE)
                && jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE operation=? AND id<>?",Long.class,input.operation().name(),target)>0)
                throw new UserInputException("Ya existe una tarea de este tipo de actualización automática, aunque esté desactivada; edita o elimina la existente");
            if(id==null) {
                if(jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks",Long.class)>=200) throw new UserInputException("Se permiten hasta 200 tareas programadas");
                jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,next_run,created_at,updated_at,telegram_notifications) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",target,input.name().strip(),input.operation().name(),input.cron().strip(),timezone,owner.id(),owner.username(),input.enabled(),input.enabled()?next.toEpochMilli():null,now.toEpochMilli(),now.toEpochMilli(),!Boolean.FALSE.equals(input.telegramNotifications()));
            } else if(jdbc.update("UPDATE scheduled_tasks SET name=?,operation=?,cron=?,timezone=?,owner_id=?,owner_username=?,enabled=?,next_run=?,updated_at=?,telegram_notifications=? WHERE id=? AND running=0",input.name().strip(),input.operation().name(),input.cron().strip(),timezone,owner.id(),owner.username(),input.enabled(),input.enabled()?next.toEpochMilli():null,now.toEpochMilli(),!Boolean.FALSE.equals(input.telegramNotifications()),id)!=1) throw new UserInputException("La tarea está ejecutándose o ya no existe; espera antes de editarla");
            return find(target);
        }));
    }
    public void delete(String id) {
        tx.executeWithoutResult(status -> {
            find(id);
            // One atomic condition shares the scheduler's claim boundary. Cascades remove only task metadata.
            if(jdbc.update("DELETE FROM scheduled_tasks WHERE id=? AND running=0 AND NOT EXISTS (SELECT 1 FROM scheduled_automation_runs r WHERE r.task_id=scheduled_tasks.id AND r.state IN ('WAITING','ATTENTION'))",id)!=1)
                throw new UserInputException("La tarea está ejecutándose o tiene un ciclo pendiente; completa o cancela sus trabajos y cierra el ciclo antes de eliminarla");
        });
    }
    public Task notifications(String id,boolean enabled) {
        if(jdbc.update("UPDATE scheduled_tasks SET telegram_notifications=?,updated_at=? WHERE id=?",enabled,Instant.now().toEpochMilli(),id)!=1)
            throw new UserInputException("La tarea ya no existe");
        return find(id);
    }
    public Task enable(String id,boolean enabled) {
        var value=find(id);if(enabled) owner(value.ownerId(),value.operation());
        jdbc.update("UPDATE scheduled_tasks SET enabled=?,next_run=?,updated_at=? WHERE id=?",enabled,enabled?next(value.cron(),timezone,Instant.now()).toEpochMilli():null,Instant.now().toEpochMilli(),id);
        return find(id);
    }
    /** Runs once after restart, records interrupted work, and never replays missed schedules. */
    public void recover(Instant now) {
        var interrupted=tx.execute(s -> {
            int count=jdbc.update("UPDATE scheduled_tasks SET last_result='INTERRUPTED',last_message='La aplicación se reinició durante la ejecución',last_finished=? WHERE running=1",now.toEpochMilli());
            for(var task:list()) jdbc.update("UPDATE scheduled_tasks SET running=0,next_run=?,timezone=? WHERE id=?",task.enabled()?next(task.cron(),timezone,now).toEpochMilli():null,timezone,task.id());
            return count;
        });
        if(interrupted!=null && interrupted>0) org.slf4j.LoggerFactory.getLogger(ScheduledTasks.class).info("Recuperación de tareas programadas interrumpidas: total={}",interrupted);
    }
    /** SQL claim also prevents two tasks of the same operation from overlapping. */
    public Optional<Task> claim(Instant now) {return withExecution(()->claimDue(now));}
    private Optional<Task> claimDue(Instant now) {
        for(var task:jdbc.query(SELECT+" WHERE t.enabled=1 AND t.running=0 AND t.next_run<=? ORDER BY t.next_run,t.id LIMIT 200",ScheduledTasks::map,now.toEpochMilli())) {
            var following=next(task.cron(),timezone,now);
            if(jdbc.update("UPDATE scheduled_tasks SET running=1,last_run=?,last_finished=NULL,last_result='RUNNING',last_message=NULL,next_run=? WHERE id=? AND enabled=1 AND running=0 AND next_run<=? AND NOT EXISTS (SELECT 1 FROM scheduled_tasks other WHERE other.running=1 AND (other.operation IN (?,?) OR other.operation IN ('TORRENT_AUTO_UPDATE','CALIBRE_AUTO_UPDATE') OR ? IN ('TORRENT_AUTO_UPDATE','CALIBRE_AUTO_UPDATE')))",now.toEpochMilli(),following.toEpochMilli(),task.id(),now.toEpochMilli(),task.operation().name(),task.operation().pairedOperation(),task.operation().name())==1) return Optional.of(find(task.id()));
        }
        return Optional.empty();
    }
    /** Claims an explicit execution without changing the regular cron schedule, even when disabled. */
    public Task claimNow(String id,Instant now) {
        return withExecution(()->{
            var task=find(id);
            task.operation().permissions().forEach(Permission::require);
            owner(task.ownerId(),task.operation());
            if(jdbc.update("UPDATE scheduled_tasks SET running=1,last_run=?,last_finished=NULL,last_result='RUNNING',last_message=NULL WHERE id=? AND running=0 AND NOT EXISTS (SELECT 1 FROM scheduled_tasks other WHERE other.running=1 AND (other.operation IN (?,?) OR other.operation IN ('TORRENT_AUTO_UPDATE','CALIBRE_AUTO_UPDATE') OR ? IN ('TORRENT_AUTO_UPDATE','CALIBRE_AUTO_UPDATE')))",now.toEpochMilli(),id,task.operation().name(),task.operation().pairedOperation(),task.operation().name())!=1)
                throw new UserInputException("La tarea u otra operación incompatible ya está ejecutándose");
            return find(id);
        });
    }
    public <T> T withExecution(java.util.function.Supplier<T> action) {
        lifecycle.readLock().lock();try {return action.get();} finally {lifecycle.readLock().unlock();}
    }
    public <T> T exclusive(java.util.function.Supplier<T> action) {
        if(!lifecycle.writeLock().tryLock()) throw new UserInputException("Hay una tarea programada en ejecución; espera antes de reiniciar");
        try {
            if(jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE running=1",Long.class)>0) throw new UserInputException("Hay una tarea programada en ejecución; espera antes de reiniciar");
            return action.get();
        } finally {lifecycle.writeLock().unlock();}
    }
    public void finish(String id,String result,String message) {jdbc.update("UPDATE scheduled_tasks SET running=0,last_finished=?,last_result=?,last_message=? WHERE id=? AND running=1",Instant.now().toEpochMilli(),result,message,id);}
}
