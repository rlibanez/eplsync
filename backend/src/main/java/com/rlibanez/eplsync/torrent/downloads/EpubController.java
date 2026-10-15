package com.rlibanez.eplsync.torrent.downloads;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/api/torrent/downloads")
@PreAuthorize("hasAuthority('DOWNLOADS_FILES_READ') and (hasAuthority('TORRENT_SYNC') or hasAuthority('BOOK_HISTORY_READ'))")
public class EpubController {
    private final EpubFiles files;
    private final EpubBatches batches;
    // Shared by individual EPUB and ZIP responses. Reject immediately rather than occupying waiting HTTP threads.
    private final java.util.concurrent.Semaphore transfers=new java.util.concurrent.Semaphore(10);
    public EpubController(EpubFiles files,EpubBatches batches) { this.files=files; this.batches=batches; }
    public record Selection(java.util.List<String> ids) {}
    @PostMapping("/epub-batches")
    public ResponseEntity<EpubBatches.Ready> prepare(@RequestBody Selection selection,jakarta.servlet.http.HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.prepare(session.getId(),selection.ids()));
    }
    @GetMapping("/epub-batches/{token}")
    public void batch(@PathVariable String token,jakarta.servlet.http.HttpSession session,HttpServletResponse response) throws IOException {
        acquire(response);
        try { streamBatch(token,session,response); }
        finally { transfers.release(); }
    }
    private void streamBatch(String token,jakarta.servlet.http.HttpSession session,HttpServletResponse response) throws IOException {
        var plan=batches.take(session.getId(),token);
        files.validate(plan);
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        if(!plan.archive()) {
            try(var opened=files.open(plan.items().getFirst())) {
                response.setContentType("application/epub+zip");
                attachment(response,opened.name());response.setContentLengthLong(opened.size());
                opened.stream().transferTo(response.getOutputStream());
            }
        } else {
            response.setContentType("application/zip");attachment(response,"eplsync-epubs.zip");
            try(var zip=new java.util.zip.ZipOutputStream(response.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8)) {
                // EPUB is already compressed; avoid wasting CPU recompressing its content.
                zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                var names=new java.util.HashSet<String>();
                for(var entry:plan.items()) try(var opened=files.open(entry)) {
                    String name=uniqueName(opened.name(),names);
                    zip.putNextEntry(new java.util.zip.ZipEntry(name));
                    opened.stream().transferTo(zip);zip.closeEntry();
                }
            }
        }
    }
    private static void attachment(HttpServletResponse response,String name) {
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(name,java.nio.charset.StandardCharsets.UTF_8).build().toString());
    }
    static String uniqueName(String name,java.util.Set<String> names) {
        String stem=name.substring(0,name.length()-5);
        String candidate=name;
        for(int index=2; !names.add(candidate.toLowerCase(java.util.Locale.ROOT));index++) candidate=stem+" ("+index+").epub";
        return candidate;
    }
    @GetMapping("/{id}/epubs") public ResponseEntity<java.util.List<EpubFiles.Item>> list(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.list(id));
    }
    @GetMapping("/{id}/epubs/{index}")
    public void download(@PathVariable String id,@PathVariable int index,HttpServletResponse response) throws IOException {
        acquire(response);
        try(var opened=files.download(id,index)) {
            response.setContentType("application/epub+zip");
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(opened.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString());
            response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setContentLengthLong(opened.size());
            opened.stream().transferTo(response.getOutputStream());
        } finally { transfers.release(); }
    }
    private void acquire(HttpServletResponse response) {
        if(!transfers.tryAcquire()) {
            response.setHeader("Retry-After","5");
            response.setHeader("Cache-Control","no-store");
            throw new com.rlibanez.eplsync.exception.TorrentOperationException(HttpStatus.TOO_MANY_REQUESTS,
                    "Hay diez descargas de EPUB torrent en curso; inténtalo de nuevo en unos segundos");
        }
    }
}
