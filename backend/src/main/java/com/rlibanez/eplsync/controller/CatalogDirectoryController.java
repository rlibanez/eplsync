package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.model.enums.Language;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CATALOG_READ')")
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
    public PageResponse<Entry> list(String kind, String q, int page, int size, String initial) {
        return list(kind, q, page, size, initial, null);
    }
    @GetMapping("/{kind}")
    @Transactional(readOnly = true)
    public PageResponse<Entry> list(@PathVariable String kind, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="") String initial, @RequestParam(required=false) Integer century) {
        if (century != null && (!kind.equals("years") || century < 0 || century > 21))
            throw new com.rlibanez.eplsync.exception.UserInputException("Siglo inválido: usa 0 para años <= 0 o un siglo entre 1 y 21");
        String field = FIELDS.get(kind);
        if (field == null || page < 0 || !List.of(10,20,50,100,200,500,1000).contains(size)
                || (long)page * size > Integer.MAX_VALUE || q.length() > 512)
            throw new com.rlibanez.eplsync.exception.UserInputException("Directorio, página, tamaño o búsqueda inválidos");
        if (!initial.isEmpty() && !initial.matches("[A-ZÑ#]"))
            throw new com.rlibanez.eplsync.exception.UserInputException("Inicial inválida");
        if (kind.equals("authors") || kind.equals("genres") || kind.equals("collections"))
            return alphabeticalValues(field, kind.equals("authors") ? "&" : kind.equals("genres") ? "," : null, q, page, size, initial);
        if (!initial.isEmpty()) throw new com.rlibanez.eplsync.exception.UserInputException("Este directorio no admite iniciales");
        String expr = "b." + field;
        String where = " from CatalogBook b where " + expr + " is not null and trim(cast(" + expr + " as String)) <> ''";
        if (!q.isBlank()) where += " and locate(:q, lower(cast(" + expr + " as String))) > 0";
        if (century != null) where += century == 0 ? " and b.publicationYear <= 0"
            : " and b.publicationYear between :yearFrom and :yearTo";
        var count = em.createQuery("select count(distinct " + expr + ")" + where, Long.class);
        var rows = em.createQuery("select distinct " + expr + where + " order by " + expr + " asc", Object.class);
        if (!q.isBlank()) { count.setParameter("q", q.strip().toLowerCase(Locale.ROOT)); rows.setParameter("q", q.strip().toLowerCase(Locale.ROOT)); }
        if (century != null && century > 0) {
            int from = (century - 1) * 100 + 1, to = century * 100;
            count.setParameter("yearFrom", from).setParameter("yearTo", to);
            rows.setParameter("yearFrom", from).setParameter("yearTo", to);
        }
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
            ? "SELECT DISTINCT trim(collection) AS value FROM catalog_books WHERE collection IS NOT NULL"
            : """
            WITH RECURSIVE parts(value, rest) AS (
              SELECT '', %s || :separator FROM (SELECT DISTINCT %s FROM catalog_books WHERE %s IS NOT NULL)
              UNION ALL
              SELECT trim(substr(rest, 1, instr(rest, :separator) - 1), char(9)||char(10)||char(13)||' '),
                     substr(rest, instr(rest, :separator) + 1)
              FROM parts WHERE rest <> ''
            ) SELECT DISTINCT value FROM parts WHERE value <> ''
            """.formatted(field, field, field);
        // SQLite applies Unicode filtering and Spanish collation before LIMIT/OFFSET.
        // Functions are registered on the transaction's connection, including pooled connections.
        em.unwrap(org.hibernate.Session.class).doWork(connection -> {
            var sqlite = connection.unwrap(org.sqlite.SQLiteConnection.class);
            org.sqlite.Function.create(sqlite, "epl_strip", new org.sqlite.Function() {
                @Override protected void xFunc() throws java.sql.SQLException { result(value_text(0).strip()); }
            }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
            org.sqlite.Function.create(sqlite, "epl_normalize", new org.sqlite.Function() {
                @Override protected void xFunc() throws java.sql.SQLException { result(normalize(value_text(0))); }
            }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
            org.sqlite.Function.create(sqlite, "epl_initial", new org.sqlite.Function() {
                @Override protected void xFunc() throws java.sql.SQLException { result(initial(value_text(0))); }
            }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
            org.sqlite.Collation.create(sqlite, "epl_spanish", new org.sqlite.Collation() {
                private final java.text.Collator collator = java.text.Collator.getInstance(Locale.forLanguageTag("es"));
                @Override protected int xCompare(String left, String right) { return collator.compare(left, right); }
            });
        });
        String filtered = "WITH names AS (" + sql + "), entries AS (SELECT DISTINCT epl_strip(value) AS value FROM names WHERE epl_strip(value) <> '') "
                + "SELECT %s FROM entries WHERE instr(epl_normalize(value), :search) > 0 "
                + "AND (:initial = '' OR epl_initial(value) = :initial)";
        var count = em.createNativeQuery(filtered.formatted("count(*)"), Long.class);
        var rows = em.createNativeQuery(filtered.formatted("value")
                + " ORDER BY instr('" + LETTERS + "', epl_initial(value)), value COLLATE epl_spanish, value", String.class);
        for (var query : java.util.List.of(count, rows)) {
            if (separator != null) query.setParameter("separator", separator);
            query.setParameter("search", normalize(q.strip())); query.setParameter("initial", initial);
        }
        long total = ((Number) count.getSingleResult()).longValue();
        java.util.List<?> values = rows.setFirstResult(page * size).setMaxResults(size).getResultList();
        var items = values.stream().map(value -> {
            String name = (String) value;
            return new Entry(name, initial(name));
        }).toList();
        int pages = (int) ((total + size - 1) / size);
        return new PageResponse<>(items, new PageResponse.PageMeta(page, size, total, pages,
                page == 0, page >= pages - 1, page < pages - 1, page > 0));
    }
}
