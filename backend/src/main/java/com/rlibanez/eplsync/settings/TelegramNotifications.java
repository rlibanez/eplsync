package com.rlibanez.eplsync.settings;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.exception.UserInputException;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class TelegramNotifications {
    public record Input(boolean enabled,String token,boolean clearToken,String chatId,boolean success,boolean warning,boolean error,String language) {}
    public record View(boolean enabled,boolean tokenConfigured,String chatId,boolean success,boolean warning,boolean error,String language,long pending,long failed) {}
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final TelegramClient client;
    private final TransactionTemplate tx;
    private final String key;
    private long lastMaintenance;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("telegram-notifications").factory());
    private final java.util.concurrent.Semaphore testing=new java.util.concurrent.Semaphore(2);
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TelegramNotifications.class);
    @org.springframework.beans.factory.annotation.Value("${eplsync.telegram.worker-enabled:true}") private boolean workerEnabled;
    public TelegramNotifications(JdbcTemplate jdbc,AccountStore accounts,TelegramClient client,PlatformTransactionManager manager,
        @org.springframework.beans.factory.annotation.Value("${EPLSYNC_SECRET_KEY:}") String key) {
        this.jdbc=jdbc;this.accounts=accounts;this.client=client;tx=new TransactionTemplate(manager);this.key=key;
    }
    private List<Map<String,Object>> rows(String user) {return jdbc.queryForList("SELECT * FROM telegram_settings WHERE user_id=?",user);}
    private static boolean flag(Map<String,Object> row,String field) {var v=row.get(field);return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue()!=0;}
    public View view(String user) {
        var rows=rows(user);if(rows.isEmpty()) return new View(false,false,"",false,true,true,"es",0,0);
        var r=rows.getFirst();return new View(flag(r,"enabled"),!r.get("token").toString().isEmpty(),(String)r.get("chat_id"),flag(r,"success"),flag(r,"warning"),flag(r,"error"),(String)r.get("language"),
            jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries WHERE user_id=? AND state IN ('PENDING','SENDING')",Long.class,user),
            jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries WHERE user_id=? AND state='FAILED'",Long.class,user));
    }
    @org.springframework.transaction.annotation.Transactional
    public View save(String user,Input input) {
        if(input==null || input.chatId()==null || !Set.of("es","en").contains(Objects.requireNonNullElse(input.language(),""))) throw new UserInputException("Configuración de Telegram inválida");
        String chat=input.chatId().strip();
        if(!chat.isEmpty() && !chat.matches("-?[1-9][0-9]{0,19}")) throw new UserInputException("El ID del chat de Telegram debe ser numérico");
        var rows=rows(user);String token=rows.isEmpty()?"":rows.getFirst().get("token").toString();
        if(input.clearToken()) token="";
        if(input.token()!=null && !input.token().isBlank()) {
            String plain=input.token().strip();
            if(!plain.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,200}")) throw new UserInputException("Token de Telegram inválido");
            if(key.isBlank()) throw new SecretKeyRequiredException();
            token=new CredentialCipher(key).encrypt("telegram."+user,plain);
        }
        if(input.enabled() && (token.isEmpty() || chat.isEmpty())) throw new UserInputException("Configura el token del bot y el ID del chat antes de activar Telegram");
        if(input.enabled()) {
            try {
                String plain=new CredentialCipher(key).decrypt("telegram."+user,token);
                if(plain.substring(0,plain.indexOf(':')).equals(chat))
                    throw new UserInputException("El ID del chat es el del propio bot; introduce el message.chat.id de tu conversación privada, no el id que devuelve getMe");
            } catch(UserInputException ex) {throw ex;}
            catch(RuntimeException ex) {throw new UserInputException("No se puede recuperar el token de Telegram; revisa EPLSYNC_SECRET_KEY o guarda de nuevo el token");}
        }
        jdbc.update("INSERT INTO telegram_settings(user_id,enabled,token,chat_id,success,warning,error,language) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET enabled=excluded.enabled,token=excluded.token,chat_id=excluded.chat_id,success=excluded.success,warning=excluded.warning,error=excluded.error,language=excluded.language",user,input.enabled(),token,chat,input.success(),input.warning(),input.error(),input.language());
        // A queued notification must never move to a different destination after editing settings.
        jdbc.update("DELETE FROM telegram_deliveries WHERE user_id=? AND state='PENDING'",user);
        return view(user);
    }
    private String token(String user,Map<String,Object> row) {
        try {return new CredentialCipher(key).decrypt("telegram."+user,row.get("token").toString());}
        catch(RuntimeException ex) {throw new UserInputException("No se puede recuperar el token de Telegram; revisa EPLSYNC_SECRET_KEY o guarda de nuevo el token");}
    }
    public record TestInput(String token, boolean clearToken, String chatId) {}
    public void test(String user) {test(user,null);}
    public void test(String user,TestInput input) {
        if(!testing.tryAcquire()) throw new UserInputException("Hay otras pruebas de Telegram en curso; inténtalo de nuevo");
        try {
            var rows=rows(user);if(input==null && (rows.isEmpty() || rows.getFirst().get("token").toString().isEmpty() || rows.getFirst().get("chat_id").toString().isEmpty())) throw new UserInputException("Guarda primero el token y el ID del chat");
            var r=rows.isEmpty()?null:rows.getFirst();
            String chat=input==null?(String)r.get("chat_id"):Objects.requireNonNullElse(input.chatId(),"").strip();
            String plain=input==null?"":Objects.requireNonNullElse(input.token(),"").strip();
            if(plain.isEmpty() && (input==null || !input.clearToken()) && r!=null && !r.get("token").toString().isEmpty()) plain=token(user,r);
            if(!plain.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,200}")) throw new UserInputException("Token de Telegram inválido");
            if(!chat.matches("-?[1-9][0-9]{0,19}")) throw new UserInputException("El ID del chat de Telegram debe ser numérico");
            var result=client.send(plain,chat,"EPL Sync · Telegram OK");
            if(!result.success()) {
                log.warn("Prueba de Telegram fallida: motivo={}, httpStatus={}, errorCode={}",result.failure(),result.httpStatus(),result.errorCode());
                throw new UserInputException(result.safeMessage());
            }
        } finally {testing.release();}
    }
    private static String safeText(String text) {
        String safe=text.replaceAll("[\\p{Cntrl}]"," ")
            .replaceAll("[0-9]{5,20}:[A-Za-z0-9_-]{20,200}","[token oculto]")
            .replaceAll("(?i)https?://[^\\s]+","[URL omitida]")
            .replaceAll("(?i)(password|token|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+","$1=[oculto]");
        return safe.substring(0,Math.min(600,safe.length()));
    }
    private static Permission permission(EventJournal.Category category,String action) {
        if(category==EventJournal.Category.SECURITY || action.equals("CALIBRE_SEND_FILE") || action.equals("CALIBRE_JOB_CONTROL")) return null;
        if(Set.of("CALIBRE_SYNC","CALIBRE_UPDATE_SEARCH","TORRENT_UPDATE_SEARCH").contains(action)) return Permission.SETTINGS_MANAGE;
        if(category==EventJournal.Category.JOB) return action.equals("CALIBRE_SEND")?Permission.CALIBRE_JOBS_MANAGE:action.equals("SCHEDULED_TASK")?Permission.SETTINGS_MANAGE:Permission.TORRENT_JOBS_MANAGE;
        if(category==EventJournal.Category.CATALOG || category==EventJournal.Category.COVERS) return Permission.CATALOG_READ;
        return Permission.TORRENT_SYNC;
    }
    private static String configuration(Map<String,Object> row) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((row.get("token")+"\n"+row.get("chat_id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    /** Bounded local outbox only; Telegram is never called from a business transaction. */
    public void enqueue(EventContext.Actor actor,EventJournal.Category category,String action,EventJournal.Outcome outcome,EventContext.Origin origin,String operation,Map<String,?> details) {
        if(actor.id()==null || operation==null || operation.length()>128) return;
        String field=switch(outcome) {case SUCCEEDED -> "success";case PARTIAL,PAUSED,RETRY_WAIT -> "warning";case FAILED -> "error";default -> null;};
        var permission=permission(category,action);if(field==null || permission==null) return;
        // A catalogue step may finish while an automatic update still has downloads or cleanup pending.
        if(category==EventJournal.Category.CATALOG && outcome==EventJournal.Outcome.SUCCEEDED && Set.of("TORRENT_AUTO_UPDATE","CALIBRE_AUTO_UPDATE").contains(Objects.toString(details.get("taskOperation"),""))) return;
        if(action.equals("SCHEDULED_TASK") && outcome==EventJournal.Outcome.SUCCEEDED) {
            if(details.get("pending") instanceof Number pending && pending.longValue()>0) return;
            if(details.get("cycle") instanceof String cycle && jdbc.queryForObject("SELECT count(*) FROM scheduled_automation_runs WHERE id=? AND owner_id=? AND state='COMPLETED'",Long.class,cycle,actor.id())==0) return;
        }
        var values=rows(actor.id());if(values.isEmpty()) return;var r=values.getFirst();
        if(!flag(r,"enabled") || !flag(r,field)) return;
        if(jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries WHERE user_id=? AND state IN ('PENDING','SENDING')",Long.class,actor.id())>=100) return;
        boolean spanish=r.get("language").equals("es");
        String result=switch(field) {case "success"->spanish?"Completado":"Completed";case "warning"->spanish?"Requiere atención":"Needs attention";default->spanish?"Fallido":"Failed";};
        String taskId=details.get("taskId") instanceof String id?id:null;
        boolean job=action.equals("DOWNLOAD") || action.equals("CALIBRE_SEND");
        if(job) {
            String kind=action.equals("DOWNLOAD")?"downloads":"calibre";
            var ids=jdbc.queryForList("SELECT r.task_id FROM scheduled_automation_runs r JOIN scheduled_automation_jobs j ON j.run_id=r.id WHERE j.kind=? AND j.job_id=? AND r.owner_id=?",String.class,kind,operation,actor.id());
            if(!ids.isEmpty()) taskId=ids.getFirst();
        }
        if(taskId==null) return;
        var tasks=jdbc.queryForList("SELECT name FROM scheduled_tasks WHERE id=? AND owner_id=? AND telegram_notifications=1",String.class,taskId,actor.id());
        if(tasks.isEmpty()) return;
        String taskName=tasks.getFirst();
        String icon=switch(field) {case "success" -> "✅";case "warning" -> "⚠️";default -> "❌";};
        String source=spanish?switch(origin) {case MANUAL->"ejecución manual";case SCHEDULED->"ejecución programada";case SYSTEM->"Sistema";}:switch(origin) {case MANUAL->"manual execution";case SCHEDULED->"scheduled execution";case SYSTEM->"System";};
        StringBuilder message=new StringBuilder(icon).append(" ").append(safeText(taskName))
            .append("\n").append(spanish?"Origen: ":"Origin: ").append(source)
            .append("\nID: ").append(operation)
            .append("\n\n").append(spanish?"Resultado: ":"Result: ").append(result);
        String reason=details.get("reason") instanceof String text?text:null;
        if(reason==null && !field.equals("success") && job) {
            String table=action.equals("DOWNLOAD")?"torrent_bulk_items":"calibre_job_items";
            var reasons=jdbc.queryForList("SELECT DISTINCT message FROM "+table+" WHERE job_id=? AND state IN ('FAILED','UNCERTAIN','BLOCKED_DOWNLOAD') AND message IS NOT NULL LIMIT 3",String.class,operation);
            if(!reasons.isEmpty()) reason=String.join("; ",reasons);
        }
        if(reason==null && !field.equals("success")) reason=spanish?"La operación requiere atención; revisa sus detalles en EPL Sync":"The operation needs attention; check its details in EPL Sync";
        if(reason!=null && !field.equals("success")) message.append("\n").append(spanish?"Motivo: ":"Reason: ").append(safeText(reason));
        // Only bounded user-facing reasons and selected counters are forwarded.
        var counts=new StringJoiner("\n");
        for(String count:List.of("selected","accepted","failed","errors","removed","blocked","unconfirmed"))
            if(details.get(count) instanceof Number n) counts.add("• "+(spanish?switch(count) {case "selected"->"Seleccionados";case "accepted"->"Aceptados";case "failed","errors"->"Fallidos";case "removed"->"Eliminados";case "blocked"->"Bloqueados";default->"Sin confirmar";}:count)+": "+n.longValue());
        if(counts.length()>0) message.append("\n").append(counts);
        long now=Instant.now().toEpochMilli();
        jdbc.update("INSERT INTO telegram_deliveries(id,user_id,operation_id,action,outcome,permission,configuration,message,task_id,state,created_at,next_attempt) SELECT ?,?,?,?,?,?,?,?,?,'PENDING',?,? WHERE (SELECT count(*) FROM telegram_deliveries WHERE user_id=? AND state IN ('PENDING','SENDING'))<100 ON CONFLICT(user_id,operation_id,action,outcome) DO NOTHING",UUID.randomUUID().toString(),actor.id(),operation,action,outcome.name(),permission.name(),configuration(r),message.toString(),taskId,now,now,actor.id());
    }
    @jakarta.annotation.PostConstruct void start() {
        if(workerEnabled) {
            jdbc.update("UPDATE telegram_deliveries SET state='FAILED' WHERE state='SENDING'");
            worker.scheduleWithFixedDelay(()->{try {deliver();}catch(RuntimeException ex) {log.warn("No se pudo procesar la cola de Telegram: tipo={}",ex.getClass().getSimpleName());}},1,1,TimeUnit.SECONDS);
        }
    }
    void deliver() {
        long now=Instant.now().toEpochMilli();
        if(now-lastMaintenance>=60000) {
            jdbc.update("DELETE FROM telegram_deliveries WHERE created_at<?",now-java.time.Duration.ofDays(7).toMillis());
            jdbc.update("UPDATE telegram_deliveries SET state='FAILED' WHERE state='PENDING' AND created_at<?",now-java.time.Duration.ofDays(1).toMillis());
            lastMaintenance=now;
        }
        if(jdbc.queryForList("SELECT id FROM telegram_deliveries WHERE state='PENDING' AND next_attempt<=? LIMIT 1",String.class,now).isEmpty()) return;
        var pending=tx.execute(status -> {
            var selected=jdbc.queryForList("SELECT * FROM telegram_deliveries WHERE state='PENDING' AND next_attempt<=? ORDER BY created_at,id LIMIT 1",now);
            if(selected.isEmpty()) return selected;
            jdbc.update("UPDATE telegram_deliveries SET state='SENDING',attempts=attempts+1 WHERE id=?",selected.getFirst().get("id"));return selected;
        });
        if(pending==null || pending.isEmpty()) return;var delivery=pending.getFirst();String user=(String)delivery.get("user_id");String id=(String)delivery.get("id");
        var settings=rows(user);var owner=accounts.find(user);boolean sent=false,retry=false;int delay=30;
        try {
            if(delivery.get("task_id")!=null && jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE id=? AND owner_id=? AND telegram_notifications=1",Long.class,delivery.get("task_id"),user)>0 && !settings.isEmpty() && configuration(settings.getFirst()).equals(delivery.get("configuration")) && flag(settings.getFirst(),"enabled") && owner!=null && owner.status().equals("ACTIVE") && !owner.mustChangePassword()
                && (owner.temporaryExpiresAt()==null || owner.temporaryExpiresAt().isAfter(Instant.now())) && owner.permissions().contains(Permission.valueOf((String)delivery.get("permission")))) {
                var r=settings.getFirst();var response=client.send(token(user,r),(String)r.get("chat_id"),(String)delivery.get("message"));
                sent=response.success();retry=response.retryable();delay=response.retryAfter();
                if(!sent) log.debug("Entrega de Telegram fallida: id={}, motivo={}, httpStatus={}, errorCode={}",id,response.failure(),response.httpStatus(),response.errorCode());
            }
        } catch(RuntimeException ex) {log.debug("Detalle de entrega de Telegram fallida: id={}, tipo={}",id,ex.getClass().getSimpleName());}
        int attempts=((Number)delivery.get("attempts")).intValue()+1;
        jdbc.update("UPDATE telegram_deliveries SET state=?,next_attempt=? WHERE id=? AND state='SENDING'",sent?"SENT":retry && attempts<3?"PENDING":"FAILED",Instant.now().toEpochMilli()+delay*1000L,id);
        if(!sent && (!retry || attempts>=3)) log.warn("Notificación de Telegram no entregada: id={}",id);
    }
    @jakarta.annotation.PreDestroy void close() {worker.shutdownNow();}
}
