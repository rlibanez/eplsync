package com.rlibanez.eplsync.settings;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
import jakarta.servlet.http.*;

@RestController
@RequestMapping("/api/calibre/epub-downloads")
@PreAuthorize("hasAuthority('CALIBRE_READ') and hasAuthority('CALIBRE_FILES_READ')")
public class CalibreEpubController {
    private final CalibreEpubDownloads downloads;
    public CalibreEpubController(CalibreEpubDownloads downloads) { this.downloads=downloads; }
    public record Selection(List<Long> ids) {}
    @PostMapping public ResponseEntity<CalibreEpubDownloads.Ready> prepare(@RequestBody Selection selection,HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(downloads.prepare(session.getId(),selection.ids()));
    }
    @GetMapping("/{token}") public void download(@PathVariable String token,HttpSession session,HttpServletResponse response) throws java.io.IOException {
        downloads.download(session.getId(),token,response);
    }
}
