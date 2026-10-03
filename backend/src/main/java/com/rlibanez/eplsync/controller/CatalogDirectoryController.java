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
        if (kind.equals("authors")) return authors(q, page, size);
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
    private PageResponse<Entry> authors(String q, int page, int size) {
        // Split before filtering/deduplicating/paginating, including existing imports.
        String cte = """
            WITH RECURSIVE author_parts(value, rest) AS (
              SELECT '', author || '&' FROM (SELECT DISTINCT author FROM catalog_books WHERE author IS NOT NULL)
              UNION ALL
              SELECT trim(substr(rest, 1, instr(rest, '&') - 1), char(9)||char(10)||char(13)||' '),
                     substr(rest, instr(rest, '&') + 1)
              FROM author_parts WHERE rest <> ''
            ), authors AS (
              SELECT DISTINCT value FROM author_parts
              WHERE value <> '' AND instr(lower(value), :query) > 0
            )
            """;
        String query = q.strip().toLowerCase(Locale.ROOT);
        long total = ((Number) em.createNativeQuery(cte + "SELECT count(*) FROM authors")
                .setParameter("query", query).getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<String> names = em.createNativeQuery(cte + "SELECT value FROM authors ORDER BY value LIMIT :size OFFSET :offset", String.class)
                .setParameter("query", query).setParameter("size", size).setParameter("offset", page * size).getResultList();
        int pages = (int) ((total + size - 1) / size);
        return new PageResponse<>(names.stream().map(Entry::new).toList(),
                new PageResponse.PageMeta(page, size, total, pages, page == 0, page + 1 >= pages, page + 1 < pages, page > 0));
    }

}
