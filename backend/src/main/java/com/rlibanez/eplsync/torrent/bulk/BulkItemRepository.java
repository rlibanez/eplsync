package com.rlibanez.eplsync.torrent.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface BulkItemRepository extends JpaRepository<BulkItem, String> {
    List<BulkItem> findByJobIdAndStateOrderByPosition(String jobId, BulkItem.State state, Pageable pageable);
    Page<BulkItem> findByJobIdOrderByPosition(String jobId, Pageable pageable);
    long countByJobIdAndState(String jobId, BulkItem.State state);
    long countByJobId(String jobId);
    @Query("select count(distinct i.hash) from BulkItem i where i.jobId = :jobId")
    long selectedTorrents(String jobId);
    @Query("select count(distinct i.eplId) from BulkItem i where i.jobId = :jobId and not exists "
            + "(select x.id from BulkItem x where x.jobId = :jobId and x.eplId = i.eplId and x.state in :unfinished)")
    long processedBooks(String jobId, List<BulkItem.State> unfinished);
    @Query("select count(distinct i.hash) from BulkItem i where i.jobId = :jobId and i.hash is not null and not exists "
            + "(select x.id from BulkItem x where x.jobId = :jobId and x.hash = i.hash and x.state in :unfinished)")
    long processedTorrents(String jobId, List<BulkItem.State> unfinished);
    @Modifying
    @Query("update BulkItem i set i.state = :to where i.jobId = :jobId and i.state = :from")
    int transition(String jobId, BulkItem.State from, BulkItem.State to);
    @Modifying
    @Query("update BulkItem i set i.state = :to where i.state = :from")
    int recover(BulkItem.State from, BulkItem.State to);
}
