package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class CatalogMagnetService {
    private final CatalogBookRepository repository;
    private final MagnetLinkBuilder builder;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    public CatalogMagnetService(CatalogBookRepository repository, MagnetLinkBuilder builder) {
        this.repository = repository;
        this.builder = builder;
    }

    public Optional<List<String>> forBook(Long eplId) {
        return repository.findById(eplId).map(book -> builder.hashes(book.getLinks()).stream()
                .map(hash -> builder.build(hash, book.getEplId(), book.getTitle())).toList());
    }

    /** A projection cursor and SQLite temporary table keep deduplication off the Java heap. */
    public com.rlibanez.eplsync.dto.PageResponse<String> page(CatalogBookFilter filter, Sort sort, int page, int size) {
        com.rlibanez.eplsync.config.QueryLimits.page(page, size);
        Sort ordering = CatalogOrdering.normalize(sort);
        var spec = CatalogBookSpecifications.fromFilter(filter);
        var cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(com.rlibanez.eplsync.model.CatalogBook.class);
        query.select(cb.tuple(root.get("eplId"), root.get("title"), root.get("links")));
        query.where(spec.toPredicate(root, query, cb));
        query.orderBy(ordering.stream().map(order -> com.rlibanez.eplsync.ordering.TextOrdering.order(cb,root,order)).toList());
        return em.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            String table = "magnet_page_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try (var ddl = connection.createStatement()) {
                ddl.execute("CREATE TEMP TABLE " + table + " (hash TEXT UNIQUE NOT NULL, position INTEGER PRIMARY KEY, epl_id INTEGER, title TEXT)");
                try {
                    try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO " + table + " VALUES(?,?,?,?)");
                         var rows = em.createQuery(query).getResultStream()) {
                        var iterator = rows.iterator();
                        long position = 0;
                        while (iterator.hasNext()) {
                            var row = iterator.next();
                            for (String hash : builder.hashes(row.get(2, String.class))) {
                                insert.setString(1, hash); insert.setLong(2, position++);
                                insert.setLong(3, row.get(0, Long.class)); insert.setString(4, row.get(1, String.class));
                                insert.executeUpdate();
                            }
                        }
                    }
                    long total;
                    try (var count = ddl.executeQuery("SELECT count(*) FROM " + table)) { count.next(); total = count.getLong(1); }
                    var items = new java.util.ArrayList<String>();
                    try (var select = connection.prepareStatement("SELECT hash,epl_id,title FROM " + table + " ORDER BY position LIMIT ? OFFSET ?")) {
                        select.setInt(1, size); select.setLong(2, (long) page * size);
                        try (var rows = select.executeQuery()) {
                            while (rows.next()) items.add(builder.build(rows.getString(1), rows.getLong(2), rows.getString(3)));
                        }
                    }
                    int pages = (int) ((total + size - 1) / size);
                    return new com.rlibanez.eplsync.dto.PageResponse<>(items,
                            new com.rlibanez.eplsync.dto.PageResponse.PageMeta(page, size, total, pages,
                                    page == 0, page >= pages - 1, page < pages - 1, page > 0));
                } finally { ddl.execute("DROP TABLE " + table); }
            }
        });
    }

}
