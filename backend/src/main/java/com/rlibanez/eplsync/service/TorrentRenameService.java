package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentRenameResult;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import com.rlibanez.eplsync.torrent.TorrentNameResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TorrentRenameService {
    private final CatalogBookRepository repository;
    private final MagnetLinkBuilder magnets;
    private final TorrentNameResolver names;
    private final TorrentProperties properties;
    private final TorrentClientService client;

    public TorrentRenameService(CatalogBookRepository repository, MagnetLinkBuilder magnets,
            TorrentNameResolver names, TorrentProperties properties, TorrentClientService client) {
        this.repository = repository;
        this.magnets = magnets;
        this.names = names;
        this.properties = properties;
        this.client = client;
    }

    public TorrentRenameResult rename(Long eplId, String hash) {
        client.requireRenameEnabled();
        if (!hash.matches("(?i)([0-9a-f]{40}|[a-z2-7]{32})")) {
            throw new com.rlibanez.eplsync.exception.UserInputException("El hash debe ser hexadecimal de 40 caracteres o Base32 de 32");
        }
        var book = repository.findById(eplId).orElseThrow(() ->
                new TorrentOperationException(HttpStatus.NOT_FOUND, "El libro no existe en el catálogo"));
        String normalizedHash = magnets.hashes(hash).getFirst();
        if (!magnets.hashes(book.getLinks()).contains(normalizedHash)) {
            throw new com.rlibanez.eplsync.exception.UserInputException("El hash no pertenece a los enlaces del libro");
        }
        String name = names.resolve(properties.getRename().getPattern(), book);
        client.renameTorrent(normalizedHash, name);
        return new TorrentRenameResult(eplId, normalizedHash, name, properties.getClient());
    }
}
