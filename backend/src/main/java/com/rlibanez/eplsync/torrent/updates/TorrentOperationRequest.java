package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.torrent.bulk.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.data.domain.*;

public record TorrentOperationRequest(boolean dryRun, @Valid CatalogBookFilter filters, String selection,
        Boolean includeNotFound, @Min(0) Integer page, @Min(1) @jakarta.validation.constraints.Max(com.rlibanez.eplsync.config.QueryLimits.MAX_SIZE) Integer size, List<String> sort, Boolean all,
        Boolean includeDetails, PreviousVersions previousVersions, TorrentDownloadRequest options,
        Integer batchSize, Integer concurrency, String interval, MultipleHashes multipleHashes,
        @Min(0) Integer detailPage,@Min(1) @jakarta.validation.constraints.Max(1000) Integer detailSize) {
    public int detailPageNumber() { return detailPage==null ? 0 : detailPage; }
    public int detailPageSize() { return detailSize==null ? 20 : detailSize; }
    public CatalogBookFilter filter() { var f = filters == null ? new CatalogBookFilter() : filters; f.normalize(); return f; }
    public int pageNumber() { return page == null ? 0 : page; }
    public int pageSize() { return size == null ? 50 : size; }
    public boolean paginated() { return page != null || size != null; }
    public Pageable pageable() {
        var orders = new java.util.ArrayList<Sort.Order>();
        if (sort != null) for (String value : sort) {
            String[] parts = value.split(",", -1);
            if (parts.length > 2 || parts[0].isBlank()) throw new com.rlibanez.eplsync.exception.UserInputException("Orden inválido");
            orders.add(new Sort.Order(parts.length == 1 ? Sort.Direction.ASC : Sort.Direction.fromString(parts[1]),parts[0]));
        }
        com.rlibanez.eplsync.config.QueryLimits.page(pageNumber(), size == null ? 20 : size);
        com.rlibanez.eplsync.config.QueryLimits.sort(Sort.by(orders));
        return PageRequest.of(pageNumber(),size == null ? 20 : size,Sort.by(orders));
    }
    public BulkRequest bulk() { return new BulkRequest(options,batchSize,concurrency,interval,multipleHashes); }
    public UpdateRequest update() { return new UpdateRequest(previousVersions,options,batchSize,concurrency,interval,multipleHashes); }
}
