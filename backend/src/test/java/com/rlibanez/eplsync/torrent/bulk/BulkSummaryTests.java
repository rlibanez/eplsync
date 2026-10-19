package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.torrent.updates.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false",
        "eplsync.torrent.bulk.retention.enabled=false"})
class BulkSummaryTests {
    @Autowired BulkStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.rlibanez.eplsync.settings.WorkListings listings;
    @Autowired BulkJobRepository jobs;
    @Autowired BulkItemRepository items;
    @Autowired UpdatePlanRepository plans;
    @Autowired UpdateCleanupRepository cleanup;
    @Autowired EntityManagerFactory factory;
    @BeforeEach void reset() {jdbc.update("DELETE FROM job_retry_jobs");jdbc.update("DELETE FROM calibre_jobs");cleanup.deleteAll();plans.deleteAll();items.deleteAll();jobs.deleteAll();}

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

    private void calibreJob(String id,String library,String state) {
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES(?,'2026-10-19T10:00:00Z','2026-10-19T10:00:00Z','owner','target',?,'source',?)",id,library,state);
    }
    private void calibreItem(String id,String job,int position,String download,String state) {
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,download_job_id) VALUES(?,?,?, ?,0,'book.epub',10,1,'1',?,?)",id,job,position,id,state,download);
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"TORRENT_JOBS_MANAGE","CALIBRE_SEND"})
    void calibreSummariesCountOnlyLinkedItemsAcrossDestinationsAndDoNotDuplicateLegacyRetries() throws Exception {
        seed("download",false);seed("other",false);seed("none",false);
        calibreJob("first","Main","PAUSED");calibreJob("second","Other","RUNNING");
        var states=List.of("ACCEPTED","SKIPPED","WAITING_DOWNLOAD","FAILED","UNCERTAIN","CANCELLED","BLOCKED_DOWNLOAD","CLEANUP_PENDING");
        for(int i=0;i<states.size();i++) calibreItem("first-"+i,"first",i,"download",states.get(i));
        calibreItem("unrelated","first",8,"other","ACCEPTED");calibreItem("unlinked","first",9,null,"ACCEPTED");
        calibreItem("replaced","first",10,"download","FAILED");calibreItem("retry","second",0,"download","ACCEPTED");
        jdbc.update("INSERT INTO job_retry_jobs(kind,child_id,parent_id,root_id,request_id,created_at) VALUES('calibre','second','first','first','request',0)");
        jdbc.update("INSERT INTO job_retry_items(kind,child_id,parent_id,source_item_id,child_item_id,phase) VALUES('calibre','second','first','replaced','retry','SEND')");
        var summary=store.view("download").calibre();
        assertThat(summary.counts()).isEqualTo(new BulkStore.CalibreCounts(9,3,1,1,2,1,1));
        assertThat(summary.jobs()).extracting(value->java.util.Objects.requireNonNull(value).id()).containsExactly("first","second");
        assertThat(summary.jobs().getFirst().counts().total()).isEqualTo(8);
        assertThat(summary.jobs().getFirst().library()).isEqualTo("Main");assertThat(summary.jobs().getFirst().state()).isEqualTo("PAUSED");
        assertThat(store.view("none").calibre()).isNull();
        assertThat(store.view("other").calibre().counts()).isEqualTo(new BulkStore.CalibreCounts(1,1,0,0,0,0,0));
        // Reattempting the same item changes its result, not the denominator.
        jdbc.update("UPDATE calibre_job_items SET state='ACCEPTED' WHERE id='first-3'");
        assertThat(store.view("download").calibre().counts()).isEqualTo(new BulkStore.CalibreCounts(9,4,1,1,1,1,1));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs/download"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.calibre.counts.total").value(9));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs").param("type","DOWNLOAD"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TORRENT_JOBS_MANAGE","CALIBRE_READ"})
    @org.springframework.security.test.context.support.WithMockUser(authorities="TORRENT_JOBS_MANAGE")
    void relatedCalibreDataRequiresPermissionToReadCalibreJobs(String extraPermission) throws Exception {
        seed("download",false);calibreJob("private-calibre","secret-library","RUNNING");calibreItem("secret-item","private-calibre",0,"download","WAITING_DOWNLOAD");
        var previous=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var security=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reader","unused",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_JOBS_MANAGE"),new org.springframework.security.core.authority.SimpleGrantedAuthority(extraPermission))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(security);
        try {
            assertThat(store.view("download").calibre()).isNull();
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
            for(String path:List.of("/api/torrent/jobs/download","/api/torrent/jobs")) {
                var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(response).doesNotContain("secret-library","private-calibre");
            }
        } finally {org.springframework.security.core.context.SecurityContextHolder.setContext(previous);}
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"TORRENT_JOBS_MANAGE","CALIBRE_JOBS_MANAGE"})
    void relatedCalibreProjectionHasBoundedQueriesForTheActualJobListing() {
        for(int i=0;i<20;i++) {seed("job-"+i,false);calibreJob("calibre-"+i,"library","RUNNING");calibreItem("item-"+i,"calibre-"+i,0,"job-"+i,"WAITING_DOWNLOAD");}
        var stats=factory.unwrap(SessionFactory.class).getStatistics();boolean enabled=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        try {
            stats.clear();var one=listings.list("downloads",0,1,null,"DOWNLOAD",null,null,null,"jobId,asc");long single=stats.getPrepareStatementCount();
            stats.clear();var many=listings.list("downloads",0,20,null,"DOWNLOAD",null,null,null,"jobId,asc");
            assertThat(stats.getPrepareStatementCount()).isEqualTo(single);assertThat(one.items()).hasSize(1);assertThat(many.items()).hasSize(20);
            assertThat(many.items()).allSatisfy(row->assertThat(row.get("calibre")).isNotNull());
            assertThat(listings.list("downloads",1,10,null,"DOWNLOAD",null,null,null,"jobId,asc").items()).hasSize(10);
        } finally {stats.setStatisticsEnabled(enabled);}
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

    @Test void trackedDownloadSummariesRemainBoundedForOneAndTwentyJobs() {
        for(int i=0;i<20;i++) {seed("tracked-"+i,false);var job=jobs.findById("tracked-"+i).orElseThrow();job.setTrackDownloads(true);jobs.save(job);}
        var stats=factory.unwrap(SessionFactory.class).getStatistics();boolean enabled=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        try {
            stats.clear();var single=store.list(0,1,null);long queries=stats.getPrepareStatementCount();
            stats.clear();var many=store.list(0,20,null);assertThat(stats.getPrepareStatementCount()).isEqualTo(queries);
            assertThat(queries).isEqualTo(5);assertThat(single.items().getFirst().download().pending()).isEqualTo(3);
            assertThat(many.items()).allSatisfy(view->assertThat(view.download().selected()).isEqualTo(3));
        } finally {stats.setStatisticsEnabled(enabled);}
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
