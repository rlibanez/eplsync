package com.rlibanez.eplsync.specification;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.CalibreBook;
import com.rlibanez.eplsync.security.Permission;
import com.rlibanez.eplsync.torrent.downloads.DownloadRecord;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

/** Correlated local queries: no remote calls and no materialized list of catalog IDs. */
@Component
public class CatalogLibraryFilters {
    private final DownloadTrackingService tracking;
    public CatalogLibraryFilters(DownloadTrackingService tracking) {this.tracking=tracking;}
    public Specification<CatalogBook> fromFilter(CatalogBookFilter filter) {
        filter.normalize();
        boolean torrent=filter.getTorrentStatus()!=null || filter.getTorrentPresence()!=null || filter.getTorrentRevision()!=null;
        boolean calibre=filter.getCalibrePresence()!=null || filter.getCalibreRevision()!=null;
        if(torrent && !Permission.has(Permission.BOOK_HISTORY_READ)) Permission.require(Permission.TORRENT_SYNC);
        if(calibre) Permission.require(Permission.CALIBRE_READ);
        String instance=torrent?tracking.instanceId():null;
        return (root,query,cb)-> {
            var criteria=java.util.Objects.requireNonNull(query);
            var predicates=new ArrayList<Predicate>();
            if(torrent) {
                var present=criteria.subquery(Integer.class);var record=present.from(DownloadRecord.class);
                present.select(cb.literal(1)).where(cb.equal(record.get("eplId"),root.get("eplId")),
                    cb.equal(record.get("clientInstanceId"),instance),cb.notEqual(record.get("status"),DownloadStatus.NOT_FOUND));
                predicates.add("ABSENT".equals(filter.getTorrentPresence())?cb.not(cb.exists(present)):cb.exists(present));
                if(filter.getTorrentStatus()!=null) {
                    var matching=criteria.subquery(Integer.class);var item=matching.from(DownloadRecord.class);
                    matching.select(cb.literal(1)).where(cb.equal(item.get("eplId"),root.get("eplId")),
                        cb.equal(item.get("clientInstanceId"),instance),item.get("status").in((Object[])filter.getTorrentStatus()));
                    predicates.add(com.rlibanez.eplsync.ordering.StateOperator.excluded(filter.getTorrentStatusOperator())?cb.not(cb.exists(matching)):cb.exists(matching));
                }
                if(filter.getTorrentRevision()!=null) {
                    var known=criteria.subquery(Double.class);var copy=known.from(DownloadRecord.class);
                    known.select(cb.max(copy.<Double>get("revision"))).where(cb.equal(copy.get("eplId"),root.get("eplId")),
                        cb.equal(copy.get("clientInstanceId"),instance),cb.notEqual(copy.get("status"),DownloadStatus.NOT_FOUND));
                    predicates.add("UPDATED".equals(filter.getTorrentRevision())?cb.greaterThanOrEqualTo(known,root.get("revision")):cb.lessThan(known,root.get("revision")));
                }
            }
            if(calibre) {
                var present=criteria.subquery(Integer.class);var record=present.from(CalibreBook.class);
                present.select(cb.literal(1)).where(cb.equal(record.get("eplId"),root.get("eplId")),cb.isTrue(record.get("active")));
                predicates.add("ABSENT".equals(filter.getCalibrePresence())?cb.not(cb.exists(present)):cb.exists(present));
                if(filter.getCalibreRevision()!=null) {
                    var known=criteria.subquery(Double.class);var copy=known.from(CalibreBook.class);
                    known.select(cb.max(copy.<Double>get("revision"))).where(cb.equal(copy.get("eplId"),root.get("eplId")),cb.isTrue(copy.get("active")));
                    predicates.add("UPDATED".equals(filter.getCalibreRevision())?cb.greaterThanOrEqualTo(known,root.get("revision")):cb.lessThan(known,root.get("revision")));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
