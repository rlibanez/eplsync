package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.channels.*;
import java.io.*;
import java.util.*;

@Service
public class EpubFiles {
    private final DownloadRepository records;
    private final DownloadTrackingService tracking;
    private final TorrentClientService client;
    private final TorrentProperties properties;
    public EpubFiles(DownloadRepository records, DownloadTrackingService tracking, TorrentClientService client, TorrentProperties properties) {
        this.records=records; this.tracking=tracking; this.client=client; this.properties=properties;
    }
    public record Item(int index, String name, long size) {}
    public record Opened(String name, long size, InputStream stream) implements AutoCloseable {
        public void close() throws IOException { stream.close(); }
    }
    private TorrentOperationException unavailable(String message) { return new TorrentOperationException(HttpStatus.CONFLICT,message); }
    private List<TorrentFile> files(String id) {
        return client.exclusiveClient(adapter -> {
            var row=records.findById(id).orElseThrow(() -> new TorrentOperationException(HttpStatus.NOT_FOUND,"Registro de descarga no encontrado"));
            if (!row.getClientInstanceId().equals(tracking.instanceId())) throw unavailable("El registro pertenece a otro cliente torrent");
            return adapter.listFiles(row.getHash()).stream().filter(f -> f.name().toLowerCase(Locale.ROOT).endsWith(".epub") && f.progress()==1 && f.size()>0).toList();
        });
    }
    public List<Item> list(String id) {
        requireRoots();
        var result=new ArrayList<Item>();
        for(var f:files(id)) {
            try(var opened=open(f)) { result.add(new Item(f.index(), opened.name(), opened.size())); }
            catch(IOException ex) { throw unavailable("No se puede leer el EPUB; revisa el montaje y los permisos de la carpeta"); }
        }
        if(result.isEmpty()) throw unavailable("El torrent no contiene archivos EPUB completamente descargados");
        return List.copyOf(result);
    }
    public Opened download(String id,int index) {
        requireRoots();
        var file=files(id).stream().filter(f -> f.index()==index).findFirst().orElseThrow(() -> unavailable("El EPUB no existe o todavía no está completamente descargado"));
        try { return open(file); } catch(IOException ex) { throw unavailable("El EPUB no está accesible; revisa la carpeta compartida y sus permisos"); }
    }
    record Planned(String recordId, TorrentFile file) {}
    record Plan(String instance, String remoteRoot, String localRoot, boolean archive, List<Planned> items) {}
    Plan prepare(List<String> ids) {
        requireRoots();
        if (ids == null || ids.isEmpty() || ids.size()>1000 || ids.stream().anyMatch(id -> id==null || id.isBlank() || id.length()>64))
            throw new com.rlibanez.eplsync.exception.UserInputException("Selecciona entre 1 y 1000 registros de descargas");
        var selected=ids.stream().distinct().toList();
        return client.exclusiveClient(adapter -> {
            var roots=properties.getFiles();
            var planned=new ArrayList<Planned>();
            var failures=new ArrayList<String>();
            var cache=new HashMap<String,List<TorrentFile>>();
            var paths=new HashSet<Path>();
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
            for (var id:selected) {
                if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)
                    throw unavailable("La preparación ha tardado demasiado; selecciona menos registros e inténtalo de nuevo");
                var row=records.findById(id).orElse(null);
                if(row==null) { failures.add("Registro "+id+": ya no existe"); continue; }
                String label="EPL ID "+row.getEplId()+" (r"+row.getRevision()+")";
                if(!row.getClientInstanceId().equals(tracking.instanceId())) { failures.add(label+": pertenece a otro cliente torrent");continue; }
                List<TorrentFile> candidates;
                try {
                    candidates=cache.computeIfAbsent(row.getHash(), hash -> adapter.listFiles(hash).stream()
                            .filter(f -> f.name().toLowerCase(Locale.ROOT).endsWith(".epub") && f.progress()==1 && f.size()>0).toList());
                } catch(TorrentOperationException ex) {
                    failures.add(label+": "+ex.getMessage());continue;
                }
                if(candidates.isEmpty()) { failures.add(label+": no hay EPUB completamente descargados");continue; }
                for(var file:candidates) {
                    try(var opened=open(file)) {
                        if(paths.add(relative(roots.getRemoteRoot(),file.savePath(),file.name()))) planned.add(new Planned(id,file));
                    } catch(IOException | TorrentOperationException ex) { failures.add(label+": EPUB inaccesible o fuera de la carpeta configurada");break; }
                    if(planned.size()>1000) throw unavailable("La selección contiene más de 1000 archivos EPUB; selecciona menos registros");
                }
            }
            if(!failures.isEmpty()) throw unavailable("No se ha iniciado la descarga. Revisa los registros afectados:\n"+String.join("\n",failures));
            return new Plan(tracking.instanceId(),roots.getRemoteRoot(),roots.getLocalRoot(),selected.size()>1 || planned.size()>1,List.copyOf(planned));
        });
    }
    void validate(Plan plan) {
        requireRoots();
        client.requireEnabled();
        if(!plan.instance().equals(tracking.instanceId()) || !plan.remoteRoot().equals(properties.getFiles().getRemoteRoot())
                || !plan.localRoot().equals(properties.getFiles().getLocalRoot()))
            throw unavailable("La configuración del cliente o de las carpetas ha cambiado; vuelve a iniciar la descarga");
        for(var entry:plan.items()) try(var opened=open(entry.file())) { /* Check every file before sending headers. */ }
            catch(IOException | TorrentOperationException ex) { throw unavailable("No se ha iniciado la descarga: un EPUB ya no está accesible. Vuelve a intentarlo"); }
    }
    Opened open(Planned entry) throws IOException { return open(entry.file()); }

    private void requireRoots() {
        var f=properties.getFiles();
        if(f.getRemoteRoot().isBlank() || f.getLocalRoot().isBlank()) throw unavailable("Configura las carpetas de descarga en Ajustes > Torrent");
    }
    static Path relative(String root,String savePath,String name) {
        // Remote paths may use Windows separators even when EPL Sync runs on Linux.
        if (java.util.stream.Stream.of(root,savePath,name).anyMatch(v -> v.isBlank() || v.chars().anyMatch(Character::isISOControl)))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"Ruta de archivo inválida");
        String base=trimRoot(root);
        String save=trimRoot(savePath);
        String file=name.replace('\\','/');
        String prefix=base.endsWith("/") ? base : base+"/";
        if(base.isBlank() || !(base.startsWith("/") || base.matches("[A-Za-z]:/.*"))
                || !(save.equals(base) || save.startsWith(prefix)) || file.startsWith("/") || file.contains(":"))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"El archivo queda fuera de la carpeta configurada");
        var relative=Path.of((save.equals(base)?"":save.substring(prefix.length())+"/")+file);
        for(var part:relative) if(part.toString().equals("..") || part.toString().equals("."))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"Ruta de archivo no permitida");
        if(relative.isAbsolute() || !file.toLowerCase(Locale.ROOT).endsWith(".epub"))
            throw new TorrentOperationException(HttpStatus.CONFLICT,"Ruta de EPUB inválida");
        return relative;
    }
    private static String trimRoot(String value) {
        String result=value.replace('\\','/');
        while(result.endsWith("/") && result.length()>1 && !result.matches("[A-Za-z]:/"))
            result=result.substring(0,result.length()-1);
        return result;
    }
    private Opened open(TorrentFile file) throws IOException {
        var roots=properties.getFiles();
        var root=Path.of(roots.getLocalRoot()).toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Not a directory");
        var relative=relative(roots.getRemoteRoot(),file.savePath(),file.name());
        SeekableByteChannel channel;
        // Descriptor-relative traversal prevents replacing a parent with a symlink between validation and opening.
        try(var directory=Files.newDirectoryStream(root)) {
            if(directory instanceof SecureDirectoryStream<Path> secure) channel=openSecure(secure,relative,0);
            else {
                var path=root.resolve(relative);
                var current=root;
                for(var part:relative) { current=current.resolve(part); if(Files.isSymbolicLink(current)) throw new IOException("Symlink"); }
                if(!path.toRealPath().startsWith(root) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid file");
                channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
            }
        }
        try {
            if(channel.size()!=file.size()) throw new IOException("File size changed");
            return new Opened(relative.getFileName().toString(),channel.size(),Channels.newInputStream(channel));
        } catch(IOException | RuntimeException ex) { channel.close(); throw ex; }
    }
    private static SeekableByteChannel openSecure(SecureDirectoryStream<Path> directory,Path path,int index) throws IOException {
        var name=path.getName(index);
        if(index==path.getNameCount()-1) {
            var attrs=directory.getFileAttributeView(name,java.nio.file.attribute.BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
            if(!attrs.isRegularFile()) throw new IOException("Not a regular file");
            return directory.newByteChannel(name,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
        }
        try(var child=directory.newDirectoryStream(name,LinkOption.NOFOLLOW_LINKS)) { return openSecure(child,path,index+1); }
    }
}
