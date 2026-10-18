package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explicit corrections require matching publication resources and an unequivocal download identity. */
@Service
public class CalibreIdentifiers {
    public record Preview(String token,long calibreId,String title,Long eplId,String revision,boolean allowed,String message) {}
    private record Plan(String owner,Instant expires,CalibreSettings.Connection connection,String item,String uuid,long calibreId,long eplId,BigDecimal revision,String fingerprint,Map<String,Object> externalSource) {}
    private final Map<String,Plan> plans=new ConcurrentHashMap<>();
    private record Control(String owner,Instant expires,java.util.concurrent.atomic.AtomicBoolean cancelled) {}
    private final Map<String,Control> operations=new ConcurrentHashMap<>();
    private final ThreadLocal<Control> active=new ThreadLocal<>();
    private Control control(String id) {
        String owner=owner();
        try {if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();}
        catch(Exception ex) {throw new UserInputException("Identificador de comprobación inválido");}
        operations.entrySet().removeIf(e->!e.getKey().equals(id) && e.getValue().expires().isBefore(Instant.now()));
        if(operations.size()>=10000 && !operations.containsKey(id)) throw new UserInputException("Hay demasiadas comprobaciones abiertas; espera unos minutos");
        var control=operations.compute(id,(key,old)->new Control(old==null?owner:old.owner(),Instant.now().plusSeconds(900),old==null?new java.util.concurrent.atomic.AtomicBoolean():old.cancelled()));
        if(!control.owner().equals(owner)) throw new UserInputException("La comprobación pertenece a otro usuario");
        return control;
    }
    public void cancel(String id) {control(id).cancelled().set(true);}
    private void checkCancelled() {
        var control=active.get();
        if(Thread.currentThread().isInterrupted() || control!=null && control.cancelled().get()) throw new UserInputException("Comprobación cancelada; se conservan los cambios ya aplicados");
    }
    private <T> T controlled(String id,java.util.function.Supplier<T> work) {
        if(id==null) return work.get();
        var control=control(id);active.set(control);
        try {checkCancelled();return client.withCancellation(control.cancelled()::get,work);}
        catch(RuntimeException ex) {checkCancelled();throw ex;}
        finally {active.remove();}
    }
    public Preview preview(long id,String operationId) {return controlled(operationId,()->preview(id));}
    public Preview apply(String token,String operationId) {return controlled(operationId,()->apply(token));}
    private final JdbcTemplate jdbc;
    private final CalibreClient client;
    private final CalibreSettings settings;
    private final CalibreTransfers transfers;
    private final CalibreCatalogService catalog;
    private final EpubFiles files;
    private final SessionAccess sessions;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreIdentifiers.class);
    public CalibreIdentifiers(JdbcTemplate jdbc,CalibreClient client,CalibreSettings settings,CalibreTransfers transfers,CalibreCatalogService catalog,EpubFiles files,SessionAccess sessions) {
        this.jdbc=jdbc;this.client=client;this.settings=settings;this.transfers=transfers;this.catalog=catalog;this.files=files;this.sessions=sessions;
    }
    private String owner() {Permission.require(Permission.CALIBRE_READ);Permission.require(Permission.CALIBRE_FILES_READ);Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);return sessions.current().id();}
    public Preview preview(long id) {
        String owner=owner();if(id<=0) throw new UserInputException("Calibre ID inválido");
        return transfers.exclusive(()->{
            checkCancelled();
            plans.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
            if(plans.size()>10000) throw new UserInputException("Hay demasiadas previsualizaciones abiertas; espera unos minutos");
            var c=settings.connection();var book=client.book(c,id);
            if(book.eplId()!=null && book.revision()!=null) {
                // A previous manual write may have succeeded although its HTTP response was lost.
                for(var pending:jdbc.queryForList("SELECT i.* FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE j.destination=? AND j.library=? AND i.calibre_id=? AND i.calibre_uuid=? AND i.identifier_completion_pending=1 AND i.state='ACCEPTED'",CalibreTransfers.destination(c),c.library(),id,book.uuid())) {
                    var identity=CalibreTransfers.storedIdentity(pending);
                    var expected=identity.revision()==null?new BigDecimal(pending.get("revision").toString()):identity.revision();
                    if(((Number)pending.get("epl_id")).longValue()==book.eplId() && expected.compareTo(book.revision())==0 && client.hasEpub(c,id)) {
                        jdbc.update("UPDATE calibre_job_items SET identifiers_completed=1,identifier_completion_pending=0,message=? WHERE id=?","Identificadores comprobados en Calibre tras recuperar una respuesta incierta; EPUB sin modificar",pending.get("id"));
                        catalog.observe(c,book);
                    }
                }
                return new Preview(null,id,book.title(),book.eplId(),book.revision().toPlainString(),false,"Los identificadores ya están completos");
            }
            var rows=jdbc.queryForList("SELECT i.*,j.source FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE j.destination=? AND j.library=? AND i.calibre_id=? AND i.state='ACCEPTED' ORDER BY j.created_at DESC,i.position",CalibreTransfers.destination(c),c.library(),id);
            if(rows.isEmpty()) {
                try {return previewExternal(owner,c,book);}
                catch(UserInputException | com.rlibanez.eplsync.exception.TorrentOperationException ex) {
                    throw new UserInputException("Importación ajena a EPL Sync: "+ex.getMessage());
                }
            }
            var row=rows.getFirst();long epl=((Number)row.get("epl_id")).longValue();
            var observed=CalibreTransfers.storedIdentity(row);BigDecimal revision=observed.revision()==null?new BigDecimal(row.get("revision").toString()):observed.revision();
            for(var candidate:rows) {
                var identity=CalibreTransfers.storedIdentity(candidate);var expected=identity.revision()==null?new BigDecimal(candidate.get("revision").toString()):identity.revision();
                if(((Number)candidate.get("epl_id")).longValue()!=epl || expected.compareTo(revision)!=0) return new Preview(null,id,book.title(),null,null,false,"Correspondencia no inequívoca: hay envíos con distintas identidades");
            }
            if(book.uuid()==null || book.uuid().isBlank() || book.eplId()!=null && book.eplId()!=epl || book.revision()!=null && book.revision().compareTo(revision)!=0)
                return new Preview(null,id,book.title(),epl,revision.toPlainString(),false,"La identidad actual de Calibre contradice el envío original");
            String storedUuid=(String)row.get("calibre_uuid");
            if(storedUuid!=null && !storedUuid.equals(book.uuid())) return new Preview(null,id,book.title(),epl,revision.toPlainString(),false,"La entrada de Calibre fue reemplazada");
            String fingerprint=compare(c,id,row);
            String token=UUID.randomUUID().toString();
            plans.put(token,new Plan(owner,Instant.now().plusSeconds(900),c,(String)row.get("id"),book.uuid(),id,epl,revision,fingerprint,null));
            return new Preview(token,id,book.title(),epl,revision.stripTrailingZeros().toPlainString(),true,"Correspondencia inequívoca con el envío original");
        });
    }
    private Preview previewExternal(String owner,CalibreSettings.Connection c,CalibreClient.Book book) {
        if(book.uuid()==null || book.uuid().isBlank()) throw new UserInputException("La entrada de Calibre no tiene una identidad verificable");
        Map<String,Object> match=null;String fingerprint=null;int records=0,compared=0;
        Path remote=null;
        try {
            remote=Files.createTempFile("eplsync-calibre-send-identifiers-remote-",".epub");
            client.downloadOriginalEpub(c,book.calibreId(),remote,268435456);
            checkCancelled();
            String remoteFingerprint=CalibreEpubMetadata.contentFingerprint(remote);
            // Inspect every retained download: titles and file names never establish identity.
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
            var downloads=jdbc.queryForList("SELECT id,epl_id,revision FROM torrent_downloads WHERE client_instance_id=? ORDER BY id",files.clientInstanceId());
            if(downloads.isEmpty()) return new Preview(null,book.calibreId(),book.title(),null,null,false,"Importación ajena a EPL Sync: no hay registros de Descargas de este cliente; sincroniza Descargas");
            for(var download:downloads) {
                checkCancelled();records++;
                if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new UserInputException("Comprobación incompleta: tiempo de espera agotado");
                String record=download.get("id").toString();
                List<EpubFiles.Item> candidates;
                try {candidates=files.completed(record);}
                catch(UserInputException | com.rlibanez.eplsync.exception.TorrentOperationException ex) {
                    throw new UserInputException("No se pudo consultar la descarga EPL ID "+download.get("epl_id")+" (registro "+record+"): "+ex.getMessage());
                }
                for(var file:candidates) {
                    checkCancelled();compared++;
                    var row=new HashMap<String,Object>();row.put("record_id",record);row.put("file_index",file.index());row.put("name",file.name());row.put("size",file.size());row.put("epl_id",download.get("epl_id"));
                    row.put("revision",new BigDecimal(download.get("revision").toString()).stripTrailingZeros().toPlainString());
                    String source=sourceFingerprint(row,false);
                    if(!remoteFingerprint.equals(source)) continue;
                    if(candidates.size()!=1) throw new UserInputException("Correspondencia no inequívoca: la descarga contiene varios EPUB");
                    long epl=((Number)row.get("epl_id")).longValue();var revision=new BigDecimal(row.get("revision").toString());
                    sourceFingerprint(row,true);
                    validateIdentity(remote,epl,revision);
                    if(book.eplId()!=null && book.eplId()!=epl || book.revision()!=null && book.revision().compareTo(revision)!=0)
                        throw new UserInputException("Los identificadores actuales de Calibre contradicen la descarga coincidente");
                    if(match!=null && (((Number)match.get("epl_id")).longValue()!=epl || new BigDecimal(match.get("revision").toString()).compareTo(revision)!=0))
                        throw new UserInputException("Correspondencia no inequívoca: hay descargas con distintas identidades");
                    match=row;fingerprint=source;
                }
            }
        } catch(java.io.IOException ex) {throw new UserInputException("No se pudo comprobar el EPUB de Calibre");}
        finally {removeTemporary(remote);}
        log.debug("Comparación de identificadores: Calibre ID={}, registros={}, EPUB originales comparados={}, coincidencia={}",book.calibreId(),records,compared,match!=null);
        if(match==null) return new Preview(null,book.calibreId(),book.title(),null,null,false,compared==0
            ?"Importación ajena a EPL Sync: consultados "+records+" registros; no hay EPUB completos y accesibles"
            :"Importación ajena a EPL Sync: no hay correspondencia inequívoca");
        long epl=((Number)match.get("epl_id")).longValue();var revision=new BigDecimal(match.get("revision").toString());String token=UUID.randomUUID().toString();
        plans.put(token,new Plan(owner,Instant.now().plusSeconds(900),c,null,book.uuid(),book.calibreId(),epl,revision,fingerprint,Map.copyOf(match)));
        return new Preview(token,book.calibreId(),book.title(),epl,revision.toPlainString(),true,"Importación ajena a EPL Sync: correspondencia inequívoca");
    }
    private static void removeTemporary(Path path) {
        if(path!=null) try {Files.deleteIfExists(path);}catch(java.io.IOException ex) {log.warn("No se pudo eliminar un temporal de comprobación de identificadores");}
    }
    private static void validateIdentity(Path path,long epl,BigDecimal revision) {
        var actual=CalibreEpubMetadata.read(path);
        if(actual.eplId()!=null && actual.eplId()!=epl || actual.revision()!=null && actual.revision().compareTo(revision)!=0)
            throw new UserInputException("Los identificadores internos del EPUB contradicen la descarga original");
    }
    private String sourceFingerprint(Map<String,Object> row,boolean validate) {
        checkCancelled();
        try {return readSourceFingerprint(row,validate);}
        catch(UserInputException | com.rlibanez.eplsync.exception.TorrentOperationException ex) {
            checkCancelled();
            throw new UserInputException("No se pudo comprobar la descarga EPL ID "+row.get("epl_id")+", revisión "+row.get("revision")+" (registro "+row.get("record_id")+"): "+ex.getMessage());
        }
    }
    private String readSourceFingerprint(Map<String,Object> row,boolean validate) {
        Path original=null;
        try {
            original=Files.createTempFile("eplsync-calibre-send-identifiers-source-",".epub");
            try(var input=files.download((String)row.get("record_id"),((Number)row.get("file_index")).intValue());var output=Files.newOutputStream(original)) {
                if(input.size()!=((Number)row.get("size")).longValue() || !input.name().equals(row.get("name"))) throw new UserInputException("La descarga original cambió; no se puede asegurar la correspondencia");
                byte[] buffer=new byte[65536];long total=0;int count;long deadline=System.nanoTime()+java.time.Duration.ofMinutes(1).toNanos();
                while((count=input.stream().read(buffer))!=-1) {checkCancelled();total+=count;if(total>input.size() || total>268435456 || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw new UserInputException("La descarga original supera los límites de comprobación");output.write(buffer,0,count);}
                if(total!=input.size()) throw new UserInputException("La descarga original está incompleta");
            }
            var expected=CalibreTransfers.storedIdentity(row);
            if(validate) validateIdentity(original,((Number)row.get("epl_id")).longValue(),expected.revision()==null?new BigDecimal(row.get("revision").toString()):expected.revision());
            return CalibreEpubMetadata.contentFingerprint(original);
        } catch(java.io.IOException ex) {throw new UserInputException("No se pudo leer el EPUB para comprobar su origen");}
        finally {removeTemporary(original);}
    }
    private String compare(CalibreSettings.Connection c,long id,Map<String,Object> row) {
        Path remote=null;
        try {
            remote=Files.createTempFile("eplsync-calibre-send-identifiers-remote-",".epub");client.downloadOriginalEpub(c,id,remote,268435456);
            var expected=CalibreTransfers.storedIdentity(row);
            validateIdentity(remote,((Number)row.get("epl_id")).longValue(),expected.revision()==null?new BigDecimal(row.get("revision").toString()):expected.revision());
            String source=sourceFingerprint(row,true);
            if(!source.equals(CalibreEpubMetadata.contentFingerprint(remote))) throw new UserInputException("El contenido del EPUB de Calibre no coincide con la descarga original");
            return source;
        } catch(java.io.IOException ex) {throw new UserInputException("No se pudo leer el EPUB para comprobar su origen");}
        finally {removeTemporary(remote);}
    }
    public Preview apply(String token) {
        String owner=owner();var plan=token==null?null:plans.get(token);
        if(plan==null || !owner.equals(plan.owner()) || plan.expires().isBefore(Instant.now())) throw new UserInputException("La previsualización ha caducado; vuelve a comprobar los libros");
        return transfers.exclusive(()->{
            checkCancelled();
            if(!plan.connection().equals(settings.connection())) throw new UserInputException("La conexión de Calibre cambió; repite la previsualización");
            var rows=jdbc.queryForList("SELECT i.* FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE i.id=? AND i.calibre_id=? AND i.state='ACCEPTED' AND j.destination=? AND j.library=?",plan.item(),plan.calibreId(),CalibreTransfers.destination(plan.connection()),plan.connection().library());
            if(plan.externalSource()==null && rows.size()!=1) throw new UserInputException("El envío original cambió o ya no existe; repite la previsualización");
            var book=client.book(plan.connection(),plan.calibreId());
            if(!plan.uuid().equals(book.uuid())) throw new UserInputException("La entrada de Calibre cambió; repite la previsualización");
            if(plan.externalSource()!=null) {
                var renewed=previewExternal(owner,plan.connection(),book);
                var checked=renewed.token()==null?null:plans.remove(renewed.token());
                if(checked==null || checked.eplId()!=plan.eplId() || checked.revision().compareTo(plan.revision())!=0 || !checked.fingerprint().equals(plan.fingerprint())) throw new UserInputException("Las descargas coincidentes cambiaron; repite la previsualización");
            }
            if(!plan.fingerprint().equals(compare(plan.connection(),plan.calibreId(),plan.externalSource()==null?rows.getFirst():plan.externalSource()))) throw new UserInputException("El EPUB cambió después de la previsualización");
            if(!plan.connection().equals(settings.connection())) throw new UserInputException("La conexión de Calibre cambió durante la comprobación");
            if(plan.externalSource()==null) jdbc.update("UPDATE calibre_job_items SET identifier_completion_pending=1,calibre_uuid=? WHERE id=?",plan.uuid(),plan.item());
            checkCancelled();
            book=client.completeIdentifiers(plan.connection(),plan.calibreId(),plan.eplId(),plan.revision(),plan.uuid());
            if(plan.externalSource()==null) jdbc.update("UPDATE calibre_job_items SET identifiers_completed=1,identifier_completion_pending=0,calibre_uuid=?,message=? WHERE id=?",plan.uuid(),"Identificadores ausentes completados en Calibre con los datos de la descarga; EPUB original sin modificar",plan.item());
            catalog.observe(plan.connection(),book);
            log.debug("Identificadores de Calibre completados: Calibre ID={}, EPL ID={}, revisión={}, elemento={}",plan.calibreId(),plan.eplId(),plan.revision(),plan.item());
            return new Preview(token,plan.calibreId(),book.title(),plan.eplId(),plan.revision().toPlainString(),false,plan.externalSource()==null?"Identificadores completados":"Importación ajena a EPL Sync: identificadores completados");
        });
    }
}
