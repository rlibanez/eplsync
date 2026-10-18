package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.jobs.JobRetryController;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.revision-cleanup.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class WorkDeletionTests {
    @Autowired WorkDeletion deletion;
    @Autowired WorkListings listings;
    @Autowired JobRetries retries;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    @Autowired PlatformTransactionManager manager;
    @Autowired CalibreTransfers transfers;
    @Autowired RevisionCleanup cleanup;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean CalibreClient calibre;
    @MockitoBean com.rlibanez.eplsync.service.TorrentClientService torrent;
    Account owner;
    @BeforeEach void setup() {
        for(String table:List.of("deleted_work_receipts","job_retry_previews","job_retry_attempts","job_retry_jobs","scheduled_tasks","revision_cleanup_jobs","torrent_update_cleanup","torrent_update_plans","torrent_bulk_items","torrent_bulk_jobs","calibre_jobs","calibre_update_requests","torrent_downloads","calibre_books")) jdbc.update("DELETE FROM "+table);
        accounts.clear();owner=accounts.initialize("Owner","owner@example.com","password123","password123");login(owner);
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('record',1,1,?,'stub','instance','DOWNLOADED','EPLSYNC',?)","A".repeat(40),Instant.now().toString());
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('book','generation',1,1,1,1,'Book','Author')");
        jdbc.update("INSERT INTO calibre_update_requests(id,owner_id,payload,result,created_at) VALUES('receipt',?,'{}','{}',?)",owner.id(),Instant.now().toString());
        clearInvocations(calibre,torrent);
    }
    void login(Account account) {SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account,null,account.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));}
    @AfterEach void verifyData() {
        try {assertThat(count("torrent_downloads")).isEqualTo(1);assertThat(count("calibre_books")).isEqualTo(1);assertThat(count("calibre_update_requests")).isEqualTo(1);assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();verifyNoInteractions(calibre,torrent);}
        finally {SecurityContextHolder.clearContext();}
    }
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void job(String source,String id,String state) {
        if(source.equals("downloads")) jdbc.update("INSERT INTO torrent_bulk_jobs(id,state,type,batch_size,concurrency,interval_millis,selected_books,created_at,updated_at) VALUES(?,?,'DOWNLOAD',100,1,0,1,?,?)",id,state,Instant.now().toEpochMilli(),Instant.now().toEpochMilli());
        else jdbc.update("INSERT INTO calibre_jobs(id,state,owner_username,destination,library,source,created_at,updated_at) VALUES(?,?,'Owner','destination','main','source',?,?)",id,state,Instant.now().toString(),Instant.now().toString());
    }
    void item(String source,String job,String state,String dependency) {
        if(source.equals("downloads")) jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES(?,?,0,0,?)",job+"-item",job,state);
        else jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,download_job_id) VALUES(?,?,0,'record',0,'Book.epub',20,1,'1',?,?)",job+"-item",job,state,dependency);
    }
    void cleanupJob(String source,String id,String state,String itemState,boolean dispatched) {
        jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,created_at,updated_at,expires_at) VALUES(?,?,'BOOK',?,'Owner','context',?,1,1,1)",id,source,owner.id(),state);
        jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,state,dispatched) VALUES(?,?,0,'old','new',1,'Book','1','2',?,?)",id+"-item",id,itemState,dispatched);
    }
    WorkDeletion.Result apply(String source,boolean preview,WorkDeletion.Ref... refs) {return deletion.apply(source,new WorkDeletion.Selection(List.of(refs)),preview);}
    WorkDeletion.Ref ref(String id) {return new WorkDeletion.Ref(id,false);}
    void queue(String job,String state) {jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,epl_id,download_id,hash,state) VALUES(?,?,1,'record',?,?)",UUID.randomUUID().toString(),job,"A".repeat(40),state);}
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void removesOnlyHistoryAndClosedAttempts(String source) {
        job(source,"finished","COMPLETED");item(source,"finished","FAILED",null);
        jdbc.update("INSERT INTO job_retry_attempts(id,kind,job_id,username,created_at,finished_at,before_job) VALUES('attempt',?,'finished','Owner',1,2,'{}')",source);
        jdbc.update("INSERT INTO job_retry_attempt_items(attempt_id,item_id,position,phase,before_item) VALUES('attempt','finished-item',0,'SEND','{}')");
        assertThat(apply(source,true,ref("finished")).items().getFirst().allowed()).isTrue();
        assertThat(count(source.equals("downloads")?"torrent_bulk_jobs":"calibre_jobs")).isEqualTo(1);
        assertThat(apply(source,false,ref("finished")).items().getFirst().deleted()).isTrue();
        assertThat(count("job_retry_attempts")).isZero();assertThat(count("job_retry_attempt_items")).isZero();
        assertThat(apply(source,false,ref("finished")).items().getFirst().reason()).contains("ya no existe");
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void activeAndPausedJobsCannotBeDeleted(String source) {
        for(String state:source.equals("downloads")?List.of("QUEUED","RUNNING","RETRY_WAIT","CLEANUP_WAIT","PAUSED"):List.of("QUEUED","RUNNING","PAUSED")) {
            job(source,state,state);assertThat(apply(source,false,ref(state)).items().getFirst().reason()).contains("terminados o cancelados");
        }
        job(source,"cancelled","CANCELLED");assertThat(apply(source,false,ref("cancelled")).items().getFirst().deleted()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void terminalJobsWithUnfinishedOrUncertainItemsAreProtected(String source) {
        for(String state:source.equals("downloads")?List.of("PENDING","IN_FLIGHT"):List.of("PENDING","WAITING_DOWNLOAD","BLOCKED_DOWNLOAD","SENDING","UNCERTAIN","CLEANUP_PENDING")) {
            job(source,state,"CANCELLED");item(source,state,state,null);
            assertThat(apply(source,false,ref(state)).items().getFirst().reason()).contains("pendientes de resolver");
        }
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"})
    void workFiltersCombineMultipleTypesAndStatesWithExclusionAndPagination(String source) throws Exception {
        job(source,"a","QUEUED");job(source,"b","PAUSED");job(source,"d","COMPLETED");
        cleanupJob(source,"c","COMPLETED","SUCCEEDED",true);cleanupJob(source,"e","PAUSED","PENDING",false);
        boolean downloads=source.equals("downloads");
        if(downloads) jdbc.update("UPDATE torrent_bulk_jobs SET type='UPDATE' WHERE id='b'");
        String types=downloads?"DOWNLOAD,UPDATE":"SEND,CLEANUP", sort=downloads?"jobId,asc":"id,asc", idKey=downloads?"jobId":"id";
        var rows=listings.list(source,0,20,List.of("QUEUED","PAUSED"),types,"",null,null,sort);
        assertThat(rows.items().stream().map(row -> row.get(idKey)).toList()).containsExactlyElementsOf(downloads?List.of("a","b"):List.of("a","b","e"));
        var page=listings.list(source,1,1,List.of("QUEUED","PAUSED"),types,"",null,null,sort);
        assertThat(page.items()).singleElement().satisfies(row -> assertThat(row.get(idKey)).isEqualTo("b"));
        assertThat(page.meta().totalItems()).isEqualTo(downloads?2:3);
        var neighbors=listings.neighbors(source,"b",List.of("QUEUED","PAUSED"),types,"",null,null,sort);
        assertThat(neighbors.previous().id()).isEqualTo("a");
        assertThat(neighbors.next()==null?null:neighbors.next().id()).isEqualTo(downloads?null:"e");
        var excludedStates=listings.list(source,0,20,List.of("QUEUED","PAUSED"),types,"",null,null,sort,"ne","eq");
        assertThat(excludedStates.items().stream().map(row -> row.get(idKey)).toList()).containsExactlyElementsOf(downloads?List.of("d"):List.of("c","d"));
        var excludedTypes=listings.list(source,0,20,List.of("QUEUED","PAUSED"),types,"",null,null,sort,"eq","ne");
        assertThat(excludedTypes.items().stream().map(row -> row.get(idKey)).toList()).containsExactlyElementsOf(downloads?List.of("e"):List.of());
        assertThatThrownBy(()->listings.list(source,0,20,List.of(),types+",INVALID","",null,null,sort)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(()->listings.list(source,0,20,List.of("QUEUED","INVALID"),types,"",null,null,sort)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        String endpoint=downloads?"/api/torrent/jobs":"/api/calibre/transfers", stateKey=downloads?"status":"state";
        mvc.perform(get(endpoint).with(authentication(auth)).param("type",types).param(stateKey,"QUEUED,PAUSED").param("sort",sort))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(downloads?2:3));
        mvc.perform(get(endpoint).with(authentication(auth)).param("type",types).param("typeOperator","ne").param(stateKey,"QUEUED,PAUSED").param("sort",sort))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(downloads?1:0));
    }
    @Test void updateCleanupRequestsCannotBeLost() {
        for(String state:List.of("WAITING","BLOCKED","REQUESTED")) {
            job("downloads",state,"COMPLETED");queue(state,state);
            assertThat(apply("downloads",false,ref(state)).items().getFirst().reason()).contains("limpiezas");
        }
        job("downloads","resolved","COMPLETED");queue("resolved","REMOVED");
        assertThat(apply("downloads",false,ref("resolved")).items().getFirst().deleted()).isTrue();assertThat(count("torrent_update_cleanup")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void cleanupProtectsEveryUncertainOrDispatchedOutcome(String source) {
        for(String state:List.of("PENDING","RUNNING","VERIFYING","UNCERTAIN","FAILED","BLOCKED","CANCELLED")) {
            cleanupJob(source,state,"COMPLETED",state,true);
            assertThat(apply(source,false,new WorkDeletion.Ref(state,true)).items().getFirst().reason()).contains("confirmarse");
        }
        cleanupJob(source,"done","COMPLETED","SUCCEEDED",true);
        assertThat(apply(source,false,new WorkDeletion.Ref("done",true)).items().getFirst().deleted()).isTrue();
        cleanupJob(source,"notSent","CANCELLED","CANCELLED",false);
        assertThat(apply(source,false,new WorkDeletion.Ref("notSent",true)).items().getFirst().deleted()).isTrue();
    }
    @Test void deletedCleanupRequestCannotBeReplayedEvenWhenTheDownloadRecordIsKept() {
        String id=UUID.randomUUID().toString();
        cleanupJob("downloads",id,"COMPLETED","SUCCEEDED",true);
        assertThat(apply("downloads",false,new WorkDeletion.Ref(id,true)).items().getFirst().deleted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deleted_work_receipts WHERE request_id=?",Long.class,id)).isEqualTo(1);
        var request=new RevisionCleanup.RemovalInput(id,new com.rlibanez.eplsync.torrent.updates.HistoryActions.Removal(1L,List.of("record"),false,"torrent",true,true));
        assertThatThrownBy(()->cleanup.removeSelected(request)).hasMessageContaining("ya fue eliminado");
        assertThat(count("revision_cleanup_jobs")).isZero();assertThat(count("torrent_update_cleanup")).isZero();
    }
    @Test void calibreJobMustBeRemovedBeforeItsDownloadSourceEvenWhenCompleted() {
        job("downloads","torrent","COMPLETED");job("calibre","send","COMPLETED");item("calibre","send","ACCEPTED","torrent");
        var blocked=apply("downloads",false,ref("torrent")).items().getFirst();assertThat(blocked.relatedId()).isEqualTo("send");assertThat(blocked.relatedSource()).isEqualTo("calibre");
        assertThat(apply("calibre",false,ref("send")).items().getFirst().deleted()).isTrue();
        assertThat(apply("downloads",false,ref("torrent")).items().getFirst().deleted()).isTrue();
    }
    @Test void deletingALegacyRetryKeepsItsRequestTokenFromReopeningTheOriginalJob() {
        job("downloads","parent","COMPLETED");item("downloads","parent","FAILED",null);job("downloads","child","COMPLETED");
        String token=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO job_retry_jobs(kind,child_id,parent_id,root_id,request_id,created_at) VALUES('downloads','child','parent','parent',?,1)",token);
        assertThat(apply("downloads",false,ref("child")).items().getFirst().deleted()).isTrue();
        assertThatThrownBy(()->retries.create("downloads","parent",new JobRetryController.Confirmation(token,false)))
            .hasMessageContaining("ya fue eliminado");
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id='parent'",String.class)).isEqualTo("COMPLETED");
        assertThat(count("job_retry_attempts")).isZero();
    }
    @Test void calibreWaitingOnAnOriginalDownloadAlsoProtectsItsLegacyRetry() {
        job("downloads","parent","COMPLETED");job("downloads","child","COMPLETED");
        jdbc.update("INSERT INTO job_retry_jobs(kind,child_id,parent_id,root_id,request_id,created_at) VALUES('downloads','child','parent','parent','token',1)");
        job("calibre","send","PAUSED");item("calibre","send","WAITING_DOWNLOAD","parent");
        assertThat(apply("downloads",false,ref("child")).items().getFirst().relatedId()).isEqualTo("send");
        assertThat(count("job_retry_jobs")).isEqualTo(1);assertThat(count("torrent_bulk_jobs")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void retryChainsDeleteLeavesFirstAndNeverStrandAChild(String source) {
        job(source,"parent","COMPLETED");job(source,"child","COMPLETED");
        jdbc.update("INSERT INTO job_retry_jobs(kind,child_id,parent_id,root_id,request_id,created_at) VALUES(?,'child','parent','parent','token',1)",source);
        assertThat(apply(source,false,ref("parent")).items().getFirst().relatedId()).isEqualTo("child");
        assertThat(apply(source,true,ref("parent"),ref("child")).items()).allMatch(item -> item.allowed());
        assertThat(count("job_retry_jobs")).isEqualTo(1);
        assertThat(apply(source,false,ref("parent"),ref("child")).items()).allMatch(item -> item.deleted());
        assertThat(count("job_retry_jobs")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void automaticCyclesAndUnclosedAttemptsPreventDeletion(String source) {
        job(source,"auto","COMPLETED");
        jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_username,enabled,created_at,updated_at) VALUES('task','Task','TORRENT_AUTO_UPDATE','0 0 0 * * *','UTC','Owner',0,1,1)");
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,state,created_at,configuration,context,"+(source.equals("downloads")?"download_job_id":"calibre_job_id")+") VALUES('run','task','ATTENTION',1,'{}','context','auto')");
        jdbc.update("INSERT INTO scheduled_automation_jobs(run_id,kind,job_id) VALUES('run',?,'auto')",source);
        assertThat(apply(source,false,ref("auto")).items().getFirst().reason()).contains("ciclo");
        jdbc.update("UPDATE scheduled_automation_runs SET state='COMPLETED'");
        jdbc.update("INSERT INTO job_retry_attempts(id,kind,job_id,username,created_at,before_job) VALUES('attempt',?,'auto','Owner',1,'{}')",source);
        // A terminal retry can have a lazily frozen audit; it is not an active operation.
        assertThat(apply(source,false,ref("auto")).items().getFirst().deleted()).isTrue();
        assertThat(count("scheduled_tasks")).isEqualTo(1);assertThat(count("scheduled_automation_runs")).isEqualTo(1);assertThat(count("scheduled_automation_jobs")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"downloads","calibre"}) void activeCycleAlsoProtectsIndirectRetries(String source) {
        job(source,"parent","COMPLETED");job(source,"child","COMPLETED");
        jdbc.update("INSERT INTO job_retry_jobs(kind,child_id,parent_id,root_id,request_id,created_at) VALUES(?,'child','parent','parent','token',1)",source);
        jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_username,enabled,created_at,updated_at) VALUES('task','Task','TORRENT_AUTO_UPDATE','0 0 0 * * *','UTC','Owner',0,1,1)");
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,state,created_at,configuration,context) VALUES('run','task','WAITING',1,'{}','context')");
        jdbc.update("INSERT INTO scheduled_automation_jobs(run_id,kind,job_id) VALUES('run',?,'parent')",source);
        var preview=apply(source,true,ref("parent"),ref("child"));
        assertThat(preview.items()).noneMatch(item -> item.allowed());
        assertThat(preview.items().getLast().reason()).contains("ciclo");
        assertThat(apply(source,false,ref("parent"),ref("child")).items()).noneMatch(item -> item.deleted());
    }
    @Test void newDependencyCommittedWhileDeletionWaitsForTheWriterCannotBeMissed() throws Exception {
        job("downloads","torrent","COMPLETED");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var writer=executor.submit(()->new TransactionTemplate(manager).execute(status->{
                held.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("writer timeout");}catch(InterruptedException ex) {throw new RuntimeException(ex);}
                job("calibre","send","QUEUED");item("calibre","send","WAITING_DOWNLOAD","torrent");return null;
            }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var deleting=executor.submit(()->{login(owner);try {return apply("downloads",false,ref("torrent"));}finally {SecurityContextHolder.clearContext();}});
            try {assertThatThrownBy(()->deleting.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            writer.get(5,TimeUnit.SECONDS);
            assertThat(deleting.get(5,TimeUnit.SECONDS).items().getFirst().relatedId()).isEqualTo("send");
        } finally {release.countDown();}
        assertThat(count("torrent_bulk_jobs")).isEqualTo(1);
    }
    @Test void anExecutorInProgressCannotRaceHistoryRemoval() throws Exception {
        job("calibre","old","COMPLETED");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(()->transfers.preparing(()->{held.countDown();try {if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("worker timeout");}catch(InterruptedException ex) {throw new RuntimeException(ex);}return null;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            try {assertThatThrownBy(()->apply("calibre",false,ref("old"))).isInstanceOf(CalibreTransfers.OperationBusy.class);}
            finally {release.countDown();}
            worker.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertThat(apply("calibre",false,ref("old")).items().getFirst().deleted()).isTrue();
    }
    @Test void dependencyLinksRespectTheRelatedJobsReadPermission() {
        job("downloads","torrent","COMPLETED");job("calibre","send","PAUSED");item("calibre","send","WAITING_DOWNLOAD","torrent");
        var reader=new Account("reader","Reader","reader@example.com","USER","ACTIVE",false,null,1,Set.of(Permission.TORRENT_JOBS_MANAGE),Instant.now());login(reader);
        var blocked=apply("downloads",false,ref("torrent")).items().getFirst();
        assertThat(blocked.reason()).contains("Calibre");assertThat(blocked.relatedId()).isNull();assertThat(blocked.relatedSource()).isNull();
    }
    @Test void deletionRechecksChangesAfterPreviewAndOnlyDeletesEligibleRows() {
        job("downloads","one","COMPLETED");job("downloads","two","COMPLETED");
        assertThat(apply("downloads",true,ref("one"),ref("two")).items()).allMatch(item -> item.allowed());
        jdbc.update("UPDATE torrent_bulk_jobs SET state='QUEUED' WHERE id='two'");
        var result=apply("downloads",false,ref("one"),ref("two"));
        assertThat(result.items().getFirst().deleted()).isTrue();assertThat(result.items().getLast().allowed()).isFalse();
        assertThat(jdbc.queryForList("SELECT id FROM torrent_bulk_jobs",String.class)).containsExactly("two");
    }
    @Test void writeFailureRollsBackTheWholeBatchWithoutLosingItems() {
        job("downloads","one","COMPLETED");job("downloads","two","COMPLETED");item("downloads","one","ACCEPTED",null);
        jdbc.execute("CREATE TRIGGER fail_work_deletion BEFORE DELETE ON torrent_bulk_jobs WHEN OLD.id='two' BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try {assertThatThrownBy(()->apply("downloads",false,ref("one"),ref("two"))).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("DROP TRIGGER fail_work_deletion");}
        assertThat(count("torrent_bulk_jobs")).isEqualTo(2);assertThat(count("torrent_bulk_items")).isEqualTo(1);
    }
    @Test void permissionsOwnershipCsrfAndInvalidSelectionsAreEnforced() throws Exception {
        job("downloads","normal","COMPLETED");cleanupJob("downloads","cleanup","COMPLETED","SUCCEEDED",true);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(post("/api/settings/work/downloads/deletions").with(authentication(auth)).contentType("application/json").content("{\"jobs\":[{\"id\":\"normal\",\"cleanup\":false}]}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/work/downloads/deletions/preview").with(authentication(auth)).with(csrf()).contentType("application/json").content("{\"jobs\":[{\"id\":\"normal\",\"cleanup\":false}]}" )).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].allowed").value(true));
        accounts.create("Reader","reader@example.com","USER",owner.id());
        jdbc.update("UPDATE users SET must_change_password=0 WHERE username='Reader'");
        var readerId=jdbc.queryForObject("SELECT id FROM users WHERE username='Reader'",String.class);
        var reader=accounts.find(readerId).authenticatedNow();login(reader);
        var readerAuth=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(post("/api/settings/work/downloads/deletions").with(authentication(readerAuth)).with(csrf()).contentType("application/json").content("{\"jobs\":[{\"id\":\"normal\",\"cleanup\":false}]}" )).andExpect(status().isForbidden());
        assertThat(apply("downloads",false,ref("normal")).items().getFirst().reason()).contains("permiso");
        var other=new Account("other","Other","other@example.com","USER","ACTIVE",false,null,1,Set.of(Permission.TORRENT_SYNC,Permission.DOWNLOADS_DELETE),Instant.now());login(other);
        assertThat(apply("downloads",false,new WorkDeletion.Ref("cleanup",true)).items().getFirst().reason()).contains("tu cuenta");
        login(owner);
        assertThatThrownBy(()->apply("downloads",false,ref("normal"),ref("normal"))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(count("torrent_bulk_jobs")).isEqualTo(1);assertThat(count("revision_cleanup_jobs")).isEqualTo(1);
    }
}
