package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.torrent.updates.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false",
        "eplsync.torrent.bulk.retention.enabled=false"})
class BulkSummaryTests {
    @Autowired BulkStore store;
    @Autowired BulkJobRepository jobs;
    @Autowired BulkItemRepository items;
    @Autowired UpdatePlanRepository plans;
    @Autowired UpdateCleanupRepository cleanup;
    @Autowired EntityManagerFactory factory;
    @BeforeEach void reset() {cleanup.deleteAll();plans.deleteAll();items.deleteAll();jobs.deleteAll();}

    private void seed(String id,boolean update) {
        var job=new BulkJob();job.setId(id);job.setState(BulkJob.State.COMPLETED);
        job.setCreatedAt(Instant.now());job.setUpdatedAt(job.getCreatedAt());job.setSelectedBooks(4);jobs.save(job);
        long position=0;
        for(var state:BulkItem.State.values()) {
            var item=new BulkItem();item.setId(id+"-"+position);item.setJobId(id);item.setPosition(position);
            item.setEplId(position/2+1);item.setHash(position==6 ? null : "hash-"+(position/2));item.setState(state);
            items.save(item);position++;
        }
        // The same hash across books is blocked from processing by any unfinished sibling.
        var duplicate=new BulkItem();duplicate.setId(id+"-duplicate");duplicate.setJobId(id);duplicate.setPosition(position);
        duplicate.setEplId(10L);duplicate.setHash("hash-0");duplicate.setState(BulkItem.State.ACCEPTED);items.save(duplicate);
        if(update) {
            var plan=new UpdatePlan();plan.setJobId(id);plan.setClientInstanceId("client");
            plan.setPreviousVersions(PreviousVersions.REMOVE_TORRENT);plan.setCreatedAt(Instant.now());plan.setSnapshot("[]");plans.save(plan);
            for(var state:UpdateCleanup.State.values()) {
                var entry=new UpdateCleanup();entry.setJobId(id);entry.setDownloadId(id+state);entry.setEplId(1L);
                entry.setHash("hash");entry.setState(state);cleanup.save(entry);
            }
        }
    }

    private void compareLegacyCounters(BulkStore.View view) {
        String id=view.jobId();
        var unfinished=List.of(BulkItem.State.PENDING,BulkItem.State.IN_FLIGHT,BulkItem.State.CANCELLED);
        assertThat(view.processedBooks()).isEqualTo(items.processedBooks(id,unfinished));
        assertThat(view.selectedTorrents()).isEqualTo(items.selectedTorrents(id));
        assertThat(view.processedTorrents()).isEqualTo(items.processedTorrents(id,unfinished));
        assertThat(view.selectedItems()).isEqualTo(items.countByJobId(id));
        assertThat(view.pending()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.PENDING));
        assertThat(view.inFlight()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.IN_FLIGHT));
        assertThat(view.accepted()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.ACCEPTED));
        assertThat(view.alreadyExists()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.ALREADY_EXISTS));
        assertThat(view.skipped()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.SKIPPED));
        assertThat(view.failed()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.FAILED));
        assertThat(view.cancelled()).isEqualTo(items.countByJobIdAndState(id,BulkItem.State.CANCELLED));
        assertThat(view.processedItems()).isEqualTo(view.accepted()+view.alreadyExists()+view.skipped()+view.failed());
    }

    @Test void preservesAllCountersWithMixedStatesDuplicatesAndNullHashes() {
        seed("download",false);seed("update",true);
        var page=store.list(0,20,null);
        page.items().forEach(this::compareLegacyCounters);
        var download=store.view("download");assertThat(download.cleanup()).isNull();assertThat(download.type()).isEqualTo(BulkJob.Type.DOWNLOAD);
        var update=store.view("update");
        assertThat(update.cleanup()).isEqualTo(new BulkStore.CleanupSummary(1,1,1,1,1));
        assertThat(update.type()).isEqualTo(BulkJob.Type.UPDATE);
        assertThat(update.previousVersions()).isEqualTo(PreviousVersions.REMOVE_TORRENT);
        assertThat(update.cleanupTiming()).isEqualTo(CleanupTiming.AFTER_DOWNLOAD);
    }

    @Test void emptyJobsAndEmptyPagesHaveZeroCounters() {
        var job=new BulkJob();job.setId("empty");job.setState(BulkJob.State.COMPLETED);jobs.save(job);
        var view=store.view("empty");compareLegacyCounters(view);
        assertThat(view.selectedItems()).isZero();assertThat(view.processedItems()).isZero();
        assertThat(store.list(10,20,null).items()).isEmpty();
    }

    @Test void fixedQueryCountForOneAndTwentyJobsAndComputedOrdering() {
        for(int i=0;i<30;i++) seed("job-"+i,true);
        var stats=factory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        try {
            stats.clear();assertThat(store.list(0,1,null).items()).hasSize(1);
            long single=stats.getPrepareStatementCount();
            stats.clear();assertThat(store.list(0,20,null).items()).hasSize(20);
            long twenty=stats.getPrepareStatementCount();
            assertThat(single).isEqualTo(5);assertThat(twenty).isEqualTo(single);
            stats.clear();assertThat(store.list(0,20,null,"progress,desc").items()).hasSize(20);
            assertThat(stats.getPrepareStatementCount()).isEqualTo(6);
            stats.clear();store.view("job-0");assertThat(stats.getPrepareStatementCount()).isEqualTo(4);
            System.out.printf("BULK_SUMMARY_QUERIES list1=%d list20=%d computed=6 detail=4%n",single,twenty);
        } finally {stats.setStatisticsEnabled(enabled);}
    }
}
