package com.rlibanez.eplsync.settings;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Each instance holds an OS lock: a process crash releases it without relying on timestamps. */
final class CalibreDownloadFiles implements AutoCloseable {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CalibreDownloadFiles.class);
    private final Path root;
    private Path directory;
    private FileChannel channel;
    private FileLock lock;
    private boolean closed;

    CalibreDownloadFiles(Path root) { this.root=root; }

    private void ensureRoot() throws IOException {
        try {
            if(root.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.createDirectory(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectory(root);
        } catch(FileAlreadyExistsException ignored) { /* Validate below; never follow a symbolic link. */ }
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Calibre temporary directory");
        if(root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions=Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS);
            if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                throw new IOException("Calibre temporary directory is not private");
        }
    }

    synchronized Path create(boolean archive) throws IOException {
        if(closed) throw new IOException("Calibre downloads are closed");
        ensureRoot();
        if(directory==null) {
            var created=Files.createDirectory(root.resolve("instance-"+UUID.randomUUID()));
            var opened=FileChannel.open(created.resolve(".lock"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try {
                lock=opened.lock();channel=opened;directory=created;
            } catch(IOException | RuntimeException ex) { opened.close();throw ex; }
        }
        return Files.createTempFile(directory,archive?"archive-":"epub-",archive?".zip":".epub");
    }

    synchronized void cleanAbandoned() {
        try {
            ensureRoot();
            try(var directories=Files.newDirectoryStream(root,"instance-*")) {
                for(var candidate:directories) {
                    if(!candidate.getFileName().toString().matches("instance-[0-9a-f-]{36}")
                            || !Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS)) continue;
                    Path marker=candidate.resolve(".lock");
                    if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)) continue;
                    boolean cleaned=false;
                    try(var owner=FileChannel.open(marker,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                        try(var abandoned=owner.tryLock()) {
                            if(abandoned!=null) { removeContents(candidate);cleaned=true; }
                        }
                    } catch(OverlappingFileLockException ignored) { /* Another live instance in this JVM. */ }
                    catch(IOException ex) { log.warn("No se pudieron limpiar temporales abandonados de descarga de Calibre"); }
                    if(cleaned) try { removeEmptyDirectory(candidate); }
                    catch(IOException ex) { log.warn("No se pudo eliminar una carpeta temporal vacía de Calibre"); }
                }
            }
        } catch(IOException ex) { log.warn("No se pudo revisar la carpeta temporal de descargas de Calibre"); }
    }

    private static void removeContents(Path directory) throws IOException {
        try(var files=Files.newDirectoryStream(directory)) {
            for(var file:files) {
                String name=file.getFileName().toString();
                if((name.matches("epub-[0-9]+\\.epub") || name.matches("archive-[0-9]+\\.zip"))
                        && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
            }
        }
    }

    private static void removeEmptyDirectory(Path directory) throws IOException {
        // The instance directory is never reused. Close its marker before deleting it (also works on Windows).
        // Do not remove unknown contents, subdirectories or links.
        try(var files=Files.newDirectoryStream(directory)) {
            for(var file:files) if(!file.getFileName().toString().equals(".lock")) return;
        }
        Files.deleteIfExists(directory.resolve(".lock"));
        Files.deleteIfExists(directory);
    }

    @Override public synchronized void close() {
        if(closed) return;
        closed=true;
        try {
            if(lock!=null) lock.release();
        } catch(IOException ex) { log.warn("No se pudo liberar el bloqueo temporal de descargas de Calibre"); }
        finally {
            try { if(channel!=null) channel.close(); }
            catch(IOException ex) { log.warn("No se pudo cerrar el almacenamiento temporal de descargas de Calibre"); }
        }
        cleanAbandoned();
    }
}
