package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.settings.CalibreClient;
import com.rlibanez.eplsync.service.TorrentClientService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class BookActivityTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    @MockitoBean CalibreClient client;
    @MockitoSpyBean TorrentClientService torrent;
    MockMvc mvc; Account owner;
    @BeforeEach void setup() {
        for(String table:List.of("job_retry_attempt_items","job_retry_attempts","revision_cleanup_items","revision_cleanup_jobs","torrent_update_cleanup","torrent_bulk_items","torrent_bulk_jobs","calibre_job_items","calibre_jobs","torrent_downloads","catalog_books")) jdbc.update("DELETE FROM "+table);
        accounts.clear();owner=accounts.initialize("owner","","activity password","activity password");
        for(int id:List.of(1,2)) jdbc.update("INSERT INTO catalog_books(epl_id,title,author,revision) VALUES(?,?,'Author',1)",id,"Book "+id);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clearInvocations(client,torrent);
    }
    void bulk(String id,long book,String type,String state,long time) {
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state,created_at,updated_at) VALUES(?,1,1,0,1,?,'COMPLETED',?,?)",id,type,time,time);
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,epl_id,hash,revision,state,message) VALUES(?,?,0,1,?,'hash',1,?,?)",id+"-item",id,book,state,"Detail "+id);
    }
    void calibre(String id,long book,String state) {
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES(?,'2026-10-19T00:00:00Z','2026-10-19T00:00:00Z','owner','target','main','source','COMPLETED')",id);
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message) VALUES(?,?,0,'record',0,'Book.epub',1,?,'1',?,'Calibre detail')",id+"-item",id,book,state);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor permissions(String... extra) {
        var values=new ArrayList<String>(List.of("CATALOG_READ"));values.addAll(List.of(extra));
        return user("reader").authorities(values.stream().map(SimpleGrantedAuthority::new).toList());
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor own(String... extra) {
        var values=new ArrayList<String>(List.of("CATALOG_READ"));values.addAll(List.of(extra));
        return authentication(new UsernamePasswordAuthenticationToken(owner,"",values.stream().map(SimpleGrantedAuthority::new).toList()));
    }
    @Test void combinesBookWorkWithGlobalFilteringSortingAndPaginationWithoutClientCalls() throws Exception {
        bulk("failed",1,"DOWNLOAD","FAILED",1000);bulk("update",1,"UPDATE","ACCEPTED",1000);bulk("other",2,"DOWNLOAD","ACCEPTED",999999);
        calibre("send",1,"ACCEPTED");
        var auth=permissions("TORRENT_JOBS_MANAGE","CALIBRE_SEND");
        mvc.perform(get("/api/catalog/books/1/activity").with(auth).param("size","1"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.meta.totalItems").value(3)).andExpect(jsonPath("$.items[0].operation").value("CALIBRE_SEND"))
            .andExpect(jsonPath("$.items[0].href").value("/calibre/jobs/send"));
        mvc.perform(get("/api/catalog/books/1/activity").with(auth).param("size","1").param("page","1"))
            .andExpect(jsonPath("$.items[0].id").value("download:update-item"));
        mvc.perform(get("/api/catalog/books/1/activity").with(auth).param("operation","DOWNLOAD,DOWNLOAD_UPDATE").param("result","FAILED"))
            .andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value("download:failed-item"));
        mvc.perform(get("/api/catalog/books/1/activity").with(auth).param("operation","DOWNLOAD_UPDATE").param("operationOperator","ne").param("result","FAILED").param("resultOperator","ne"))
            .andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].operation").value("CALIBRE_SEND"));
        verifyNoInteractions(client,torrent);
    }
    @Test void sourcePermissionsHideWorkCountsDetailsAndLinks() throws Exception {
        bulk("private",1,"DOWNLOAD","FAILED",1000);calibre("private-calibre",1,"FAILED");
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,hash,client,client_instance_id,status,origin,created_at,completed_at,revision) VALUES('download',1,'hash','qbittorrent','client','DOWNLOADED','EPLSYNC',1000,2000,1)");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions())).andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("CALIBRE_READ"))).andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("BOOK_HISTORY_READ")))
            .andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].operation").value("DOWNLOAD_COMPLETED"))
            .andExpect(jsonPath("$.items[0].message").isEmpty()).andExpect(jsonPath("$.items[0].href").isEmpty());
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("TORRENT_JOBS_MANAGE"))).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("CALIBRE_JOBS_MANAGE"))).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/catalog/books/1/activity").with(user("no-catalog").authorities(new SimpleGrantedAuthority("TORRENT_JOBS_MANAGE"))))
            .andExpect(status().isForbidden());
    }
    void cleanup(String id,String source,String user,String state,boolean direct) {
        jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,confirmation,direct_removal,created_at,updated_at,expires_at) VALUES(?,?,'BOOK',?,'owner','context',?,? ,?,3000,4000,999999)",id,source,user,state,"[\""+id+"-item\"]",direct);
        jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,state,message) VALUES(?,?,0,'old','new',1,'Book','1','2','BLOCKED','protected')",id+"-item",id);
    }
    @Test void cleanupKeepsOwnershipAndSourcePermissionsAndDoesNotRepeatQueueRecords() throws Exception {
        String other=accounts.create("other","","USER",owner.id()).user().id();
        cleanup("mine","calibre",owner.id(),"COMPLETED",true);cleanup("foreign","calibre",other,"COMPLETED",true);cleanup("preview","calibre",owner.id(),"PREVIEW",true);
        cleanup("torrent","downloads",owner.id(),"COMPLETED",false);
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state) VALUES('queue','revision:torrent','old',1,'hash','REQUESTED')");
        mvc.perform(get("/api/catalog/books/1/activity").with(own("CALIBRE_READ")))
            .andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].operation").value("CALIBRE_DELETE"))
            .andExpect(jsonPath("$.items[0].href").value("/calibre/jobs/cleanup/mine"));
        mvc.perform(get("/api/catalog/books/1/activity").with(own("TORRENT_SYNC","TORRENT_JOBS_MANAGE")))
            .andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].href").value("/downloads/jobs/cleanup/torrent"));
    }
    @Test void retrySnapshotsKeepTheInitialFailureAndTheFrozenRetryResult() throws Exception {
        bulk("job",1,"DOWNLOAD","ACCEPTED",1000);
        jdbc.update("INSERT INTO job_retry_attempts(id,kind,job_id,username,created_at,finished_at,result,before_job) VALUES('retry','downloads','job','owner',2000,3000,'COMPLETED','{}')");
        jdbc.update("INSERT INTO job_retry_attempt_items(attempt_id,item_id,position,phase,before_item,after_item) VALUES('retry','job-item',0,'SEND',?,?)","{\"epl_id\":1,\"state\":\"FAILED\",\"message\":\"first failure\",\"revision\":1}","{\"state\":\"ACCEPTED\",\"message\":\"retry accepted\"}");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("TORRENT_JOBS_MANAGE")))
            .andExpect(jsonPath("$.meta.totalItems").value(2)).andExpect(jsonPath("$.items[0].operation").value("DOWNLOAD_RETRY"))
            .andExpect(jsonPath("$.items[0].result").value("SUCCEEDED")).andExpect(jsonPath("$.items[1].result").value("FAILED"))
            .andExpect(jsonPath("$.items[1].message").value("first failure"));
        jdbc.update("UPDATE torrent_bulk_items SET state='FAILED',message='later failure'");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("TORRENT_JOBS_MANAGE")).param("operation","DOWNLOAD_RETRY"))
            .andExpect(jsonPath("$.items[0].message").value("retry accepted"));
    }
    @Test void normalizesPendingPausedUncertainAndTerminalResults() throws Exception {
        var states=Map.of("PENDING","PENDING","IN_FLIGHT","RUNNING","FAILED","FAILED","SKIPPED","SKIPPED","CANCELLED","CANCELLED","ACCEPTED","SUCCEEDED");
        for(var state:states.entrySet()) {
            bulk(state.getKey(),1,"DOWNLOAD",state.getKey(),1000);
            mvc.perform(get("/api/catalog/books/1/activity").with(permissions("TORRENT_JOBS_MANAGE")).param("result",state.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].result").value(state.getValue()));
        }
        bulk("paused",1,"DOWNLOAD","PENDING",2000);
        jdbc.update("UPDATE torrent_bulk_jobs SET state='PAUSED' WHERE id='paused'");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("TORRENT_JOBS_MANAGE")).param("result","PAUSED"))
            .andExpect(jsonPath("$.meta.totalItems").value(1));
        calibre("uncertain",1,"UNCERTAIN");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("CALIBRE_SEND")).param("result","UNCERTAIN"))
            .andExpect(jsonPath("$.meta.totalItems").value(1));
        calibre("cleanup-pending",1,"CLEANUP_PENDING");
        jdbc.update("UPDATE calibre_job_items SET completion_state='ACCEPTED' WHERE id='cleanup-pending-item'");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("CALIBRE_SEND")).param("result","SUCCEEDED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].result").value("SUCCEEDED"));
    }
    @Test void paginationHasNoRepeatedRowsAcrossAllSourcesAndHidesUnselectedPreviewItems() throws Exception {
        bulk("submission",1,"DOWNLOAD","ACCEPTED",3000);
        calibre("sent",1,"ACCEPTED");
        cleanup("removal","downloads",owner.id(),"COMPLETED",true);
        jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,state,message) VALUES('unselected','removal',1,'old2','new',1,'Book','1','2','SKIPPED','not selected')");
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state) VALUES('internal','revision:removal','old',1,'hash','REQUESTED')");
        var ids=new HashSet<String>();
        var json=new tools.jackson.databind.json.JsonMapper();
        for(int page=0;page<3;page++) {
            var response=mvc.perform(get("/api/catalog/books/1/activity").with(own("TORRENT_SYNC","TORRENT_JOBS_MANAGE","CALIBRE_SEND")).param("size","1").param("page",String.valueOf(page)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(3)).andReturn();
            assertThat(ids.add(json.readTree(response.getResponse().getContentAsString()).path("items").get(0).path("id").asString())).isTrue();
        }
        assertThat(ids).containsExactlyInAnyOrder("download:submission-item","calibre:sent-item","cleanup:removal-item");
        mvc.perform(get("/api/catalog/books/1/activity").with(own("TORRENT_SYNC","TORRENT_JOBS_MANAGE","CALIBRE_SEND")).param("size","1").param("page","3"))
            .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.meta.totalItems").value(3));
    }
    @Test void calibreRetryAndAutomaticCleanupKeepDistinctOperationsAndValidJobLinks() throws Exception {
        calibre("sent",1,"ACCEPTED");
        jdbc.update("UPDATE calibre_job_items SET cleanup_policy='BOOK',cleanup_ids='[2]',calibre_id=3 WHERE id='sent-item'");
        jdbc.update("INSERT INTO job_retry_attempts(id,kind,job_id,username,created_at,finished_at,result,before_job) VALUES('retry','calibre','sent','owner',2000,3000,'COMPLETED','{}')");
        jdbc.update("INSERT INTO job_retry_attempt_items(attempt_id,item_id,position,phase,before_item,after_item) VALUES('retry','sent-item',0,'SEND',?,?)","{\"epl_id\":1,\"state\":\"FAILED\",\"message\":\"failure\",\"revision\":1}","{\"state\":\"ACCEPTED\",\"message\":\"accepted\"}");
        mvc.perform(get("/api/catalog/books/1/activity").with(permissions("CALIBRE_SEND")).param("sort","operation,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(3))
            .andExpect(jsonPath("$.items[0].operation").value("CALIBRE_CLEANUP"))
            .andExpect(jsonPath("$.items[1].operation").value("CALIBRE_RETRY"))
            .andExpect(jsonPath("$.items[2].operation").value("CALIBRE_SEND"))
            .andExpect(jsonPath("$.items[*].href").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("/calibre/jobs/sent"))));
    }
    @Test void validatesFiltersAndPaginationAndHandlesMissingAndEmptyBooks() throws Exception {
        for(String query:List.of("page=-1","size=0","page=2147483647&size=1000","result=UNKNOWN","operation=DOWNLOAD,' OR 1=1","resultOperator=invalid","operation=DOWNLOAD&operation=CALIBRE_SEND","sort=id,asc","unknown=value"))
            mvc.perform(get("/api/catalog/books/1/activity?"+query).with(permissions("TORRENT_JOBS_MANAGE"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books/999/activity").with(permissions())).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/books/2/activity").with(permissions("TORRENT_JOBS_MANAGE","CALIBRE_SEND")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
}
