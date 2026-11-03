package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.config.TorrentProperties;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit item decisions never reconcile an uncertain upload or infer which EPUB is the right book. */
@Service
public class CalibreItemResolution {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreItemResolution.class);
    public enum Reason {
        NOT_NEEDED("No necesito enviarlo"), MANUAL("Lo gestionaré manualmente"),
        UNSUITABLE("Contenido no adecuado"), OTHER("Otro");
        final String label;
        Reason(String label) {this.label=label;}
    }
    public record File(String recordId,int index,String name,long size) {}
    public record Preview(String token,List<File> files,String problem) {}
    public record Input(String token,String action,String recordId,Integer index,String name,Long size,Reason reason,String note,boolean confirm) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CalibreTransfers transfers;
    private final CalibreSettings settings;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentProperties torrent;
    private final EpubFiles files;
    private final JobRetryHistory history;
    private final EventJournal events;
    private final JsonMapper json=JsonMapper.builder().build();
    public CalibreItemResolution(JdbcTemplate jdbc,PlatformTransactionManager manager,CalibreTransfers transfers,
            CalibreSettings settings,DownloadRepository downloads,DownloadTrackingService tracking,TorrentProperties torrent,
            EpubFiles files,JobRetryHistory history,EventJournal events) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.transfers=transfers;this.settings=settings;
        this.downloads=downloads;this.tracking=tracking;this.torrent=torrent;this.files=files;this.history=history;this.events=events;
    }
    private static void require() {
        Permission.require(Permission.CALIBRE_JOBS_MANAGE);Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);
    }
    private Map<String,Object> row(String job,String item) {
        var j=jdbc.queryForList("SELECT state FROM calibre_jobs WHERE id=?",job);
        if(j.isEmpty()) throw new UserInputException("Trabajo de Calibre no encontrado");
        if("CANCELLED".equals(j.getFirst().get("state"))) throw new UserInputException("El trabajo está cancelado");
        if(jdbc.queryForObject("SELECT count(*) FROM calibre_preparation_operations WHERE calibre_job_id=? AND state IN ('QUEUED','RUNNING')",Long.class,job)>0) throw new UserInputException("El trabajo todavía se está preparando");
        var rows=jdbc.queryForList("SELECT * FROM calibre_job_items WHERE job_id=? AND id=?",job,item);
        if(rows.size()!=1) throw new UserInputException("El elemento no pertenece al trabajo");
        var row=rows.getFirst();
        if(!Set.of("FAILED","BLOCKED_DOWNLOAD").contains(row.get("state")) || row.get("calibre_id")!=null)
            throw new UserInputException("Solo se pueden resolver errores sin un envío pendiente de confirmación");
        return row;
    }
    private String token(String job,Map<String,Object> row) {
        var j=jdbc.queryForMap("SELECT destination,library,source FROM calibre_jobs WHERE id=?",job);
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(List.of(Objects.toString(EventContext.actor().id(),""),j,row))));
        } catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private void current(String job) {
        var j=jdbc.queryForMap("SELECT destination,source FROM calibre_jobs WHERE id=?",job);
        if(!CalibreTransfers.destination(settings.connection()).equals(j.get("destination"))) throw new UserInputException("Restaura la conexión original antes de seleccionar un EPUB");
        try {
            String source=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((tracking.instanceId()+"\n"+torrent.getFiles().getRemoteRoot()+"\n"+torrent.getFiles().getLocalRoot()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(!source.equals(j.get("source"))) throw new UserInputException("Restaura el cliente y las carpetas originales antes de seleccionar un EPUB");
        } catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private List<File> candidates(Map<String,Object> row) {
        Set<String> hashes=new HashSet<>();
        if(row.get("download_job_id")!=null) hashes.addAll(jdbc.queryForList("WITH RECURSIVE related(id) AS (SELECT ? UNION SELECT j.child_id FROM job_retry_jobs j JOIN related r ON r.id=j.parent_id WHERE j.kind='downloads') SELECT hash FROM torrent_bulk_items WHERE job_id IN (SELECT id FROM related) AND epl_id=? AND revision=? AND state IN ('ACCEPTED','ALREADY_EXISTS')",String.class,row.get("download_job_id"),row.get("epl_id"),Double.valueOf(row.get("revision").toString())));
        var result=new ArrayList<File>();
        for(var record:downloads.findByEplIdIn(List.of(((Number)row.get("epl_id")).longValue()))) {
            if(!record.getClientInstanceId().equals(tracking.instanceId()) || Double.compare(record.getRevision(),Double.parseDouble(row.get("revision").toString()))!=0 ||
                (row.get("download_job_id")!=null?!hashes.contains(record.getHash()):!record.getId().equals(row.get("record_id")))) continue;
            for(var file:files.list(record.getId())) if(file.size()<=268435456) result.add(new File(record.getId(),file.index(),file.name(),file.size()));
            if(result.size()>1000) throw new UserInputException("Demasiados EPUB; revisa la descarga antes de resolverla");
        }
        return List.copyOf(result);
    }
    public Preview preview(String job,String item) {
        require();var row=row(job,item);String fingerprint=token(job,row);
        try {current(job);var choices=candidates(row);return new Preview(fingerprint,choices,choices.isEmpty()?"No hay EPUB disponibles en la descarga asociada":null);}
        catch(RuntimeException ex) {return new Preview(fingerprint,List.of(),ex instanceof UserInputException || ex instanceof com.rlibanez.eplsync.exception.TorrentOperationException?ex.getMessage():com.rlibanez.eplsync.exception.OperationDiagnostics.reason(ex,"No se pudieron consultar los EPUB; puedes volver a comprobarlos u omitir el elemento"));}
    }
    public CalibreTransfers.Job apply(String job,String item,Input input) {
        require();
        if(input==null || !input.confirm() || input.token()==null || input.token().length()!=64 || !Set.of("retry","omit").contains(Objects.toString(input.action(),""))) throw new UserInputException("Confirma la resolución del elemento");
        String note=Objects.toString(input.note(),"").strip();
        if(note.length()>500 || note.chars().anyMatch(c->Character.isISOControl(c) && c!='\n')) throw new UserInputException("El comentario no es válido o supera 500 caracteres");
        if(input.action().equals("omit") && input.reason()==null) throw new UserInputException("Selecciona el motivo de la omisión");
        return transfers.exclusive(()-> {
            var before=row(job,item);
            if(!token(job,before).equals(input.token())) throw new UserInputException("El elemento cambió; vuelve a abrir Resolver");
            File selected=null;
            if(input.action().equals("retry")) {
                current(job);
                selected=candidates(before).stream().filter(f->f.recordId().equals(input.recordId()) && Objects.equals(f.index(),input.index()) && f.name().equals(input.name()) && Objects.equals(f.size(),input.size())).findFirst()
                    .orElseThrow(()->new UserInputException("El EPUB ya no está disponible o ha cambiado; vuelve a abrir Resolver"));
            }
            File chosen=selected;
            tx.executeWithoutResult(status -> {
                if(chosen!=null) current(job);
                if(!token(job,row(job,item)).equals(input.token())) throw new UserInputException("El elemento cambió; vuelve a abrir Resolver");
                var actor=EventContext.actor();
                String attempt=history.itemResolution(job,item,chosen==null?"WAIT":"SEND",actor);
                String now=Instant.now().toString();
                if(chosen==null) {
                    String message="Omitido por el usuario. Motivo: "+input.reason().label+(note.isEmpty()?"":". "+note);
                    jdbc.update("UPDATE calibre_job_items SET state='OMITTED',cleanup_policy='KEEP',cleanup_ids='[]',cleanup_cancelled=1,next_check=0,message=? WHERE id=?",message,item);
                    jdbc.update("UPDATE calibre_jobs SET updated_at=? WHERE id=?",now,job);
                    history.resolved(attempt,item);
                    if(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=? AND state IN ('PENDING','SENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','UNCERTAIN','CLEANUP_PENDING')",Long.class,job)==0)
                        jdbc.update("UPDATE calibre_jobs SET state='COMPLETED',message=NULL WHERE id=?",job);
                } else {
                    jdbc.update("UPDATE calibre_job_items SET state='PENDING',record_id=?,file_index=?,name=?,size=?,calibre_id=NULL,calibre_uuid=NULL,epub_identity=NULL,identity_complete=0,identifiers_completed=0,identifier_completion_pending=0,next_check=0,transient_attempts=0,message='EPUB seleccionado por el usuario; reintento solicitado' WHERE id=?",chosen.recordId(),chosen.index(),chosen.name(),chosen.size(),item);
                    boolean blocked=jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=? AND state='BLOCKED_DOWNLOAD'",Long.class,job)>0;
                    jdbc.update("UPDATE calibre_jobs SET state=?,next_check=0,next_probe=0,updated_at=?,message=? WHERE id=?",blocked?"PAUSED":"QUEUED",now,blocked?"Hay otros elementos pendientes de revisión":null,job);
                }
                String operation=UUID.randomUUID().toString();
                var details=Map.of("jobId",job,"command",chosen==null?"omitItem":"resolveItem","itemId",item,"reason",chosen==null?input.reason().name():"EPUB_SELECTED","note",note);
                events.recordAs(actor,EventJournal.Category.JOB,"CALIBRE_JOB_CONTROL",EventJournal.Outcome.STARTED,EventContext.Origin.MANUAL,operation,details);
                events.recordAs(actor,EventJournal.Category.JOB,"CALIBRE_JOB_CONTROL",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,operation,details);
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            log.info("Decisión sobre elemento de Calibre: acción={}, usuario={}, userId={}, trabajo={}, elemento={}, EPL ID={}, motivo={}",
                                chosen==null?"OMITIR":"RESOLVER",actor.username(),actor.id(),job,item,before.get("epl_id"),
                                chosen==null?input.reason().name():"EPUB_SELECTED");
                        }
                    });
            });
            return transfers.job(job);
        });
    }
}
