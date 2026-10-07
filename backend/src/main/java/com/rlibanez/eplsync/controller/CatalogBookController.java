package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.dto.CatalogBookResponse;
import com.rlibanez.eplsync.torrent.downloads.CatalogDownloadViewService;
import java.util.List;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.service.CatalogBookService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ')")
@RestController
@RequestMapping("/api/catalog/books")
public class CatalogBookController {

    private final CatalogBookService catalogBookService;

    private final CatalogDownloadViewService downloads;

    public CatalogBookController(CatalogBookService catalogBookService,
            CatalogDownloadViewService downloads) {
        this.downloads = downloads;
        this.catalogBookService = catalogBookService;
    }

    /**
     * Obtiene un libro del catálogo por su EPL Id.
     */
    @GetMapping("/{eplId}")
    public ResponseEntity<CatalogBookResponse> getByEplId(@PathVariable Long eplId) {
        return catalogBookService.getByEplId(eplId)
                .map(book -> ResponseEntity.ok(downloads.enrich(List.of(book)).getFirst()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ') and hasAuthority('BOOK_HISTORY_READ')")
    @GetMapping("/{eplId}/history")
    public ResponseEntity<?> history(@PathVariable Long eplId,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="revision,desc") String sort, jakarta.servlet.http.HttpServletRequest request) {
        com.rlibanez.eplsync.config.TableOrdering.validateParameters(request);
        sort = com.rlibanez.eplsync.config.TableOrdering.request(request,"revision,desc");
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if (catalogBookService.getByEplId(eplId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(downloads.history(eplId,page,size,sort));
    }

    /**
     * Búsqueda/listado PAGINADO (recomendado).
     *
     * Ejemplos:
     * /api/catalog/books?page=0&size=20
     * /api/catalog/books?author=brandon&language=en&sort=title,asc
     * /api/catalog/books?publicationYearFrom=2000&publicationYearTo=2010&size=50
     * http://localhost:8088/api/catalog/books?author=Brandon&page=2&size=50
     */
    @GetMapping
    public Object search(@Valid @ModelAttribute CatalogBookFilter filter,
            Pageable pageable,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            jakarta.servlet.http.HttpServletRequest request) {

        var names = new java.util.HashSet<String>(java.util.Set.of("page", "size", "sort", "eplid"));
        for (var property : org.springframework.beans.BeanUtils.getPropertyDescriptors(CatalogBookFilter.class)) {
            if (property.getWriteMethod() != null) names.add(property.getName());
        }
        for (String name : request.getParameterMap().keySet()) {
            if (!names.contains(name)) {
                String hint = names.stream().filter(known -> known.equalsIgnoreCase(name))
                        .findFirst().map(known -> "; utiliza " + known).orElse("");
                throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro desconocido: " + name + hint);
            }
        }
        // Validate both spellings, including repeated values, before applying the alias.
        var requestedIds = new java.util.LinkedHashSet<Long>();
        for (String name : java.util.List.of("eplId", "eplid")) {
            String[] values = request.getParameterValues(name);
            if (values == null) continue;
            for (String value : values) {
                long id;
                try {
                    id = Long.parseLong(value.trim());
                } catch (NumberFormatException ex) {
                    throw new com.rlibanez.eplsync.exception.UserInputException(name + " debe ser un entero mayor que cero");
                }
                if (id < 1) throw new com.rlibanez.eplsync.exception.UserInputException(name + " debe ser un entero mayor que cero");
                requestedIds.add(id);
            }
        }
        if (!requestedIds.isEmpty()) filter.setEplId(requestedIds.toArray(Long[]::new));

        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? com.rlibanez.eplsync.config.QueryLimits.DEFAULT_SIZE : size;
        com.rlibanez.eplsync.config.QueryLimits.page(pageNumber, pageSize);
        for (String key : java.util.List.of("page", "size")) {
            if (request.getParameterValues(key) != null && request.getParameterValues(key).length != 1)
                throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro repetido: " + key);
        }
        pageable = org.springframework.data.domain.PageRequest.of(pageNumber, pageSize, pageable.getSort());

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

        return new PageResponse<>(downloads.enrich(resultPage.getContent()), meta);
    }
}