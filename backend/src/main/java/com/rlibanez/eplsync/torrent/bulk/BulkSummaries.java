package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.torrent.updates.CleanupTiming;
import com.rlibanez.eplsync.torrent.updates.PreviousVersions;
import com.rlibanez.eplsync.torrent.updates.UpdateCleanup;
import jakarta.persistence.EntityManager;
import java.util.*;

/** Aggregates only the requested page, with a fixed number of queries and no command/snapshot loading. */
final class BulkSummaries {
    private record Counts(long total,long pending,long inFlight,long accepted,long existing,long skipped,
                          long failed,long cancelled,long books,long torrents,long processedTorrents) {
        private static final Counts EMPTY=new Counts(0,0,0,0,0,0,0,0,0,0,0);
    }
    private record Plan(PreviousVersions policy,CleanupTiming timing) {}
    static List<BulkStore.View> load(EntityManager em,List<BulkJob> jobs) {
        if(jobs.isEmpty()) return List.of();
        var ids=jobs.stream().map(BulkJob::getId).toList();
        var counts=new HashMap<String,Counts>();
        // Grouping by book/hash first preserves the rule that every sibling must be finished.
        var query=em.createNativeQuery("""
            WITH selected AS (
                SELECT job_id,epl_id,hash,state FROM torrent_bulk_items WHERE job_id IN (:ids)
            ), books AS (
                SELECT job_id,epl_id,MAX(CASE WHEN state IN ('PENDING','IN_FLIGHT','CANCELLED') THEN 1 ELSE 0 END) unfinished
                FROM selected WHERE epl_id IS NOT NULL GROUP BY job_id,epl_id
            ), book_counts AS (
                SELECT job_id,SUM(CASE WHEN unfinished=0 THEN 1 ELSE 0 END) processed FROM books GROUP BY job_id
            ), hashes AS (
                SELECT job_id,hash,MAX(CASE WHEN state IN ('PENDING','IN_FLIGHT','CANCELLED') THEN 1 ELSE 0 END) unfinished
                FROM selected WHERE hash IS NOT NULL GROUP BY job_id,hash
            ), hash_counts AS (
                SELECT job_id,COUNT(*) total,SUM(CASE WHEN unfinished=0 THEN 1 ELSE 0 END) processed FROM hashes GROUP BY job_id
            )
            SELECT i.job_id,COUNT(*),
                SUM(CASE WHEN i.state='PENDING' THEN 1 ELSE 0 END),
                SUM(CASE WHEN i.state='IN_FLIGHT' THEN 1 ELSE 0 END),
                SUM(CASE WHEN i.state='ACCEPTED' THEN 1 ELSE 0 END),
                SUM(CASE WHEN i.state='ALREADY_EXISTS' THEN 1 ELSE 0 END),
                SUM(CASE WHEN i.state='SKIPPED' THEN 1 ELSE 0 END),
                SUM(CASE WHEN i.state='FAILED' THEN 1 ELSE 0 END),
                SUM(CASE WHEN i.state='CANCELLED' THEN 1 ELSE 0 END),
                COALESCE(b.processed,0),COALESCE(h.total,0),COALESCE(h.processed,0)
            FROM selected i LEFT JOIN book_counts b ON b.job_id=i.job_id
                LEFT JOIN hash_counts h ON h.job_id=i.job_id GROUP BY i.job_id
            """).setParameter("ids",ids);
        for(Object result:query.getResultList()) {
            var row=(Object[])result;
            counts.put(row[0].toString(),new Counts(number(row[1]),number(row[2]),number(row[3]),number(row[4]),
                    number(row[5]),number(row[6]),number(row[7]),number(row[8]),number(row[9]),number(row[10]),number(row[11])));
        }
        var plans=new HashMap<String,Plan>();
        for(var row:em.createQuery("select p.jobId,p.previousVersions,p.cleanupTiming from UpdatePlan p where p.jobId in :ids",Object[].class)
                .setParameter("ids",ids).getResultList())
            plans.put((String)row[0],new Plan((PreviousVersions)row[1],(CleanupTiming)row[2]));
        var cleanup=new HashMap<String,EnumMap<UpdateCleanup.State,Long>>();
        if(!plans.isEmpty()) for(var row:em.createQuery("select c.jobId,c.state,count(c) from UpdateCleanup c where c.jobId in :ids group by c.jobId,c.state",Object[].class)
                .setParameter("ids",plans.keySet()).getResultList())
            cleanup.computeIfAbsent((String)row[0],ignored -> new EnumMap<>(UpdateCleanup.State.class))
                .put((UpdateCleanup.State)row[1],(Long)row[2]);
        return jobs.stream().map(j -> {
            var c=counts.getOrDefault(j.getId(),Counts.EMPTY);
            var p=plans.get(j.getId());
            var stateCounts=cleanup.getOrDefault(j.getId(),new EnumMap<>(UpdateCleanup.State.class));
            var summary=p==null ? null : new BulkStore.CleanupSummary(stateCounts.getOrDefault(UpdateCleanup.State.WAITING,0L),
                    stateCounts.getOrDefault(UpdateCleanup.State.BLOCKED,0L),stateCounts.getOrDefault(UpdateCleanup.State.REQUESTED,0L),
                    stateCounts.getOrDefault(UpdateCleanup.State.REMOVED,0L),stateCounts.getOrDefault(UpdateCleanup.State.CANCELLED,0L));
            return new BulkStore.View(j.getId(),j.getState(),j.getClient(),j.getSelectedBooks(),c.books,
                    c.accepted,c.existing,c.skipped,c.failed,c.pending,c.inFlight,c.cancelled,
                    j.getBatchSize(),j.getConcurrency(),j.getIntervalMillis()+"ms",j.getCreatedAt(),j.getUpdatedAt(),j.getRetryAt(),j.getMessage(),
                    j.getMultipleHashes()==null ? MultipleHashes.SKIP : j.getMultipleHashes(),c.torrents,c.processedTorrents,
                    c.total,c.accepted+c.existing+c.skipped+c.failed,p==null ? BulkJob.Type.DOWNLOAD : BulkJob.Type.UPDATE,
                    p==null ? null : p.policy,summary,p==null ? null : p.timing==null ? CleanupTiming.AFTER_DOWNLOAD : p.timing);
        }).toList();
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
}
