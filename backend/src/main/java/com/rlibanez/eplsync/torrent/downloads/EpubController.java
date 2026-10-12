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
    public EpubController(EpubFiles files) { this.files=files; }
    @GetMapping("/{id}/epubs") public ResponseEntity<java.util.List<EpubFiles.Item>> list(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.list(id));
    }
    @GetMapping("/{id}/epubs/{index}")
    public void download(@PathVariable String id,@PathVariable int index,HttpServletResponse response) throws IOException {
        try(var opened=files.download(id,index)) {
            response.setContentType("application/epub+zip");
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(opened.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString());
            response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setContentLengthLong(opened.size());
            opened.stream().transferTo(response.getOutputStream());
        }
    }
}
