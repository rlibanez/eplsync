package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.security.SessionAccess;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Ephemeral HTTP-operation feedback. Never performs database writes or submits torrents. */
@Service
public class TorrentPreparationProgress {
    public static final String HEADER="X-EPLSync-Preparation-Id";
    public record Progress(String id,String state,String phase,long processed,long total,long items,String jobId,Object result,String message) {}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TorrentPreparationProgress.class);
    private static final ThreadLocal<Entry> CURRENT=new ThreadLocal<>();
    private final Map<String,Entry> entries=new ConcurrentHashMap<>();
    private final SessionAccess sessions;
    private final JsonMapper json=JsonMapper.builder().build();
    public TorrentPreparationProgress(SessionAccess sessions) {this.sessions=sessions;}
    private static final class Entry {
        final String id,owner,signature;final long started=System.currentTimeMillis();
        volatile boolean cancelRequested;volatile boolean cancellable;
        volatile Progress progress;Object response;RuntimeException failure;long loggedAt;int checkpoint=-1;String loggedPhase="";
        Entry(String id,String owner,String signature) {this.id=id;this.owner=owner;this.signature=signature;progress=new Progress(id,"RUNNING","WAITING",0,0,0,null,null,null);}
    }
    private void require() {Permission.require(Permission.TORRENT_SEND);}
    private String signature(String path,Object input) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((path+":"+json.writeValueAsString(input)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public <T> T track(jakarta.servlet.http.HttpServletRequest request,Object input,Supplier<T> work) {
        String id=request.getHeader(HEADER);if(id==null) return work.get();
        require();if(!id.matches("[A-Za-z0-9-]{8,100}")) throw new UserInputException("Identificador de preparación inválido");
        String owner=sessions.current().id(),signature=signature(request.getRequestURI(),input);Entry entry;
        synchronized(entries) {
            long cutoff=System.currentTimeMillis()-3_600_000;
            entries.values().removeIf(e->!e.progress.state().equals("RUNNING") && e.started<cutoff);
            entry=entries.get(id);
            if(entry!=null) {
                if(!entry.owner.equals(owner) || !entry.signature.equals(signature)) throw new UserInputException("La preparación pertenece a otra selección");
                if(entry.progress.state().equals("COMPLETED")) { @SuppressWarnings("unchecked") T result=(T)entry.response;return result; }
                if(entry.failure!=null) throw entry.failure;
                throw new com.rlibanez.eplsync.exception.TorrentOperationException(HttpStatus.CONFLICT,"La preparación ya está en curso; consulta su progreso");
            }
            if(entries.size()>=1000 || entries.values().stream().filter(e->e.owner.equals(owner) && e.progress.state().equals("RUNNING")).count()>=5)
                throw new UserInputException("Hay demasiadas preparaciones pendientes; espera a que terminen");
            entry=new Entry(id,owner,signature);entries.put(id,entry);
        }
        Entry previous=CURRENT.get();CURRENT.set(entry);report("WAITING",0,0,0,null);
        try {
            T response=work.get();Object body=response instanceof ResponseEntity<?> http?http.getBody():response;
            var p=entry.progress;String job=body instanceof BulkStore.View view?view.jobId():p.jobId();
            synchronized(entries) {entry.response=response;entry.progress=new Progress(id,"COMPLETED","COMPLETED",p.processed(),p.total(),p.items(),job,body,null);}
            log.info("Preparación torrent completada: operación={}, trabajo={}, libros={}, elementos={}, duraciónMs={}",id,job,p.processed(),p.items(),System.currentTimeMillis()-entry.started);
            return response;
        } catch(RuntimeException ex) {
            synchronized(entries) {entry.failure=ex;var p=entry.progress;String job=ex instanceof BulkCatalogPreparation.Failed failed?failed.jobId():null;String message=ex instanceof BulkCatalogPreparation.Failed || ex instanceof Cancelled?ex.getMessage():"No se pudo preparar el trabajo; no hay un trabajo confirmado para consultar";entry.progress=new Progress(id,ex instanceof Cancelled?"CANCELLED":"FAILED",p.phase(),p.processed(),p.total(),p.items(),job,null,message);}
            if(ex instanceof Cancelled) log.info("Preparación torrent cancelada: operación={}",id);
            else log.warn("Preparación torrent fallida: operación={}, causa={}",id,com.rlibanez.eplsync.exception.OperationDiagnostics.technical(ex));throw ex;
        } finally {if(previous==null) CURRENT.remove();else CURRENT.set(previous);}
    }
    public Progress status(String id) {
        require();var entry=entries.get(id);
        if(entry==null || !entry.owner.equals(sessions.current().id())) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return entry.progress;
    }
    public static final class Cancelled extends com.rlibanez.eplsync.exception.TorrentOperationException {
        Cancelled() {super(HttpStatus.CONFLICT,"Preparación cancelada; no se ha enviado ningún torrent ni conservado trabajos provisionales");}
    }
    public static void allowCancellation() {var entry=CURRENT.get();if(entry!=null) synchronized(entry) {entry.cancellable=true;}}
    public static void checkCancelled() {var entry=CURRENT.get();if(entry!=null && entry.cancelRequested) throw new Cancelled();}
    public Progress cancel(String id) {
        status(id);var entry=entries.get(id);
        synchronized(entry) {
            if(!entry.progress.state().equals("RUNNING")) return entry.progress;
            if(!entry.cancellable || entry.progress.phase().equals("FINALIZING")) throw new com.rlibanez.eplsync.exception.TorrentOperationException(HttpStatus.CONFLICT,"La preparación ya está finalizando o no admite cancelación");
            entry.cancelRequested=true;
        }
        log.info("Cancelación de preparación torrent solicitada: operación={}",id);return entry.progress;
    }
    public static boolean tracked() {return CURRENT.get()!=null;}
    public static void report(String phase,long processed,long total,long items,String job) {
        var entry=CURRENT.get();if(entry==null) return;
        synchronized(entry) {
            checkCancelled();
            entry.progress=new Progress(entry.id,"RUNNING",phase,processed,total,items,job,null,null);
        }
        long now=System.currentTimeMillis();int checkpoint=total>0?(int)Math.min(10,processed*10/total):(int)(processed/1000);
        boolean boundary=!phase.equals(entry.loggedPhase) || total>0 && processed>=total;
        boolean partials=!phase.equals("SNAPSHOT") && !phase.equals("PREPARING");
        if(boundary || partials && (checkpoint>entry.checkpoint && now-entry.loggedAt>=5000 || now-entry.loggedAt>=15000)) {
            var level=(!phase.equals(entry.loggedPhase) || total>0 && processed>=total)?log.atInfo():log.atDebug();
            level.log("Preparación torrent: operación={}, fase={}, librosProcesados={}, total={}, elementos={}",entry.id,phase,processed,total,items);
            entry.loggedPhase=phase;entry.checkpoint=checkpoint;entry.loggedAt=now;
        }
    }
}
