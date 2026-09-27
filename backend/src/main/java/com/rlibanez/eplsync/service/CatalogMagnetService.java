package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.repository.CatalogMagnetSource;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class CatalogMagnetService {
    private final CatalogBookRepository repository;
    private final MagnetLinkBuilder builder;

    public CatalogMagnetService(CatalogBookRepository repository, MagnetLinkBuilder builder) {
        this.repository = repository;
        this.builder = builder;
    }

    public Optional<List<String>> forBook(Long eplId) {
        return repository.findById(eplId).map(book -> builder.hashes(book.getLinks()).stream()
                .map(hash -> builder.build(hash, book.getEplId(), book.getTitle())).toList());
    }

    public List<String> search(CatalogBookFilter filter, Sort sort) {
        // Desempate estable: si varios libros comparten hash, se conserva el primero.
        Sort stableSort = sort.getOrderFor("eplId") == null ? sort.and(Sort.by("eplId")) : sort;
        var books = repository.findBy(CatalogBookSpecifications.fromFilter(filter),
                query -> query.as(CatalogMagnetSource.class).sortBy(stableSort).all());
        var magnets = new LinkedHashMap<String, String>();
        for (var book : books) {
            for (String hash : builder.hashes(book.getLinks())) {
                magnets.computeIfAbsent(hash, key -> builder.build(key, book.getEplId(), book.getTitle()));
            }
        }
        return List.copyOf(magnets.values());
    }
}
