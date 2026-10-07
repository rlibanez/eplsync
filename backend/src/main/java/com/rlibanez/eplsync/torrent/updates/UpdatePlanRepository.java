package com.rlibanez.eplsync.torrent.updates;

public interface UpdatePlanRepository extends org.springframework.data.jpa.repository.JpaRepository<UpdatePlan, String> {
    @org.springframework.data.jpa.repository.Query("select p from UpdatePlan p where p.automaticCleanup=true and p.jobId>:after "
        + "and exists (select e.id from UpdateCleanup e where e.jobId=p.jobId and e.state in :states) order by p.jobId")
    java.util.List<UpdatePlan> automatic(String after, java.util.List<UpdateCleanup.State> states, org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Query("select p from UpdatePlan p where p.previousVersions <> :keep "
            + "and exists (select e.id from UpdateCleanup e where e.jobId = p.jobId and e.state in :states) "
            + "order by p.createdAt, p.jobId")
    java.util.List<UpdatePlan> pending(PreviousVersions keep, java.util.List<UpdateCleanup.State> states);
}
