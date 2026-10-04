package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.dto.CatalogBookResponse;
import com.rlibanez.eplsync.model.CatalogBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CatalogDownloadViewService {
    private final DownloadRepository repository;
    public CatalogDownloadViewService(DownloadRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<CatalogBookResponse> enrich(List<CatalogBook> books) {
        var grouped = new HashMap<Long, List<DownloadRecord>>();
        // SQLite limita los parámetros: consultas agrupadas, nunca una consulta por libro.
        var ids = books.stream().map(value -> Objects.requireNonNull(value).getEplId()).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            repository.findByEplIdIn(ids.subList(start, Math.min(start + 500, ids.size())))
                    .forEach(row -> grouped.computeIfAbsent(row.getEplId(), ignored -> new ArrayList<>()).add(row));
        }
        var order = Comparator.comparing((DownloadRecord value) -> Objects.requireNonNull(value).getRevision()).reversed()
                .thenComparing(value -> Objects.requireNonNull(value).getCreatedAt(), Comparator.reverseOrder()).thenComparing(value -> Objects.requireNonNull(value).getId());
        return books.stream().map(book -> {
            var items = grouped.getOrDefault(book.getEplId(), List.of()).stream().sorted(order)
                    .map(row -> new CatalogBookResponse.DownloadItem(row.getId(), row.getRevision(), row.getStatus(), row.getCompletedAt() != null)).toList();
            return CatalogBookResponse.from(book, new CatalogBookResponse.Download(items));
        }).toList();
    }
}
