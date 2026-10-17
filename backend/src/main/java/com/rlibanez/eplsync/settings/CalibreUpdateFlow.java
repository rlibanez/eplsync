package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Plans are checked again on submission; durable items pin the requested revision and download job. */
@Service
public class CalibreUpdateFlow {
    public record Previous(long id,String revision) {}
    public record Book(long calibreId,long eplId,String title,double revision,List<Previous> previous,List<CalibreTransfers.Candidate> files,String problem) {}
    public record Choice(long calibreId,double revision,boolean send,List<CalibreTransfers.FileChoice> files,String cleanup,List<Long> previous,boolean confirmBookDelete) {}
    public record Request(String id,List<Choice> books,boolean download,boolean deferred,com.rlibanez.eplsync.torrent.updates.UpdateRequest downloadOptions) {
        public Request(String id,List<Choice> books,boolean download,boolean deferred) {this(id,books,download,deferred,null);}
    }
    public record Result(String downloadJobId,String calibreJobId) {}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreUpdateFlow.class);
    @org.springframework.beans.factory.annotation.Autowired private DownloadDependencies dependencies;
    private final JdbcTemplate jdbc;
    private final CalibreSettings settings;
    private final CalibreClient client;
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final EpubFiles files;
    private final BulkStore bulk;
    private final CalibreTransfers transfers;
    private final com.rlibanez.eplsync.service.TorrentClientService torrent;
    private final TransactionTemplate tx;
    private final com.rlibanez.eplsync.tasks.InternalTaskSettings internalSettings;
    private final JsonMapper json=JsonMapper.builder().build();
    public CalibreUpdateFlow(JdbcTemplate jdbc,CalibreSettings settings,CalibreClient client,DownloadRepository downloads,
        DownloadTrackingService tracking,EpubFiles files,BulkStore bulk,CalibreTransfers transfers,
        com.rlibanez.eplsync.service.TorrentClientService torrent,org.springframework.transaction.PlatformTransactionManager manager,
        com.rlibanez.eplsync.tasks.InternalTaskSettings internalSettings) {
        this.jdbc=jdbc;this.settings=settings;this.client=client;this.downloads=downloads;this.tracking=tracking;
        this.files=files;this.bulk=bulk;this.transfers=transfers;this.torrent=torrent;tx=new TransactionTemplate(manager);
        this.internalSettings=internalSettings;
    }
    private static void require() {Permission.require(Permission.CALIBRE_SEND);Permission.require(Permission.DOWNLOADS_FILES_READ);Permission.require(Permission.CATALOG_READ);}
    public List<Book> prepare(List<Long> ids) {
        require();
        if(ids==null || ids.isEmpty() || ids.stream().anyMatch(i -> i==null || i<=0) || new HashSet<>(ids).size()!=ids.size()) throw new UserInputException("Selecciona libros únicos");
        if(Permission.has(Permission.TORRENT_SYNC)) {
            var epls=new HashSet<Long>();
            for(int offset=0;offset<ids.size();offset+=500) {
                var batch=ids.subList(offset,Math.min(offset+500,ids.size()));
                epls.addAll(jdbc.queryForList("SELECT DISTINCT epl_id FROM calibre_books WHERE active=true AND calibre_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",Long.class,batch.toArray()));
            }
            dependencies.refresh(epls);
        }
        var c=settings.connection();client.check(c);
        var result=new ArrayList<Book>();var seenBooks=new HashSet<Long>();long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        for(long id:ids) {
            if(System.nanoTime()>deadline) throw new UserInputException("La preparación ha tardado demasiado; selecciona menos libros");
            var rows=jdbc.queryForList("SELECT b.epl_id,c.title,c.revision FROM calibre_books b JOIN catalog_books c ON c.epl_id=b.epl_id WHERE b.active=true AND b.calibre_id=? AND b.revision<c.revision AND NOT EXISTS (SELECT 1 FROM calibre_books newer WHERE newer.active=true AND newer.epl_id=b.epl_id AND newer.revision>=c.revision)",id);
            if(rows.size()!=1) throw new UserInputException("La selección de actualizaciones cambió; vuelve a buscar");
            var row=rows.getFirst();long epl=((Number)row.get("epl_id")).longValue();double revision=((Number)row.get("revision")).doubleValue();
            if(!seenBooks.add(epl)) throw new UserInputException("Selecciona una sola actualización por EPL ID");
            var previous=jdbc.query("SELECT calibre_id,revision FROM calibre_books WHERE active=true AND epl_id=? AND revision<? ORDER BY revision,calibre_id",(r,n)->new Previous(r.getLong(1),BigDecimal.valueOf(r.getDouble(2)).stripTrailingZeros().toPlainString()),epl,revision);
            if(previous.size()>1000) throw new UserInputException("Demasiadas revisiones anteriores para este libro");
            var candidates=new ArrayList<CalibreTransfers.Candidate>();String problem=null;
            for(var d:downloads.findByEplIdIn(List.of(epl))) {
                if(!d.getClientInstanceId().equals(tracking.instanceId()) || Double.compare(d.getRevision(),revision)!=0 || d.getStatus()!=DownloadStatus.DOWNLOADED) continue;
                try {var available=files.list(d.getId());if(available.isEmpty()) problem="La descarga no contiene ningún EPUB disponible";for(var f:available) if(f.size()>268435456) problem="El EPUB supera 256 MiB";else candidates.add(new CalibreTransfers.Candidate(d.getId(),f.index(),f.name(),f.size(),epl,revision,f.expectedSize()));}
                catch(RuntimeException ex) {problem="El EPUB descargado no es accesible; revisa el montaje y los permisos";}
            }
            if(!candidates.isEmpty()) problem=null;
            result.add(new Book(id,epl,(String)row.get("title"),revision,previous,List.copyOf(candidates),problem));
        }
        return List.copyOf(result);
    }
    public record Availability(long calibreId,String state) {}
    public List<Availability> availability(List<Long> ids) {
        Permission.require(Permission.CALIBRE_READ);Permission.require(Permission.DOWNLOADS_FILES_READ);Permission.require(Permission.CATALOG_READ);
        if(ids==null || ids.isEmpty() || ids.stream().anyMatch(i -> i==null || i<=0) || new HashSet<>(ids).size()!=ids.size()) throw new UserInputException("Selecciona libros únicos");
        var rows=new ArrayList<Map<String,Object>>();
        for(int offset=0;offset<ids.size();offset+=500) {
            var batch=ids.subList(offset,Math.min(offset+500,ids.size()));
            var placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
            rows.addAll(jdbc.queryForList("SELECT b.calibre_id,b.epl_id,c.revision FROM calibre_books b JOIN catalog_books c ON c.epl_id=b.epl_id WHERE b.active=true AND b.calibre_id IN ("+placeholders+")",batch.toArray()));
        }
        var epls=rows.stream().map(r -> ((Number)r.get("epl_id")).longValue()).distinct().toList();
        var byBook=new HashMap<Long,List<DownloadRecord>>();
        for(int offset=0;offset<epls.size();offset+=500) for(var d:downloads.findByEplIdIn(epls.subList(offset,Math.min(offset+500,epls.size())))) byBook.computeIfAbsent(d.getEplId(),ignored->new ArrayList<>()).add(d);
        var result=new ArrayList<Availability>();
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        for(var row:rows) {
            if(System.nanoTime()>deadline) throw new UserInputException("La comprobación ha tardado demasiado; vuelve a buscar");
            long id=((Number)row.get("calibre_id")).longValue(), epl=((Number)row.get("epl_id")).longValue();
            double revision=((Number)row.get("revision")).doubleValue();String state="MISSING";
            for(var d:byBook.getOrDefault(epl,List.of())) {
                if(d.getEplId()!=epl || !d.getClientInstanceId().equals(tracking.instanceId()) || Double.compare(d.getRevision(),revision)!=0 || d.getStatus()!=DownloadStatus.DOWNLOADED) continue;
                state="INACCESSIBLE";
                try {
                    if(files.list(d.getId()).stream().anyMatch(f -> f.size()<=268435456)) {state="READY";break;}
                } catch(RuntimeException ex) {log.debug("No se pudo comprobar el EPUB de la actualización: EPL ID={}",epl,ex);}
            }
            result.add(new Availability(id,state));
        }
        return List.copyOf(result);
    }
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.torrent.updates.UpdatePlanner updatePlanner;
    public Result create(Request request) {return createAutomated(request,r->{});}
    public Result createAutomated(Request request,java.util.function.Consumer<Result> committed) {return transfers.preparing(()->createPrepared(request,committed));}
    private Result createPrepared(Request request,java.util.function.Consumer<Result> committed) {
        require();if(request==null || request.id()==null || !request.id().matches("[A-Za-z0-9-]{1,64}") || request.books()==null || request.books().stream().anyMatch(Objects::isNull)) throw new UserInputException("Selección inválida");
        var previousRequest=existing(request);
        if(previousRequest!=null) return previousRequest;
        var prepared=prepare(request.books().stream().map(b -> b.calibreId()).toList());
        var selected=new ArrayList<CalibreTransfers.Candidate>();var waits=new ArrayList<Book>();var cleanup=new HashMap<Long,Choice>();var missing=new ArrayList<Long>();
        for(int n=0;n<prepared.size();n++) {
            var b=prepared.get(n);var choice=request.books().get(n);
            if(Double.compare(choice.revision(),b.revision())!=0) throw new UserInputException("La revisión del catálogo cambió; vuelve a preparar el envío");
            if(!choice.send()) continue;
            if(choice.cleanup()==null || !Set.of("KEEP","EPUB","BOOK").contains(choice.cleanup())) throw new UserInputException("Política de limpieza inválida");
            var old=choice.previous();if(old==null || old.size()>1000 || old.stream().anyMatch(Objects::isNull) || new HashSet<>(old).size()!=old.size() || old.stream().anyMatch(i -> b.previous().stream().noneMatch(p -> p.id()==i))) throw new UserInputException("Revisiones anteriores inválidas");
            if(!choice.cleanup().equals("KEEP")) {Permission.require(Permission.CALIBRE_DELETE);if(old.isEmpty() || choice.cleanup().equals("BOOK") && !choice.confirmBookDelete()) throw new UserInputException("Confirma la eliminación de las revisiones seleccionadas");}
            cleanup.put(b.eplId(),choice);
            if(b.files().isEmpty()) {
                if(b.problem()!=null) throw new UserInputException(b.problem());
                if(!request.download()) throw new UserInputException("Activa el envío a descargar para los EPUB no disponibles");
                missing.add(b.eplId());if(request.deferred()) waits.add(b);
            } else {
                if(choice.files()==null || choice.files().size()!=1 || choice.files().getFirst()==null) throw new UserInputException("Selecciona un EPUB por actualización");
                var f=choice.files().getFirst();selected.add(b.files().stream().filter(a -> a.recordId().equals(f.recordId()) && a.index()==f.index()).findFirst().orElseThrow(()->new UserInputException("El EPUB seleccionado cambió")));
            }
        }
        if(selected.isEmpty() && missing.isEmpty()) throw new UserInputException("Selecciona algún libro");
        if(!missing.isEmpty()) Permission.require(Permission.TORRENT_SEND);
        if(!waits.isEmpty()) {Permission.require(Permission.TORRENT_SYNC);files.requireRoots();}
        var connection=settings.connection();
        return tx.execute(s -> {
            var existing=existing(request);if(existing!=null) return existing;
            for(var b:prepared) {
                var revisions=jdbc.queryForList("SELECT revision FROM catalog_books WHERE epl_id=?",Double.class,b.eplId());
                if(revisions.size()!=1 || Double.compare(revisions.getFirst(),b.revision())!=0) throw new UserInputException("La revisión del catálogo cambió; vuelve a preparar el envío");
            }
            String downloadId=null;
            var dependencies=new HashMap<Long,String>();
            var wanted=new HashMap<Long,Double>();waits.forEach(book->wanted.put(book.eplId(),book.revision()));
            dependencies.putAll(this.dependencies.reusable(wanted));
            missing.removeAll(dependencies.keySet());
            if(!missing.isEmpty()) {
                var filter=new CatalogBookFilter();filter.setSelectedIds(missing.toArray(Long[]::new));
                downloadId=request.downloadOptions()==null
                    ?bulk.create(filter,org.springframework.data.domain.Pageable.unpaged(),false,false,new BulkRequest(null,null,null,null,MultipleHashes.FIRST)).jobId()
                    :updatePlanner.create(filter,true,request.downloadOptions(),com.rlibanez.eplsync.torrent.updates.UpdatePlanner.Selection.REQUESTED).jobId();
            }
            String calibreId=selected.isEmpty() && waits.isEmpty()?null:transfers.enqueueUpdates(connection,selected,waits,cleanup,downloadId,dependencies);
            var result=new Result(downloadId,calibreId);
            committed.accept(result);
            jdbc.update("INSERT INTO calibre_update_requests(id,owner_id,payload,result,created_at) VALUES(?,?,?,?,?)",request.id(),EventContext.actor().id(),signature(request),json.writeValueAsString(result),Instant.now().toString());
            return result;
        });
    }
    private String signature(Request request) {
        try {return "sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private Result existing(Request request) {
        var rows=jdbc.queryForList("SELECT owner_id,payload,result FROM calibre_update_requests WHERE id=?",request.id());
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!Objects.equals(row.get("owner_id"),EventContext.actor().id()) || !(Objects.equals(row.get("payload"),signature(request)) || (!row.get("payload").toString().startsWith("sha256:") && json.readValue((String)row.get("payload"),Request.class).equals(request)))) throw new UserInputException("La solicitud ya se utilizó con otra selección");
        return json.readValue((String)row.get("result"),Result.class);
    }
    /** Called by the single sender; only hashes from the linked job and pinned revision are observed. */
    void refreshDownloads(String job) {
        long now=Instant.now().toEpochMilli();
        if(jdbc.queryForObject("SELECT next_probe FROM calibre_jobs WHERE id=?",Long.class,job)>now) return;
        var hashes=new HashSet<>(jdbc.queryForList("WITH RECURSIVE retry_jobs(original,id) AS (SELECT DISTINCT download_job_id,download_job_id FROM calibre_job_items WHERE job_id=? AND download_job_id IS NOT NULL UNION SELECT r.original,j.child_id FROM retry_jobs r JOIN job_retry_jobs j ON j.parent_id=r.id AND j.kind='downloads') SELECT DISTINCT b.hash FROM torrent_bulk_items b JOIN retry_jobs r ON r.id=b.job_id JOIN calibre_job_items i ON i.download_job_id=r.original AND i.epl_id=b.epl_id AND CAST(i.revision AS REAL)=b.revision WHERE i.job_id=? AND i.state='WAITING_DOWNLOAD' AND b.state IN ('ACCEPTED','ALREADY_EXISTS')",String.class,job,job));
        if(!hashes.isEmpty()) torrent.exclusiveClient(adapter -> {
            var remote=new HashMap<String,RemoteTorrent>();for(var t:hashes.size()>1000?adapter.listTorrents():adapter.listTorrents(hashes)) for(String alias:t.aliases()) remote.put(alias,t);
            tracking.observeSelected(remote,hashes);return null;
        });
        jdbc.update("UPDATE calibre_jobs SET next_probe=? WHERE id=?",now+internalSettings.interval(com.rlibanez.eplsync.tasks.InternalTaskSettings.CALIBRE).toMillis(),job);
    }
    void postponeDownloadCheck(String job) {
        long now=Instant.now().toEpochMilli();
        long next=now+internalSettings.interval(com.rlibanez.eplsync.tasks.InternalTaskSettings.CALIBRE).toMillis();
        jdbc.update("UPDATE calibre_jobs SET next_check=? WHERE id=? AND state IN ('QUEUED','RUNNING')",next,job);
        jdbc.update("UPDATE calibre_job_items SET next_check=? WHERE job_id=? AND state='WAITING_DOWNLOAD' AND next_check<=?",next,job,now);
    }
    boolean waiting(String job,CalibreTransfers.Item item) {
        var row=jdbc.queryForMap("SELECT i.download_job_id,i.wait_until FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE i.id=?",item.id());
        if(((Number)row.get("wait_until")).longValue()<Instant.now().toEpochMilli()) throw new UserInputException("La espera superó 30 días; revisa la descarga y reanuda");
        String downloadId=(String)row.get("download_job_id");
        Set<String> hashes=new HashSet<>();
        if(downloadId!=null) {
            var hashRows=jdbc.queryForList("WITH RECURSIVE retry_jobs(id) AS (SELECT ? UNION SELECT j.child_id FROM job_retry_jobs j JOIN retry_jobs r ON r.id=j.parent_id WHERE j.kind='downloads') SELECT hash,state FROM torrent_bulk_items WHERE job_id IN (SELECT id FROM retry_jobs) AND epl_id=? AND revision=?",downloadId,item.eplId(),Double.valueOf(item.revision()));
            if(hashRows.isEmpty() || hashRows.stream().allMatch(r -> Set.of("FAILED","CANCELLED","SKIPPED").contains(r.get("state")))) throw new UserInputException("El envío al cliente torrent falló, se canceló o ya no existe");
            for(var r:hashRows) if(Set.of("ACCEPTED","ALREADY_EXISTS").contains(r.get("state"))) hashes.add((String)r.get("hash"));
        }
        boolean recordFound=false;
        for(var d:downloads.findByEplIdIn(List.of(item.eplId()))) {
            if(!d.getClientInstanceId().equals(tracking.instanceId()) || (downloadId==null?!d.getId().equals(item.recordId()):!hashes.contains(d.getHash())) || Double.compare(d.getRevision(),Double.parseDouble(item.revision()))!=0) continue;
            recordFound=true;
            if(d.getStatus()==DownloadStatus.NOT_FOUND || d.getStatus()==DownloadStatus.ERROR) throw new UserInputException("El torrent no está disponible o tiene un error; revisa Estado de las descargas");
            if(d.getStatus()!=DownloadStatus.DOWNLOADED) continue;
            var available=files.list(d.getId());
            if(available.size()!=1) throw new UserInputException("La descarga no contiene un único EPUB; selecciona el archivo manualmente en Estado de las descargas");
            var f=available.getFirst();if(f.size()>268435456) throw new UserInputException("El EPUB supera 256 MiB");
            jdbc.update("UPDATE calibre_job_items SET state='PENDING',transient_attempts=0,next_check=0,record_id=?,file_index=?,name=?,size=?,message=? WHERE id=? AND state='WAITING_DOWNLOAD'",d.getId(),f.index(),f.name(),f.size(),f.size()!=f.expectedSize()?CalibreTransfers.SIZE_WARNING:null,item.id());return true;
        }
        if(!hashes.isEmpty() && !recordFound) throw new UserInputException("El registro de la descarga aceptada ya no existe; prepara un nuevo envío desde el Catálogo");
        jdbc.update("UPDATE calibre_job_items SET next_check=? WHERE id=?",Instant.now().plus(internalSettings.interval(com.rlibanez.eplsync.tasks.InternalTaskSettings.CALIBRE)).toEpochMilli(),item.id());return false;
    }
    void cleanup(String job,CalibreSettings.Connection c,CalibreTransfers.Item item) {
        var row=jdbc.queryForMap("SELECT cleanup_policy,cleanup_ids FROM calibre_job_items WHERE id=?",item.id());
        String policy=(String)row.get("cleanup_policy");
        if(policy.equals("KEEP")) return;
        var newest=client.book(c,Objects.requireNonNull(item.calibreId()));
        if(!Objects.equals(newest.eplId(),item.eplId()) || newest.revision()==null || newest.revision().compareTo(new BigDecimal(item.revision()))!=0 || !client.hasEpub(c,newest.calibreId())) throw new UserInputException("La nueva revisión no está verificada con EPUB e identificadores; no se eliminará ninguna revisión anterior");
        List<Long> ids=new ArrayList<>();for(var id:json.readTree((String)row.get("cleanup_ids"))) ids.add(id.asLong());
        for(long id:new ArrayList<>(ids)) {
            transfers.requireCleanupAllowed(job,c);
            if(id==newest.calibreId()) throw new UserInputException("No se puede eliminar la nueva revisión");
            CalibreClient.Book old=null;
            try {old=client.book(c,id);}catch(org.springframework.web.server.ResponseStatusException ex) {if(ex.getStatusCode().value()!=404) throw ex;}
            if(old!=null) {
                if(!Objects.equals(old.eplId(),item.eplId()) || old.revision()==null || old.revision().compareTo(newest.revision())>=0) throw new UserInputException("Los metadatos de la revisión anterior cambiaron; no se eliminará");
                if(policy.equals("BOOK") || client.hasEpub(c,id)) {
                    try {client.remove(c,id,policy.equals("BOOK"));}catch(RuntimeException ex) {if(!removed(c,id,policy)) throw ex;}
                    if(!removed(c,id,policy)) throw new UserInputException("Calibre no confirmó el borrado de la revisión anterior");
                }
            }
            if(policy.equals("BOOK")) transfers.removeObserved(c,id);
            log.debug("Limpieza de Calibre verificada: trabajo={}, EPL ID={}, Calibre ID={}, política={}",job,item.eplId(),id,policy);
            ids.remove(id);jdbc.update("UPDATE calibre_job_items SET cleanup_ids=? WHERE id=?",json.writeValueAsString(ids),item.id());
        }
    }
    private boolean removed(CalibreSettings.Connection c,long id,String policy) {
        try {if(policy.equals("BOOK")) {client.book(c,id);return false;}return !client.hasEpub(c,id);}catch(org.springframework.web.server.ResponseStatusException ex) {if(ex.getStatusCode().value()==404) return true;throw ex;}
    }
}
