package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/torrent/books")
public class TorrentDownloadController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TorrentDownloadController.class);
    private final TorrentDownloadService service;

    public TorrentDownloadController(TorrentDownloadService service) { this.service = service; }

    @PostMapping("/{eplId}")
    public ResponseEntity<TorrentDownloadResult> downloadByEplId(@PathVariable Long eplId,
            @RequestBody(required = false) TorrentDownloadRequest request) {
        log.info("Solicitud de envío torrent: eplId={}", eplId);
        TorrentDownloadResult result;
        try { result = service.download(eplId, request); }
        catch (RuntimeException ex) {
            // Solo mensajes controlados; no volcar peticiones, credenciales ni excepciones arbitrarias.
            String reason = ex instanceof com.rlibanez.eplsync.exception.TorrentOperationException
                    || ex instanceof com.rlibanez.eplsync.exception.TorrentConnectionException
                    ? ex.getMessage() : ex instanceof IllegalArgumentException
                    ? "Opciones del torrent inválidas" : "Error inesperado al enviar el torrent";
            if (reason == null) reason = "Sin detalle";
            reason = reason.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
            log.warn("Fallo de envío individual: eplId={}, tipo={}, motivo={}", eplId,
                    ex.getClass().getSimpleName(), reason.substring(0, Math.min(500, reason.length())));
            throw ex;
        }
        log.trace("Resultado de envío torrent: eplId={}, hash={}, status={}", eplId, result.hash(), result.status());
        return ResponseEntity.status(result.status() == TorrentDownloadResult.Status.ALREADY_EXISTS ? 200 : 202)
                .body(result);
    }
}
