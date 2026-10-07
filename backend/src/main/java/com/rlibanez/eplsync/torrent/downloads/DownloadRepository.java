package com.rlibanez.eplsync.torrent.downloads;

import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface DownloadRepository extends JpaRepository<DownloadRecord, String>, JpaSpecificationExecutor<DownloadRecord> {
    Optional<DownloadRecord> findByClientInstanceIdAndEplIdAndHash(String instance, Long eplId, String hash);
    List<DownloadRecord> findByClientInstanceId(String instance);
    List<DownloadRecord> findByClientInstanceIdAndHashIn(String instance, Collection<String> hashes);
    List<DownloadRecord> findByEplIdIn(Collection<Long> ids);

    interface HistoryRow {
        Long getEplId(); String getId(); Double getRevision(); String getStatus(); Integer getCompleted(); Long getTotal();
    }
    @Query(value="""
        WITH ranked AS (
          SELECT epl_id AS eplId, id, revision, status,
                 CASE WHEN completed_at IS NULL THEN 0 ELSE 1 END AS completed,
                 count(*) OVER (PARTITION BY epl_id) AS total,
                 row_number() OVER (PARTITION BY epl_id ORDER BY revision DESC, created_at DESC, id ASC) AS position
          FROM torrent_downloads WHERE epl_id IN (:ids)
        ) SELECT eplId,id,revision,status,completed,total FROM ranked WHERE position <= :limit ORDER BY eplId,position
        """, nativeQuery=true)
    List<HistoryRow> historyWindow(Collection<Long> ids, int limit);
    interface HistoryStatus { Long getEplId(); String getStatus(); }
    @Query(value="SELECT epl_id AS eplId,status FROM torrent_downloads WHERE epl_id IN (:ids) GROUP BY epl_id,status",nativeQuery=true)
    List<HistoryStatus> historyStatuses(Collection<Long> ids);
    org.springframework.data.domain.Page<DownloadRecord> findByEplId(Long id, org.springframework.data.domain.Pageable pageable);
}
