package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.model.enums.Language;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog/directory")
public class CatalogDirectoryController {
    private final EntityManager em;
    private static final Map<String,String> FIELDS = Map.of("authors","author","languages","language","genres","genres","years","publicationYear");
    public record Entry(String value) {}
    public CatalogDirectoryController(EntityManager em) { this.em = em; }
    @GetMapping("/{kind}")
    @Transactional(readOnly = true)
    public PageResponse<Entry> list(@PathVariable String kind, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        String field = FIELDS.get(kind);
        if (field == null || page < 0 || !List.of(10,20,50,100,200,500,1000).contains(size)
                || (long)page * size > Integer.MAX_VALUE || q.length() > 512)
            throw new IllegalArgumentException("Directorio, página, tamaño o búsqueda inválidos");
        String expr = "b." + field;
        String where = " from CatalogBook b where " + expr + " is not null and trim(cast(" + expr + " as String)) <> ''";
        if (!q.isBlank()) where += " and locate(:q, lower(cast(" + expr + " as String))) > 0";
        var count = em.createQuery("select count(distinct " + expr + ")" + where, Long.class);
        var rows = em.createQuery("select distinct " + expr + where + " order by " + expr + (kind.equals("years") ? " desc" : " asc"), Object.class);
        if (!q.isBlank()) { count.setParameter("q", q.strip().toLowerCase(Locale.ROOT)); rows.setParameter("q", q.strip().toLowerCase(Locale.ROOT)); }
        long total = count.getSingleResult();
        var items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(value -> new Entry(value instanceof Language language ? language.getIsoCode() : value.toString())).toList();
        int pages = (int)((total + size - 1) / size);
        return new PageResponse<>(items, new PageResponse.PageMeta(page,size,total,pages,page==0,page+1>=pages,page+1<pages,page>0));
    }
}
