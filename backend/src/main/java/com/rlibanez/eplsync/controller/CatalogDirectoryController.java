package com.rlibanez.eplsync.controller;

import java.util.Objects;

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
    private static final Map<String,String> FIELDS = Map.of("authors","author","collections","collection","languages","language","genres","genres","years","publicationYear");
    public record Entry(String value, String initial) {
        public Entry(String value) { this(value, null); }
    }
    public CatalogDirectoryController(EntityManager em) { this.em = em; }
    public PageResponse<Entry> list(String kind, String q, int page, int size) {
        return list(kind, q, page, size, "");
    }
    @GetMapping("/{kind}")
    @Transactional(readOnly = true)
    public PageResponse<Entry> list(@PathVariable String kind, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="") String initial) {
        String field = FIELDS.get(kind);
        if (field == null || page < 0 || !List.of(10,20,50,100,200,500,1000).contains(size)
                || (long)page * size > Integer.MAX_VALUE || q.length() > 512)
            throw new IllegalArgumentException("Directorio, página, tamaño o búsqueda inválidos");
        if (!initial.isEmpty() && !initial.matches("[A-ZÑ#]"))
            throw new IllegalArgumentException("Inicial inválida");
        if (kind.equals("authors") || kind.equals("genres") || kind.equals("collections"))
            return alphabeticalValues(field, kind.equals("authors") ? "&" : kind.equals("genres") ? "," : null, q, page, size, initial);
        if (!initial.isEmpty()) throw new IllegalArgumentException("Este directorio no admite iniciales");
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
    private static final String LETTERS = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ#";
    private static final java.util.regex.Pattern MARKS = java.util.regex.Pattern.compile("\\p{M}+");
    private static String normalize(String value) {
        // Keep Ñ distinct while folding accents on other letters.
        String protectedValue = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).toUpperCase(Locale.ROOT).replace("Ñ", "\uE000");
        return MARKS.matcher(java.text.Normalizer.normalize(protectedValue, java.text.Normalizer.Form.NFD))
                .replaceAll("").replace("\uE000", "Ñ");
    }
    private static String initial(String value) {
        String normalized = normalize(value);
        String first = normalized.isEmpty() ? "#" : normalized.substring(0, 1);
        return LETTERS.contains(first) ? first : "#";
    }
    private PageResponse<Entry> alphabeticalValues(String field, String separator, String q, int page, int size, String initial) {
        // Only project distinct names, never load full books. Unicode normalization
        // and Spanish collation happen before filtering and pagination.
        String sql = separator == null
            ? "SELECT DISTINCT trim(collection) FROM catalog_books WHERE collection IS NOT NULL"
            : """
            WITH RECURSIVE parts(value, rest) AS (
              SELECT '', %s || :separator FROM (SELECT DISTINCT %s FROM catalog_books WHERE %s IS NOT NULL)
              UNION ALL
              SELECT trim(substr(rest, 1, instr(rest, :separator) - 1), char(9)||char(10)||char(13)||' '),
                     substr(rest, instr(rest, :separator) + 1)
              FROM parts WHERE rest <> ''
            ) SELECT DISTINCT value FROM parts WHERE value <> ''
            """.formatted(field, field, field);
        var query = em.createNativeQuery(sql, String.class);
        if (separator != null) query.setParameter("separator", separator);
        @SuppressWarnings("unchecked")
        List<String> names = query.getResultList();
        var collator = java.text.Collator.getInstance(Locale.forLanguageTag("es"));
        String search = normalize(q.strip());
        var entries = names.stream().map(value -> Objects.requireNonNull(value).strip()).filter(value -> !value.isEmpty()).distinct()
                .map(value -> new Entry(value, initial(value)))
                .filter(entry -> (initial.isEmpty() || initial.equals(entry.initial())) && normalize(entry.value()).contains(search))
                .sorted(java.util.Comparator.comparingInt((Entry entry) -> LETTERS.indexOf(entry.initial()))
                    .thenComparing(value -> Objects.requireNonNull(value).value(), collator).thenComparing(value -> Objects.requireNonNull(value).value()))
                .toList();
        long total = entries.size();
        int pages = (int) ((total + size - 1) / size);
        return new PageResponse<>(entries.stream().skip((long) page * size).limit(size).toList(),
                new PageResponse.PageMeta(page, size, total, pages, page == 0, page + 1 >= pages, page + 1 < pages, page > 0));
    }
}
