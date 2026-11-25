package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly authorize a new import when the download has stale internal revision metadata. */
@Service
public class CalibreRevisionConfirmation {
    public record Preview(String token,long calibreId,long eplId,String epubRevision,String currentRevision,String targetRevision) {}
    public record Input(String token,boolean confirm) {}
    private record Checked(Map<String,Object> row,CalibreSettings.Connection connection,CalibreClient.IdentifierMetadata metadata,
                           String epubRevision,String fingerprint,Preview preview) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CalibreTransfers transfers;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final CalibreCatalogService catalog;
    private final EpubFiles files;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreRevisionConfirmation.class);
    public CalibreRevisionConfirmation(JdbcTemplate jdbc,PlatformTransactionManager manager,CalibreTransfers transfers,
            CalibreSettings settings,CalibreClient client,CalibreCatalogService catalog,EpubFiles files) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.transfers=transfers;this.settings=settings;this.client=client;this.catalog=catalog;this.files=files;
    }
    private static void require() {
        for(var permission:List.of(Permission.CALIBRE_READ,Permission.CALIBRE_FILES_READ,Permission.CALIBRE_SEND,
                Permission.CALIBRE_DELETE,Permission.CALIBRE_JOBS_MANAGE,Permission.DOWNLOADS_FILES_READ)) Permission.require(permission);
    }
    private CalibreSettings.Connection connection(Map<String,Object> row) {
        var c=settings.connection();
        if(!CalibreTransfers.destination(c).equals(row.get("destination")) || !transfers.source().equals(row.get("source")))
            throw new UserInputException("Restaura la conexión y las carpetas originales antes de confirmar la revisión");
        return new CalibreSettings.Connection(c.url(),c.username(),c.password(),(String)row.get("library"));
    }
    private Checked check(String job,String item) {
        var rows=jdbc.queryForList("SELECT i.*,j.state job_state,j.destination,j.source,j.library FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE j.id=? AND i.id=?",job,item);
        if(rows.size()!=1) throw new UserInputException("El elemento no pertenece al trabajo");
        var row=rows.getFirst();
        if(!"PAUSED".equals(row.get("job_state")) || !"CLEANUP_PENDING".equals(row.get("state")) || row.get("calibre_id")==null ||
                !row.get("name").toString().toLowerCase(Locale.ROOT).endsWith(".epub"))
            throw new UserInputException("Solo se puede confirmar una revisión con limpieza pendiente en un trabajo pausado");
        var c=connection(row);long id=((Number)row.get("calibre_id")).longValue(),epl=((Number)row.get("epl_id")).longValue();
        var target=new BigDecimal(row.get("revision").toString());
        var download=jdbc.queryForList("SELECT epl_id,revision FROM torrent_downloads WHERE id=?",row.get("record_id"));
        if(download.size()!=1 || ((Number)download.getFirst().get("epl_id")).longValue()!=epl ||
                new BigDecimal(download.getFirst().get("revision").toString()).compareTo(target)!=0)
            throw new UserInputException("El registro de descarga ya no corresponde al EPL ID y la revisión del trabajo");
        var book=client.book(c,id);var metadata=client.identifierMetadata(c,id);
        if(book.eplId()==null || book.eplId()!=epl || book.revision()==null || metadata.uuid()==null || metadata.uuid().isBlank() ||
                !metadata.uuid().equals(book.uuid()) || row.get("calibre_uuid")!=null && !metadata.uuid().equals(row.get("calibre_uuid")) ||
                !Objects.equals(CalibreClient.positiveId(metadata.identifiers().get("epl")),epl) ||
                CalibreClient.revision(metadata.identifiers().get("revision"))==null ||
                CalibreClient.revision(metadata.identifiers().get("revision")).compareTo(book.revision())!=0)
            throw new UserInputException("La identidad de Calibre no coincide con el libro del trabajo");
        Path original=null;
        try {
            original=Files.createTempFile("eplsync-revision-source-",".epub");
            try(var in=files.download((String)row.get("record_id"),((Number)row.get("file_index")).intValue());var out=Files.newOutputStream(original)) {
                if(in.size()!=((Number)row.get("size")).longValue() || !in.name().equals(row.get("name"))) throw new UserInputException("La descarga original cambió");
                long total=0,deadline=System.nanoTime()+java.time.Duration.ofMinutes(1).toNanos();int n;byte[] buffer=new byte[65536];
                while((n=in.stream().read(buffer))!=-1) {
                    total+=n;if(total>in.size() || total>268435456 || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw new UserInputException("El EPUB supera los límites de comprobación");
                    out.write(buffer,0,n);
                }
                if(total!=in.size()) throw new UserInputException("El EPUB original está incompleto");
            }
            var identity=CalibreEpubMetadata.read(original);var stored=CalibreTransfers.storedIdentity(row);
            if(identity.eplId()==null || identity.eplId()!=epl || identity.revision()==null || identity.revision().compareTo(target)>=0 ||
                    stored.eplId()!=null && !stored.eplId().equals(identity.eplId()) || stored.revision()!=null && stored.revision().compareTo(identity.revision())!=0)
                throw new UserInputException("El EPUB no tiene la discrepancia de revisión esperada o no coincide con el envío original");
            String fingerprint=CalibreEpubMetadata.contentFingerprint(original);
            String internal=identity.revision().stripTrailingZeros().toPlainString();
            if(book.revision().compareTo(target)>=0) throw new UserInputException("La entrada antigua ya no tiene una revisión inferior a la descarga");
            if(jdbc.queryForObject("SELECT count(*) FROM calibre_revision_confirmations WHERE item_id=?",Long.class,item)>0)
                throw new UserInputException("Ya existe una confirmación para este elemento; continúa o verifica el envío pendiente sin volver a autorizarlo");
            String token=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(
                List.of(job,item,Objects.toString(EventContext.actor().id(),EventContext.actor().username()),row.get("destination"),row.get("source"),row.get("library"),id,epl,internal,target.toPlainString(),fingerprint,metadata))));
            return new Checked(row,c,metadata,internal,fingerprint,new Preview(token,id,epl,internal,book.revision().toPlainString(),target.toPlainString()));
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException ex) {throw new UserInputException("No se pudo comprobar el EPUB de la descarga");}
        finally {for(var path:new Path[]{original}) if(path!=null) try {Files.deleteIfExists(path);}catch(java.io.IOException ex) {log.warn("No se pudo eliminar un temporal de comprobación de revisión");}}
    }
    public Preview preview(String job,String item) {require();return transfers.exclusive(()->check(job,item).preview());}
    public CalibreTransfers.Job apply(String job,String item,Input input) {
        require();if(input==null || !input.confirm() || input.token()==null) throw new UserInputException("Confirma explícitamente la importación de la descarga como una revisión nueva");
        return transfers.exclusive(()-> {
            var checked=check(job,item);var p=checked.preview();
            if(!p.token().equals(input.token())) throw new UserInputException("La comprobación cambió; vuelve a abrir la confirmación");
            if(!connection(checked.row()).equals(checked.connection())) throw new UserInputException("La conexión cambió durante la comprobación");
            String now=Instant.now().toString();
            tx.executeWithoutResult(status -> {
                // Persist consent and queue the same item atomically, before any upload is dispatched.
                jdbc.update("INSERT INTO calibre_revision_confirmations(item_id,created_at,username,epub_revision,target_revision,fingerprint,expected_metadata,mode,old_calibre_id) VALUES(?,?,?,?,?,?,?,'IMPORT',?)",
                    item,now,Objects.toString(EventContext.actor().username(),"Usuario"),checked.epubRevision(),p.targetRevision(),checked.fingerprint(),json.writeValueAsString(checked.metadata()),p.calibreId());
                var ids=new ArrayList<Long>();for(var id:json.readTree(checked.row().get("cleanup_ids").toString())) ids.add(id.asLong());
                if(!ids.contains(p.calibreId())) ids.add(p.calibreId());
                jdbc.update("UPDATE calibre_job_items SET state='PENDING',calibre_id=NULL,calibre_uuid=NULL,file_sha256=NULL,identity_complete=0,identifiers_completed=0,identifier_completion_pending=0,next_check=0,transient_attempts=0,cleanup_ids=?,message=? WHERE id=?",
                    json.writeValueAsString(ids),"Importación explícita de la descarga como r"+p.targetRevision()+"; EPUB interno r"+p.epubRevision()+". Se conserva la entrada antigua hasta verificar la nueva",item);
                jdbc.update("UPDATE calibre_jobs SET state='QUEUED',message=NULL,next_check=0,next_probe=0,updated_at=? WHERE id=? AND state='PAUSED'",now,job);
            });
            log.info("Importación de revisión autorizada: trabajo={}, elemento={}, Calibre ID antiguo={}, EPL ID={}, revisión EPUB={}, revisión solicitada={}, usuario={}",job,item,p.calibreId(),p.eplId(),p.epubRevision(),p.targetRevision(),EventContext.actor().username());
            return transfers.job(job);
        });
    }

    void verifySource(String item,Path original) {
        var row=jdbc.queryForMap("SELECT fingerprint,epub_revision FROM calibre_revision_confirmations WHERE item_id=? AND mode='IMPORT'",item);
        var identity=CalibreEpubMetadata.read(original);
        if(identity.revision()==null || identity.revision().compareTo(new BigDecimal(row.get("epub_revision").toString()))!=0 || !row.get("fingerprint").equals(CalibreEpubMetadata.contentFingerprint(original))) throw new UserInputException("La descarga cambió después de autorizar su importación");
    }
    /** Verify only the newly imported entry; never alter the old entry to make its revision match. */
    CalibreClient.Book verifyImported(CalibreSettings.Connection c,CalibreClient.Book book,CalibreTransfers.Item item) {
        var row=jdbc.queryForMap("SELECT * FROM calibre_revision_confirmations WHERE item_id=? AND mode='IMPORT'",item.id());
        if(book.calibreId()==((Number)row.get("old_calibre_id")).longValue()) throw new UserInputException("La entrada antigua no puede utilizarse como nueva importación");
        if(row.get("new_calibre_id")!=null && ((Number)row.get("new_calibre_id")).longValue()!=book.calibreId()) throw new UserInputException("La nueva entrada no coincide con la importación autorizada");
        var metadata=client.identifierMetadata(c,book.calibreId());
        if(!Objects.equals(book.eplId(),item.eplId()) || !Objects.equals(CalibreClient.positiveId(metadata.identifiers().get("epl")),item.eplId()) ||
                book.uuid()==null || book.uuid().isBlank() || !book.uuid().equals(metadata.uuid())) throw new UserInputException("La identidad de la nueva importación no coincide con la descarga");
        var actualRevision=CalibreClient.revision(metadata.identifiers().get("revision"));
        if(actualRevision==null || actualRevision.compareTo(new BigDecimal(row.get("epub_revision").toString()))!=0 && actualRevision.compareTo(new BigDecimal(row.get("target_revision").toString()))!=0)
            throw new UserInputException("La revisión de la nueva entrada cambió; no se sobrescribirá");
        Path remote=null;
        try {
            remote=Files.createTempFile("eplsync-revision-import-",".epub");client.downloadOriginalEpub(c,book.calibreId(),remote,268435456);
            var identity=CalibreEpubMetadata.read(remote);
            if(!Objects.equals(identity.eplId(),item.eplId()) || !row.get("fingerprint").equals(CalibreEpubMetadata.contentFingerprint(remote)))
                throw new UserInputException("El EPUB de la nueva importación no coincide con la descarga autorizada; se conserva la revisión antigua");
        } catch(java.io.IOException ex) {throw new UserInputException("No se pudo verificar el EPUB de la nueva importación");}
        finally {if(remote!=null) try {Files.deleteIfExists(remote);}catch(java.io.IOException ex) {log.warn("No se pudo eliminar un temporal de verificación de importación");}}
        var expected=row.get("new_metadata")==null?metadata:json.readValue(row.get("new_metadata").toString(),CalibreClient.IdentifierMetadata.class);
        if(!expected.uuid().equals(metadata.uuid())) throw new UserInputException("La entrada importada fue reemplazada; no se modificará");
        // Persist the exact new entry and its before-image before changing its identifier.
        jdbc.update("UPDATE calibre_revision_confirmations SET new_calibre_id=?,new_metadata=coalesce(new_metadata,?) WHERE item_id=?",book.calibreId(),json.writeValueAsString(expected),item.id());
        client.updateIdentifiers(c,book.calibreId(),expected,Map.of("revision",row.get("target_revision").toString()),Set.of("revision"));
        var verified=client.book(c,book.calibreId());
        if(!Objects.equals(verified.eplId(),item.eplId()) || verified.revision()==null || verified.revision().compareTo(new BigDecimal(item.revision()))!=0 ||
                !expected.uuid().equals(verified.uuid()) || !client.hasEpub(c,book.calibreId())) throw new UserInputException("La nueva importación sigue sin verificar; no se eliminará la revisión antigua");
        jdbc.update("UPDATE calibre_revision_confirmations SET applied_at=coalesce(applied_at,?) WHERE item_id=?",Instant.now().toString(),item.id());
        log.debug("Importación de revisión verificada: elemento={}, EPL ID={}, Calibre ID antiguo={}, Calibre ID nuevo={}, revisión={}",item.id(),item.eplId(),row.get("old_calibre_id"),book.calibreId(),item.revision());
        return verified;
    }
}
