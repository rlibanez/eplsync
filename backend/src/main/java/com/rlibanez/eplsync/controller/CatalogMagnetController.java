package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.service.CatalogMagnetService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
public class CatalogMagnetController {
    private final CatalogMagnetService service;

    public CatalogMagnetController(CatalogMagnetService service) {
        this.service = service;
    }

    @GetMapping(value = "/books/{eplId}/magnets", produces = "application/json")
    public ResponseEntity<List<String>> forBook(@PathVariable Long eplId) {
        return service.forBook(eplId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/magnets", produces = "application/json")
    public Object search(@Valid @ModelAttribute CatalogBookFilter filter, Sort sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page == null && size == null) return service.search(filter, sort);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1) {
            throw new IllegalArgumentException("page debe ser >= 0 y size debe ser > 0");
        }
        var magnets = service.search(filter, sort);
        long offset = (long) pageNumber * pageSize;
        int from = (int) Math.min(offset, magnets.size());
        int to = (int) Math.min(offset + pageSize, magnets.size());
        var result = new PageImpl<>(magnets.subList(from, to),
                PageRequest.of(pageNumber, pageSize), magnets.size());
        return new PageResponse<>(result.getContent(), new PageResponse.PageMeta(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.isFirst(), result.isLast(), result.hasNext(), result.hasPrevious()));
    }

    @GetMapping(value = "/magnets/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> export(@Valid @ModelAttribute CatalogBookFilter filter, Sort sort) {
        var magnets = service.search(filter, sort);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"magnets.txt\"")
                .body(magnets.isEmpty() ? "" : String.join("\n", magnets) + "\n");
    }
}
