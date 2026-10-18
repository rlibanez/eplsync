package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class HomeActivityTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    MockMvc mvc;
    String owner;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM revision_cleanup_items");jdbc.update("DELETE FROM revision_cleanup_jobs");
        jdbc.update("DELETE FROM torrent_update_cleanup");
        jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");
        jdbc.update("DELETE FROM calibre_job_items");jdbc.update("DELETE FROM calibre_jobs");
        jdbc.update("DELETE FROM scheduled_tasks");
        jdbc.update("DELETE FROM app_settings WHERE setting_key='calibre.connection'");
        accounts.clear();owner=accounts.initialize("owner","","test password for home activity","test password for home activity").id();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    void bulk(String id,String state,String message) {
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,state,message,updated_at) VALUES(?,1,1,0,1,?,?,?)",id,state,message,1000);
    }
    void calibre(String id,String state) {
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES(?,'2026-10-18T00:00:00Z','2026-10-18T01:00:00Z','owner','target','main','source','COMPLETED')",id);
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message) VALUES(?,?,0,'record',0,'Book.epub',1,1,'1',?,'Revisar EPUB')",id,id,state);
    }
    @Test void attentionIsLocalBoundedPermissionScopedAndIgnoresUserPausedAndSuccessfulJobs() throws Exception {
        bulk("paused-by-user","PAUSED",null);bulk("ok","COMPLETED",null);
        for(int n=0;n<12;n++) bulk("blocked-"+n,"PAUSED","Cliente no disponible");
        mvc.perform(get("/api/home/attention").with(user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/home/attention").with(user("manager").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_JOBS_MANAGE"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.total").value(12)).andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].message").value("Cliente no disponible"))
            .andExpect(jsonPath("$.items[0].href").value(org.hamcrest.Matchers.startsWith("/downloads/jobs/")));
    }
    @Test void pendingDeletionRemainsVisibleAfterCancellationAndMultipleIssuesDoNotDuplicateAJob() throws Exception {
        bulk("cancelled","CANCELLED",null);
        for(int n=0;n<2;n++) jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state,message) VALUES(?,'cancelled',?,1,'hash','REQUESTED','Borrado pendiente de confirmación')","cleanup-"+n,"download-"+n);
        var auth=user("manager").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_JOBS_MANAGE"));
        mvc.perform(get("/api/home/attention").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].message").value("Borrado pendiente de confirmación"));
        jdbc.update("UPDATE torrent_update_cleanup SET state='REMOVED'");
        mvc.perform(get("/api/home/attention").with(auth)).andExpect(jsonPath("$.total").value(0));
    }
    @Test void calibreRequiresConfigurationAndIncludesUncertainButNotSuccessfulOrCancelledFailures() throws Exception {
        calibre("uncertain","UNCERTAIN");calibre("failed","FAILED");calibre("ok","ACCEPTED");
        jdbc.update("UPDATE calibre_jobs SET state='CANCELLED' WHERE id='failed'");
        var auth=user("sender").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALIBRE_SEND"));
        mvc.perform(get("/api/home/attention").with(auth)).andExpect(jsonPath("$.total").value(0));
        jdbc.update("INSERT INTO app_settings VALUES('calibre.connection',?)","{\"url\":\"http://calibre.test:8081\",\"username\":\"\",\"password\":\"\",\"library\":\"main\",\"browserUrl\":\"\"}");
        mvc.perform(get("/api/home/attention").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value("uncertain")).andExpect(jsonPath("$.items[0].href").value("/calibre/jobs/uncertain"));
    }
    @Test void cleanupRemainsPrivateToItsOwner() throws Exception {
        jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,created_at,updated_at,expires_at) VALUES('cleanup','downloads','FILES',?,'owner','ctx','COMPLETED',1,2,3)",owner);
        jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,state,message) VALUES('item','cleanup',0,'old','new',1,'Book','1','2','UNCERTAIN','Sin confirmar')");
        mvc.perform(get("/api/home/attention").with(user("other").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_SYNC"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        var account=accounts.find(owner).authenticatedNow();
        var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_SYNC")));
        mvc.perform(get("/api/home/attention").with(authentication(authentication)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].href").value("/downloads/jobs/cleanup/cleanup"));
    }
    @Test void tasksAreAdminOnlyBoundedOrderedAndDoNotExposeConfiguration() throws Exception {
        mvc.perform(get("/api/home/tasks").with(user("reader"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/home/attention")).andExpect(status().isUnauthorized());
        for(int n=0;n<12;n++) jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_username,enabled,next_run,created_at,updated_at,last_result) VALUES(?,?,'TORRENT_SYNC','0 5 * * *','UTC','owner',1,?,0,0,'SUCCEEDED')","task-"+n,"Task "+n,(12-n)*1000);
        mvc.perform(get("/api/home/tasks").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(12)).andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].id").value("task-11"))
            .andExpect(jsonPath("$.items[0].lastResult").value("SUCCEEDED"))
            .andExpect(jsonPath("$.items[0].cron").doesNotExist()).andExpect(jsonPath("$.items[0].ownerId").doesNotExist());
    }
}
