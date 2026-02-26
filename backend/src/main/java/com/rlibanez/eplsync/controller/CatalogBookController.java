package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.service.CatalogBookService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog/books")
public class CatalogBookController {

    private final CatalogBookService catalogBookService;

    public CatalogBookController(CatalogBookService catalogBookService) {
        this.catalogBookService = catalogBookService;
    }

    /**
     * Obtiene un libro del catálogo por su EPL Id.
     */
    @GetMapping("/{eplId}")
    public ResponseEntity<CatalogBook> getByEplId(@PathVariable Long eplId) {
        return catalogBookService.getByEplId(eplId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Búsqueda/listado PAGINADO (recomendado).
     *
     * Ejemplos:
     * /api/catalog/books?page=0&size=20
     * /api/catalog/books?author=brandon&language=INGLES&sort=title,asc
     * /api/catalog/books?publicationYearFrom=2000&publicationYearTo=2010&size=50
     * http://localhost:8080/api/catalog/books?author=Brandon&page=2&size=50
     */
    @GetMapping
    public Object search(@Valid @ModelAttribute CatalogBookFilter filter,
            Pageable pageable,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        // Si NO se especifica page/size => sin paginar
        if (page == null && size == null) {
            return catalogBookService.searchAll(filter);
        }

        // Si se especifica page o size => paginado
        Page<CatalogBook> resultPage = catalogBookService.search(filter, pageable);

        var meta = new PageResponse.PageMeta(
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.isFirst(),
                resultPage.isLast(),
                resultPage.hasNext(),
                resultPage.hasPrevious());

        return new PageResponse<>(resultPage.getContent(), meta);
    }
}