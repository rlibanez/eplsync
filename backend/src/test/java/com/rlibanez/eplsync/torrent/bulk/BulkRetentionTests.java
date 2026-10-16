package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.torrent.updates.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true",
        "eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false",
        "eplsync.torrent.bulk.retention.enabled=false"})
class BulkRetentionTests {
    @Autowired BulkRetentionStore retention;
    @Autowired BulkRetention timer;
    @Autowired BulkJobRepository jobs;
    @Autowired BulkItemRepository items;
    @Autowired UpdateCleanupRepository cleanup;
    @Autowired UpdatePlanRepository plans;
    @Autowired DownloadRepository downloads;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    private final Instant now=Instant.now();
    private final Instant cutoff=now.minus(Duration.ofDays(90));

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM calibre_jobs");
        cleanup.deleteAll(); plans.deleteAll(); items.deleteAll(); jobs.deleteAll(); downloads.deleteAll();
    }

    @Test void retentionHasOneSummaryForSuccessOrPartialFailureAndNoIdleSummary() {
        var store=org.mockito.Mockito.mock(BulkRetentionStore.class);
        var timer=new BulkRetention(store,new com.rlibanez.eplsync.maintenance.MaintenanceGate());
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(BulkRetention.class);
        var previous=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            org.mockito.Mockito.when(store.purgeOne(org.mockito.ArgumentMatchers.any())).thenReturn(new BulkRetentionStore.Result(1,2,3),new BulkRetentionStore.Result(0,0,0));
            timer.tick();
            assertThat(capture.list).singleElement().satisfies(e->{assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);assertThat(e.getFormattedMessage()).contains("trabajos=1", "elementos=2", "limpiezasFinalizadas=3");});
            capture.list.clear();timer.tick();assertThat(capture.list).isEmpty();
            org.mockito.Mockito.when(store.purgeOne(org.mockito.ArgumentMatchers.any())).thenReturn(new BulkRetentionStore.Result(1,2,3)).thenThrow(new IllegalStateException("failure"));
            timer.tick();
            assertThat(capture.list).singleElement().satisfies(e->{assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);assertThat(e.getFormattedMessage()).contains("incompleta", "trabajos=1", "elementos=2", "limpiezasFinalizadas=3");});
        } finally {logger.detachAppender(capture);capture.stop();logger.setLevel(previous);}
    }
    private BulkJob job(BulkJob.State state, Instant updated) {
        var job=new BulkJob();job.setId(UUID.randomUUID().toString());job.setState(state);
        job.setCreatedAt(now.minus(Duration.ofDays(200)));job.setUpdatedAt(updated);
        return jobs.save(job);
    }
    private BulkItem item(BulkJob job,BulkItem.State state,long position) {
        var item=new BulkItem();item.setId(UUID.randomUUID().toString());item.setJobId(job.getId());
        item.setEplId(1L);item.setRevision(1.0);item.setPosition(position);item.setState(state);
        item.setCommandJson("large command".repeat(1000));return items.save(item);
    }
    private UpdateCleanup cleanup(BulkJob job,UpdateCleanup.State state) {
        var entry=new UpdateCleanup();entry.setJobId(job.getId());entry.setDownloadId(UUID.randomUUID().toString());
        entry.setEplId(1L);entry.setHash("A".repeat(40));entry.setState(state);entry.setUpdatedAt(now);
        return cleanup.save(entry);
    }

    @Test void preservesDownloadsNeededByCalibreWithoutBlockingOtherExpiredJobs() {
        var dependency=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(2));
        var expired=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(1));
        String now=Instant.now().toString();
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('calibre',?,?, 'Owner','destination','main','source','PAUSED')",now,now);
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,name,epl_id,revision,state,download_job_id,wait_until) VALUES('item','calibre',0,'Book',1,'1.1','BLOCKED_DOWNLOAD',?,?)",dependency.getId(),Instant.now().plus(Duration.ofDays(30)).toEpochMilli());
        assertThat(retention.purgeOne(cutoff).jobs()).isEqualTo(1);
        assertThat(jobs.existsById(dependency.getId())).isTrue();assertThat(jobs.existsById(expired.getId())).isFalse();
        jdbc.update("UPDATE calibre_job_items SET state='CANCELLED'");
        assertThat(retention.purgeOne(cutoff).jobs()).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value=BulkJob.State.class,names={"COMPLETED","CANCELLED"})
    void removesExpiredJobWithItsItemsAndFinalizedPlanOnly(BulkJob.State state) {
        var job=job(state,cutoff.minusSeconds(1));item(job,BulkItem.State.ACCEPTED,0);
        for(var terminal: new UpdateCleanup.State[]{UpdateCleanup.State.KEPT,UpdateCleanup.State.REMOVED,UpdateCleanup.State.CANCELLED}) cleanup(job,terminal);
        var plan=new UpdatePlan();plan.setJobId(job.getId());plan.setClientInstanceId("client");
        plan.setPreviousVersions(PreviousVersions.KEEP);plan.setCreatedAt(now);plan.setSnapshot("[]");plans.save(plan);
        var history=new DownloadRecord();history.setEplId(1L);history.setRevision(1.0);history.setHash("A".repeat(40));
        history.setClient("qbittorrent");history.setClientInstanceId("client");history.setStatus(DownloadStatus.DOWNLOADED);
        history.setOrigin(DownloadRecord.Origin.EPLSYNC);history.setCreatedAt(now);downloads.save(history);
        var event=events.recordAs(com.rlibanez.eplsync.events.EventContext.Actor.system(),
                com.rlibanez.eplsync.events.EventJournal.Category.JOB,"DOWNLOAD",
                com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED,
                com.rlibanez.eplsync.events.EventContext.Origin.MANUAL,job.getId(),java.util.Map.of("accepted",1));
        long eventCount=jdbc.queryForObject("select count(*) from app_events",Long.class);
        assertThat(retention.purgeOne(cutoff)).isEqualTo(new BulkRetentionStore.Result(1,1,3));
        assertThat(jobs.existsById(job.getId())).isFalse();assertThat(items.count()).isZero();
        assertThat(plans.count()).isZero();assertThat(cleanup.count()).isZero();
        assertThat(downloads.existsById(history.getId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from app_events",Long.class)).isEqualTo(eventCount);
        assertThat(jdbc.queryForObject("select count(*) from app_events where id=?",Long.class,event.id())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value=BulkJob.State.class,names={"QUEUED","RUNNING","RETRY_WAIT","PAUSED"})
    void protectsUnfinishedJobs(BulkJob.State state) {
        var job=job(state,cutoff.minusSeconds(1));
        assertThat(retention.purgeOne(cutoff).jobs()).isZero();assertThat(jobs.existsById(job.getId())).isTrue();
    }

    @ParameterizedTest @EnumSource(value=UpdateCleanup.State.class,names={"WAITING","BLOCKED","REQUESTED"})
    void skipsProtectedCleanupWithoutStarvingOtherExpiredJobs(UpdateCleanup.State state) {
        var protectedJob=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(2));cleanup(protectedJob,state);
        var expired=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(1));
        assertThat(retention.purgeOne(cutoff).jobs()).isEqualTo(1);
        assertThat(jobs.existsById(protectedJob.getId())).isTrue();assertThat(jobs.existsById(expired.getId())).isFalse();
    }

    @ParameterizedTest @EnumSource(value=BulkItem.State.class,names={"PENDING","IN_FLIGHT"})
    void protectsUnfinishedItemsEvenInCancelledJobs(BulkItem.State state) {
        var job=job(BulkJob.State.CANCELLED,cutoff.minusSeconds(1));item(job,state,0);
        assertThat(retention.purgeOne(cutoff).jobs()).isZero();assertThat(items.count()).isEqualTo(1);
    }

    @Test void ageUsesLastUpdateAndExcludesExactBoundary() {
        job(BulkJob.State.COMPLETED,now);job(BulkJob.State.COMPLETED,cutoff);
        assertThat(retention.purgeOne(cutoff).jobs()).isZero();assertThat(jobs.count()).isEqualTo(2);
    }

    @Test void disabledTimerDoesNotPurge() {
        job(BulkJob.State.COMPLETED,cutoff.minusSeconds(1));timer.tick();assertThat(jobs.count()).isEqualTo(1);
    }

    @Test void rejectsInvalidRetentionConfiguration() {
        var isolated=new BulkRetention(retention,new com.rlibanez.eplsync.maintenance.MaintenanceGate());
        org.springframework.test.util.ReflectionTestUtils.setField(isolated,"days",0);
        assertThatThrownBy(isolated::validate).isInstanceOf(IllegalArgumentException.class);
        org.springframework.test.util.ReflectionTestUtils.setField(isolated,"days",36501);
        assertThatThrownBy(isolated::validate).isInstanceOf(IllegalArgumentException.class);
        org.springframework.test.util.ReflectionTestUtils.setField(isolated,"days",90);
        org.springframework.test.util.ReflectionTestUtils.setField(isolated,"interval","0s");
        assertThatThrownBy(isolated::validate).isInstanceOf(IllegalArgumentException.class);
        org.springframework.test.util.ReflectionTestUtils.setField(isolated,"interval","-1s");
        assertThatThrownBy(isolated::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deletesOneWholeJobPerTransactionInOldestOrder() {
        var oldest=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(2));
        for(int i=0;i<55;i++) item(oldest,BulkItem.State.ACCEPTED,i);
        var newer=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(1));item(newer,BulkItem.State.ACCEPTED,0);
        assertThat(retention.purgeOne(cutoff)).isEqualTo(new BulkRetentionStore.Result(1,55,0));
        assertThat(items.count()).isEqualTo(1);assertThat(jobs.existsById(newer.getId())).isTrue();
    }

    @Test void failedFinalDeleteRollsBackItemsCleanupAndPlan() {
        var job=job(BulkJob.State.COMPLETED,cutoff.minusSeconds(1));item(job,BulkItem.State.ACCEPTED,0);
        cleanup(job,UpdateCleanup.State.REMOVED);
        var plan=new UpdatePlan();plan.setJobId(job.getId());plan.setClientInstanceId("client");
        plan.setPreviousVersions(PreviousVersions.KEEP);plan.setCreatedAt(now);plan.setSnapshot("[]");plans.save(plan);
        jdbc.execute("CREATE TRIGGER retention_failure BEFORE DELETE ON torrent_bulk_jobs BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try {
            assertThatThrownBy(() -> retention.purgeOne(cutoff)).isInstanceOf(RuntimeException.class);
            assertThat(jobs.count()).isEqualTo(1);assertThat(items.count()).isEqualTo(1);
            assertThat(cleanup.count()).isEqualTo(1);assertThat(plans.count()).isEqualTo(1);
        } finally {jdbc.execute("DROP TRIGGER retention_failure");}
        assertThat(retention.purgeOne(cutoff).jobs()).isEqualTo(1);
    }
}
