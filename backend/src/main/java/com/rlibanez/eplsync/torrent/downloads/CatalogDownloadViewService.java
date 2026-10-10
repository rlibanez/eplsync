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
        if (!com.rlibanez.eplsync.security.Permission.has(com.rlibanez.eplsync.security.Permission.BOOK_HISTORY_READ))
            return books.stream().map(book -> CatalogBookResponse.from(book, new CatalogBookResponse.Download(List.of()))).toList();
        var grouped = new HashMap<Long, List<CatalogBookResponse.DownloadItem>>();
        var totals = new HashMap<Long, Long>();
        var statuses=new HashMap<Long,List<DownloadStatus>>();
        var ids = books.stream().map((CatalogBook entryValue) -> java.util.Objects.requireNonNull(entryValue).getEplId()).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            for(var row:repository.historyStatuses(ids.subList(start,Math.min(start+500,ids.size()))))
                statuses.computeIfAbsent(row.getEplId(),ignored -> new ArrayList<>()).add(DownloadStatus.valueOf(row.getStatus()));
            for (var row : repository.historyWindow(ids.subList(start, Math.min(start+500,ids.size())),20)) {
                grouped.computeIfAbsent(row.getEplId(),ignored -> new ArrayList<>()).add(new CatalogBookResponse.DownloadItem(
                        row.getId(),row.getRevision(),DownloadStatus.valueOf(row.getStatus()),row.getCompleted()==1));
                totals.put(row.getEplId(),row.getTotal());
            }
        }
        return books.stream().map(book -> CatalogBookResponse.from(book,new CatalogBookResponse.Download(
                grouped.getOrDefault(book.getEplId(),List.of()),totals.getOrDefault(book.getEplId(),0L),statuses.getOrDefault(book.getEplId(),List.of())))).toList();
    }
    public record HistoryItem(String id, Double revision, DownloadStatus status, boolean completed,
            String hash, String client, String clientInstanceId, DownloadRecord.Origin origin,
            java.time.Instant lastCheckedAt, java.time.Instant completedAt, String lastError) {}
    @Transactional(readOnly=true)
    public com.rlibanez.eplsync.dto.PageResponse<HistoryItem> history(Long id,int page,int size) {
        return history(id,page,size,"revision,desc");
    }
    @Transactional(readOnly=true)
    public com.rlibanez.eplsync.dto.PageResponse<HistoryItem> history(Long id,int page,int size,String sort) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        var fields=Set.of("hash","revision","status","client","lastCheckedAt","completedAt","lastError");
        var orders = new ArrayList<>(com.rlibanez.eplsync.config.TableOrdering.parse(sort,fields));
        orders.add(org.springframework.data.domain.Sort.Order.desc("createdAt"));
        orders.add(org.springframework.data.domain.Sort.Order.asc("id"));
        var ordering=org.springframework.data.domain.Sort.by(orders);
        var result=repository.findAll(com.rlibanez.eplsync.ordering.TextOrdering.sorted((root,query,cb)->cb.equal(root.get("eplId"),id),ordering),org.springframework.data.domain.PageRequest.of(page,size));
        return new com.rlibanez.eplsync.dto.PageResponse<>(result.getContent().stream().map(row -> new HistoryItem(
                row.getId(),row.getRevision(),row.getStatus(),row.getCompletedAt()!=null,row.getHash(),row.getClient(),
                row.getClientInstanceId(),row.getOrigin(),row.getLastCheckedAt(),row.getCompletedAt(),row.getLastError())).toList(),
                new com.rlibanez.eplsync.dto.PageResponse.PageMeta(page,size,result.getTotalElements(),result.getTotalPages(),
                        result.isFirst(),result.isLast(),result.hasNext(),result.hasPrevious()));
    }
}
