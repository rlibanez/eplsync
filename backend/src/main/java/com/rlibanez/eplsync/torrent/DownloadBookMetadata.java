package com.rlibanez.eplsync.torrent;

import jakarta.persistence.EntityManager;
import java.util.*;

/** Only display metadata for the bounded result page; never fetch full catalog entities. */
public final class DownloadBookMetadata {
    private DownloadBookMetadata() {}
    public record Book(String title, String coverUrl, Boolean coverAvailable) {}
    public static Map<Long, Book> load(EntityManager em, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<Long, Book>();
        var unique = new ArrayList<>(new LinkedHashSet<>(ids));
        // Keep bind counts below SQLite limits even for the maximum page size.
        for (int offset = 0; offset < unique.size(); offset += 250) {
            var rows = em.createQuery("select b.eplId, b.title, b.coverUrl, b.coverAvailable from CatalogBook b where b.eplId in :ids", Object[].class)
                .setParameter("ids", unique.subList(offset, Math.min(offset + 250, unique.size()))).getResultList();
            for (var row : rows) result.put((Long) row[0], new Book((String) row[1], (String) row[2], (Boolean) row[3]));
        }
        return result;
    }
}
