package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.torrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TorrentDownloadService {
    private final CatalogBookRepository repository;
    private final TorrentProperties properties;
    private final MagnetLinkBuilder magnets;
    private final TorrentNameResolver names;
    private final TorrentClientService client;

    public TorrentDownloadService(CatalogBookRepository repository, TorrentProperties properties,
            MagnetLinkBuilder magnets, TorrentNameResolver names, TorrentClientService client) {
        this.repository = repository;
        this.properties = properties;
        this.magnets = magnets;
        this.names = names;
        this.client = client;
    }

    public TorrentDownloadResult download(Long eplId, TorrentDownloadRequest request) {
        client.requireEnabled();
        var book = repository.findById(eplId).orElseThrow(() ->
                new TorrentOperationException(HttpStatus.NOT_FOUND, "El libro no existe en el catálogo"));
        var command = prepare(book, request);
        var status = client.addTorrent(command);
        return new TorrentDownloadResult(eplId, command.hash(), properties.getClient(), status);
    }

    public TorrentDownload prepare(com.rlibanez.eplsync.model.CatalogBook book, TorrentDownloadRequest request) {
        Long eplId = book.getEplId();
        var hashes = magnets.hashes(book.getLinks());
        if (hashes.isEmpty()) throw new TorrentOperationException(HttpStatus.UNPROCESSABLE_CONTENT,
                "El libro no tiene hashes torrent válidos");
        var options = request == null ? new TorrentDownloadRequest(null, null, null, null, null) : request;
        String hash;
        if (options.hash() == null) {
            if (hashes.size() != 1) throw new TorrentOperationException(HttpStatus.CONFLICT,
                    "El libro tiene varios torrents; especifica hash en la petición");
            hash = hashes.getFirst();
        } else {
            if (!options.hash().matches("(?i)([0-9a-f]{40}|[a-z2-7]{32})"))
                throw new IllegalArgumentException("Hash torrent inválido");
            hash = magnets.hashes(options.hash()).getFirst();
            if (!hashes.contains(hash)) throw new IllegalArgumentException("El hash no pertenece al libro");
        }
        var rename = options.rename();
        boolean renameEnabled = rename != null && rename.enabled() != null
                ? rename.enabled() : properties.getRename().isEnabled();
        String pattern = rename != null && rename.pattern() != null
                ? rename.pattern() : properties.getRename().getPattern();
        String name = renameEnabled ? names.resolve(pattern, book) : null;
        boolean start = options.start() == null ? properties.getDownload().isStart() : options.start();
        String savePath = options.savePath() == null ? properties.getDownload().getSavePath() : options.savePath();
        return client.withDefaults(new TorrentDownload(hash, magnets.build(hash, eplId, book.getTitle()),
                start, savePath, name, options.qbittorrent(), book));
    }
}
