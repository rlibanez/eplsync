package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class CatalogBookService {
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.specification.CatalogLibraryFilters libraryFilters;

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
        com.rlibanez.eplsync.config.QueryLimits.page(pageable.getPageNumber(), pageable.getPageSize());
        var spec = CatalogBookSpecifications.fromFilter(filter);
        if(filter!=null && filter.hasLibraryFilters()) spec=spec.and(libraryFilters.fromFilter(filter));
        return repository.findAll(com.rlibanez.eplsync.ordering.TextOrdering.sorted(spec, CatalogOrdering.normalize(pageable.getSort())), org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize()));
    }

}
