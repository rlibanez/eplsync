package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.torrent.updates.UpdateCleanup;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/** Removes one expired job atomically; never loads its commands into memory. */
@Service
public class BulkRetentionStore {
    private final EntityManager em;
    public BulkRetentionStore(EntityManager em) { this.em = em; }
    public record Result(int jobs, int items, int cleanup) {}

    @Transactional(timeout=10)
    public Result purgeOne(Instant cutoff) {
        long deadline = System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        var session = em.unwrap(org.hibernate.Session.class);
        session.doWork(connection -> org.sqlite.ProgressHandler.setHandler(
                connection.unwrap(org.sqlite.SQLiteConnection.class),1000,new org.sqlite.ProgressHandler() {
                    @Override protected int progress() {
                        return Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline ? 1 : 0;
                    }
                }));
        try { return deleteExpired(cutoff); }
        finally { session.doWork(connection -> org.sqlite.ProgressHandler.clearHandler(connection.unwrap(org.sqlite.SQLiteConnection.class))); }
    }

    private Result deleteExpired(Instant cutoff) {
        var candidates = em.createQuery("select j.id from BulkJob j where j.state in :finished "
                + "and j.updatedAt < :cutoff and not exists "
                + "(select i.id from BulkItem i where i.jobId=j.id and i.state in :unfinished) "
                + "and not exists (select c.id from UpdateCleanup c where c.jobId=j.id and c.state in :pending) "
                + "order by j.updatedAt,j.id", String.class)
                .setParameter("finished", List.of(BulkJob.State.COMPLETED, BulkJob.State.CANCELLED))
                .setParameter("cutoff", cutoff)
                .setParameter("unfinished", List.of(BulkItem.State.PENDING, BulkItem.State.IN_FLIGHT))
                .setParameter("pending", List.of(UpdateCleanup.State.WAITING, UpdateCleanup.State.BLOCKED, UpdateCleanup.State.REQUESTED))
                .setMaxResults(1).getResultList();
        if (candidates.isEmpty()) return new Result(0,0,0);
        String id = candidates.getFirst();
        int items = em.createQuery("delete from BulkItem i where i.jobId=:id").setParameter("id",id).executeUpdate();
        int cleanup = em.createQuery("delete from UpdateCleanup c where c.jobId=:id").setParameter("id",id).executeUpdate();
        em.createQuery("delete from UpdatePlan p where p.jobId=:id").setParameter("id",id).executeUpdate();
        int jobs = em.createQuery("delete from BulkJob j where j.id=:id").setParameter("id",id).executeUpdate();
        return new Result(jobs,items,cleanup);
    }
}
