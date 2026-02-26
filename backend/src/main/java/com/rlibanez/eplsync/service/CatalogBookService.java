package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CatalogBookService {

    private final CatalogBookRepository repository;

    public CatalogBookService(CatalogBookRepository repository) {
        this.repository = repository;
    }

    /**
     * Obtiene un libro del catálogo por EPL Id.
     */
    @Transactional(readOnly = true)
    public Optional<CatalogBook> getByEplId(Long eplId) {
        return repository.findById(eplId);
    }

    /**
     * Listado/búsqueda paginada.
     */
    @Transactional(readOnly = true)
    public Page<CatalogBook> search(CatalogBookFilter filter, Pageable pageable) {
        var spec = CatalogBookSpecifications.fromFilter(filter);
        return repository.findAll(spec, pageable);
    }

    /**
     * Listado/búsqueda sin paginar.
     */
    @Transactional(readOnly = true)
    public List<CatalogBook> searchAll(CatalogBookFilter filter) {
        var spec = CatalogBookSpecifications.fromFilter(filter);
        return repository.findAll(spec);
    }
}