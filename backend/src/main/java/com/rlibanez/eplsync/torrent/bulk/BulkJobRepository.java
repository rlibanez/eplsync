package com.rlibanez.eplsync.torrent.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BulkJobRepository extends JpaRepository<BulkJob, String> {
    List<BulkJob> findByStateOrderByCreatedAtAsc(BulkJob.State state, org.springframework.data.domain.Pageable pageable);
    long countByStateIn(java.util.Collection<BulkJob.State> states);
    org.springframework.data.domain.Page<BulkJob> findByStateIn(
            java.util.Collection<BulkJob.State> states, org.springframework.data.domain.Pageable pageable);
}
