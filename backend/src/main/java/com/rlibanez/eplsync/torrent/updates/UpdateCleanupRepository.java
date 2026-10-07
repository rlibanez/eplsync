package com.rlibanez.eplsync.torrent.updates;

public interface UpdateCleanupRepository extends org.springframework.data.jpa.repository.JpaRepository<UpdateCleanup, String> {
    java.util.List<UpdateCleanup> findByJobIdOrderByEplIdAsc(String jobId);
    org.springframework.data.domain.Page<UpdateCleanup> findByJobIdOrderByEplIdAsc(String jobId,org.springframework.data.domain.Pageable pageable);
    java.util.List<UpdateCleanup> findByClientInstanceIdAndHashInAndStateIn(String client, java.util.Collection<String> hashes, java.util.Collection<UpdateCleanup.State> states);
    java.util.List<UpdateCleanup> findByClientInstanceIdAndStateIn(String client, java.util.Collection<UpdateCleanup.State> states);
    java.util.List<UpdateCleanup> findByClientInstanceIdIsNull(org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Query("select e from UpdateCleanup e where (e.automatic=true or e.state=com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REQUESTED) and e.state in :states "
        + "order by coalesce(e.lastCheckedAt,e.createdAt),e.createdAt,e.id")
    java.util.List<UpdateCleanup> pendingAutomatic(java.util.Collection<UpdateCleanup.State> states, org.springframework.data.domain.Pageable page);
}
