package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.dto.CatalogBookResponse;
import com.rlibanez.eplsync.model.CatalogBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CatalogDownloadViewService {
    private final DownloadRepository repository;
    @org.springframework.beans.factory.annotation.Autowired private DownloadTrackingService tracking;
    @org.springframework.beans.factory.annotation.Autowired private DownloadQueryService queries;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    public CatalogDownloadViewService(DownloadRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<CatalogBookResponse> enrich(List<CatalogBook> books) {
        if (!com.rlibanez.eplsync.security.Permission.has(com.rlibanez.eplsync.security.Permission.BOOK_HISTORY_READ))
            return books.stream().map(book -> CatalogBookResponse.from(book, new CatalogBookResponse.Download(List.of()))).toList();
        var latest=new HashMap<Long,CatalogBookResponse.DownloadItem>();
        var grouped = new HashMap<Long, List<CatalogBookResponse.DownloadItem>>();
        var totals = new HashMap<Long, Long>();
        var statuses=new HashMap<Long,List<DownloadStatus>>();
        var ids = books.stream().map((CatalogBook entryValue) -> java.util.Objects.requireNonNull(entryValue).getEplId()).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            if(tracking!=null) for(var row:repository.latestPresent(ids.subList(start,Math.min(start+500,ids.size())),tracking.instanceId()))
                latest.put(row.getEplId(),new CatalogBookResponse.DownloadItem(row.getId(),row.getRevision(),DownloadStatus.valueOf(row.getStatus()),row.getCompleted()==1));
            for(var row:repository.historyStatuses(ids.subList(start,Math.min(start+500,ids.size()))))
                statuses.computeIfAbsent(row.getEplId(),ignored -> new ArrayList<>()).add(DownloadStatus.valueOf(row.getStatus()));
            for (var row : repository.historyWindow(ids.subList(start, Math.min(start+500,ids.size())),20)) {
                grouped.computeIfAbsent(row.getEplId(),ignored -> new ArrayList<>()).add(new CatalogBookResponse.DownloadItem(
                        row.getId(),row.getRevision(),DownloadStatus.valueOf(row.getStatus()),row.getCompleted()==1));
                totals.put(row.getEplId(),row.getTotal());
            }
        }
        return books.stream().map(book -> CatalogBookResponse.from(book,new CatalogBookResponse.Download(
                grouped.getOrDefault(book.getEplId(),List.of()),totals.getOrDefault(book.getEplId(),0L),statuses.getOrDefault(book.getEplId(),List.of()),latest.get(book.getEplId())))).toList();
    }
    public record HistoryItem(String id, Double revision, DownloadStatus status, boolean completed,
            String hash, String client, String clientInstanceId, DownloadRecord.Origin origin,
            java.time.Instant lastCheckedAt, java.time.Instant completedAt, String lastError, List<String> fileExtensions,String inventoryState,String inventoryCheckedAt) {}
    @Transactional(readOnly=true)
    public com.rlibanez.eplsync.dto.PageResponse<HistoryItem> history(Long id,int page,int size) {
        return history(id,page,size,"revision,desc");
    }
    @Transactional(readOnly=true)
    public com.rlibanez.eplsync.dto.PageResponse<HistoryItem> history(Long id,int page,int size,String sort) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        var fields=Set.of("fileExtensions","hash","revision","status","client","lastCheckedAt","completedAt","lastError");
        var orders = new ArrayList<>(com.rlibanez.eplsync.config.TableOrdering.parse(sort,fields));
        orders.replaceAll(order->order.getProperty().equals("fileExtensions")?new org.springframework.data.domain.Sort.Order(order.getDirection(),"extensionsSort"):order);
        orders.add(org.springframework.data.domain.Sort.Order.desc("createdAt"));
        orders.add(org.springframework.data.domain.Sort.Order.asc("id"));
        var ordering=org.springframework.data.domain.Sort.by(orders);
        var result=repository.findAll(com.rlibanez.eplsync.ordering.TextOrdering.sorted((root,query,cb)->cb.equal(root.get("eplId"),id),ordering),org.springframework.data.domain.PageRequest.of(page,size));
        queries.loadExtensions(result.getContent());
        return new com.rlibanez.eplsync.dto.PageResponse<>(result.getContent().stream().map(row -> new HistoryItem(
                row.getId(),row.getRevision(),row.getStatus(),row.getCompletedAt()!=null,row.getHash(),row.getClient(),
                row.getClientInstanceId(),row.getOrigin(),row.getLastCheckedAt(),row.getCompletedAt(),row.getLastError(),row.getFileExtensions(),row.getInventoryState(),row.getInventoryCheckedAt())).toList(),
                new com.rlibanez.eplsync.dto.PageResponse.PageMeta(page,size,result.getTotalElements(),result.getTotalPages(),
                        result.isFirst(),result.isLast(),result.hasNext(),result.hasPrevious()));
    }
    public record InventoryFile(int index,String name,String extension,long size) {}
    public record Inventory(String inventoryState,String inventoryCheckedAt,List<InventoryFile> items,com.rlibanez.eplsync.dto.PageResponse.PageMeta meta) {}
    @Transactional(readOnly=true)
    public Inventory inventory(Long eplId,String recordId,int page,int size) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if(recordId==null || recordId.isBlank() || recordId.length()>128) throw new com.rlibanez.eplsync.exception.UserInputException("Registro de descarga inválido");
        var records=jdbc.queryForList("SELECT inventory_state,inventory_checked_at FROM torrent_downloads WHERE id=? AND epl_id=?",recordId,eplId);
        if(records.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Registro de descarga no encontrado");
        long total=jdbc.queryForObject("SELECT count(*) FROM torrent_download_files WHERE download_id=?",Long.class,recordId),pages=(total+size-1)/size;
        var items=jdbc.query("SELECT file_index,relative_path,extension,size FROM torrent_download_files WHERE download_id=? ORDER BY file_index LIMIT ? OFFSET ?",(r,n)->new InventoryFile(r.getInt(1),r.getString(2),r.getString(3),r.getLong(4)),recordId,size,(long)page*size);
        return new Inventory((String)records.getFirst().get("inventory_state"),(String)records.getFirst().get("inventory_checked_at"),items,
            new com.rlibanez.eplsync.dto.PageResponse.PageMeta(page,size,total,Math.toIntExact(pages),page==0,page+1>=pages,page+1<pages,page>0));
    }

}
