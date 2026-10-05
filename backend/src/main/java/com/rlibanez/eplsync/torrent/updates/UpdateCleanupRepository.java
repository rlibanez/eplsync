package com.rlibanez.eplsync.torrent.updates;

public interface UpdateCleanupRepository extends org.springframework.data.jpa.repository.JpaRepository<UpdateCleanup, String> {
    java.util.List<UpdateCleanup> findByJobIdOrderByEplIdAsc(String jobId);
    org.springframework.data.domain.Page<UpdateCleanup> findByJobIdOrderByEplIdAsc(String jobId,org.springframework.data.domain.Pageable pageable);
}
