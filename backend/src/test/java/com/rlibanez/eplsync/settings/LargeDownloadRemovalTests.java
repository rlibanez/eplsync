package com.rlibanez.eplsync.settings;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@org.springframework.boot.test.context.SpringBootTest(properties={"eplsync.revision-cleanup.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.tasks.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LargeDownloadRemovalTests {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired RevisionCleanup cleanup;
    @org.springframework.beans.factory.annotation.Autowired WorkListings listings;
    @org.springframework.beans.factory.annotation.Autowired com.rlibanez.eplsync.security.AccountStore accounts;
    @org.springframework.beans.factory.annotation.Autowired CalibreSettings settings;
    @org.springframework.beans.factory.annotation.Autowired com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService tracking;
    @org.springframework.beans.factory.annotation.Autowired com.rlibanez.eplsync.torrent.updates.HistoryActions history;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.rlibanez.eplsync.service.TorrentClientService torrent;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.rlibanez.eplsync.torrent.downloads.EpubFiles files;
    @org.springframework.test.context.bean.override.mockito.MockitoBean CalibreClient client;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager contextManager;
    @org.springframework.beans.factory.annotation.Autowired CalibreTransfers transfers;
    @org.junit.jupiter.api.BeforeEach void reset() {
        jdbc.setQueryTimeout(0);
        jdbc.update("DELETE FROM revision_cleanup_jobs");jdbc.update("DELETE FROM torrent_update_cleanup");
        jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM torrent_downloads");accounts.clear();
    }
    com.rlibanez.eplsync.security.Account owner;
    void login() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner,null,owner.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));
    }
    @org.junit.jupiter.api.AfterEach void logout() {org.springframework.security.core.context.SecurityContextHolder.clearContext();}

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("large-removal.db"));
        registry.add("spring.datasource.hikari.maximum-pool-size",()->2);
        registry.add("spring.datasource.hikari.connection-timeout",()->1000);
    }
    @Test void removes73000WithoutStarvingReaders() throws Exception {
        owner=accounts.initialize("Owner","owner@example.com","password123","password123");login();
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        var ids=new ArrayList<String>();var batch=new ArrayList<Object[]>();
        for(int i=0;i<73000;i++) {String id=UUID.randomUUID().toString();ids.add(id);batch.add(new Object[]{id,10000L+i,String.format("%040x",i),tracking.instanceId()});}
        var transaction=new org.springframework.transaction.support.TransactionTemplate(contextManager);
        transaction.executeWithoutResult(status->jdbc.batchUpdate("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,1,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',CURRENT_TIMESTAMP)",batch));
        var input=new RevisionCleanup.RemovalInput(UUID.randomUUID().toString(),new com.rlibanez.eplsync.torrent.updates.HistoryActions.Removal(null,ids,true,"files",true,true));
        var done=new java.util.concurrent.atomic.AtomicBoolean();
        try(var executor=Executors.newFixedThreadPool(3)) {
            var reader=executor.submit(()-> {int checks=0;while(!done.get()) {assertThat(accounts.find(owner.id())).isNotNull();checks++;Thread.sleep(10);}return checks;});
            try {
                assertThat(history.previewRecords(new com.rlibanez.eplsync.torrent.updates.HistoryActions.Selection(null,ids,null,null))).hasSize(73000);
                var job=cleanup.removeSelected(input);
                assertThat(job.total()).isEqualTo(73000);
                assertThat(jdbc.queryForList("EXPLAIN QUERY PLAN SELECT id FROM torrent_downloads WHERE client_instance_id=? AND hash=?",tracking.instanceId(),batch.getFirst()[2]).toString()).contains("idx_download_client_hash");
                assertThat(jdbc.queryForList("EXPLAIN QUERY PLAN SELECT count(*) FROM torrent_update_cleanup WHERE download_id=? AND state IN ('WAITING','BLOCKED','REQUESTED')",ids.getFirst()).toString()).contains("idx_cleanup_download_state");
                jdbc.setQueryTimeout(2);
                var concurrentListing=executor.submit(()-> {login();try {return listings.list("downloads",0,20,List.of(),"",null,null,null,"createdAt,desc","eq","eq").items().size();}finally {logout();}});
                long listingStart=System.nanoTime();
                assertThat(listings.list("downloads",0,20,List.of(),"",null,null,null,"createdAt,desc","eq","eq").items()).hasSize(1);
                System.out.println("LARGE_REMOVAL_LISTING_MS="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-listingStart));
                assertThat(concurrentListing.get(10,TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(cleanup.jobs("downloads",0,20).items()).hasSize(1);
                jdbc.update("UPDATE revision_cleanup_items SET state='SKIPPED' WHERE job_id=? AND position=0",job.id());
                var listed=listings.list("downloads",0,20,List.of(),"CLEANUP",null,null,null,"createdAt,desc").items().getFirst();
                assertThat(((Number)listed.get("processedItems")).longValue()).isEqualTo(1);
                assertThat(cleanup.removeSelected(input).id()).isEqualTo(job.id());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items WHERE job_id=?",Long.class,job.id())).isEqualTo(73000);
                var remote=new ConcurrentHashMap<String,com.rlibanez.eplsync.torrent.downloads.RemoteTorrent>();
                for(var entry:batch) {String hash=(String)entry[2];remote.put(hash,new com.rlibanez.eplsync.torrent.downloads.RemoteTorrent(hash,com.rlibanez.eplsync.torrent.downloads.DownloadStatus.DOWNLOADED,java.time.Instant.now(),Set.of(),"/downloads/"+hash+".epub","Book","/downloads",false));}
                var adapter=org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.TorrentClient.class);
                org.mockito.Mockito.when(adapter.listTorrents()).thenAnswer(call->List.copyOf(remote.values()));
                org.mockito.Mockito.when(adapter.listTorrents(org.mockito.ArgumentMatchers.anySet())).thenAnswer(call->List.copyOf(remote.values()));
                var deleted=ConcurrentHashMap.<String>newKeySet();
                org.mockito.Mockito.doAnswer(call->{String hash=call.getArgument(0);assertThat(deleted.add(hash)).as("no duplicate delete").isTrue();remote.remove(hash);return null;}).when(adapter).deleteTorrent(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(true));
                org.mockito.Mockito.when(torrent.exclusiveClient(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{java.util.function.Function<com.rlibanez.eplsync.torrent.TorrentClient,?> action=call.getArgument(0);return action.apply(adapter);});
                cleanup.cycle();
                assertThat(deleted).isNotEmpty();
                int firstCycle=deleted.size();
                jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING' WHERE job_id=? AND state='SUCCEEDED'",job.id());
                cleanup.recover();cleanup.cycle();
                assertThat(deleted.size()).as("restart verifies the whole interrupted batch without resending").isEqualTo(firstCycle);
                cleanup.cycle();assertThat(deleted.size()).isGreaterThan(firstCycle);
                assertThat(cleanup.get(job.id()).succeeded()).isGreaterThanOrEqualTo(firstCycle);
                assertThat(cleanup.removeSelected(input).id()).isEqualTo(job.id());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs",Long.class)).isEqualTo(1);
            } finally {done.set(true);}
            assertThat(reader.get(10,TimeUnit.SECONDS)).isGreaterThan(0);
        }
    }
    @Test void calibreSendAndCleanupQueriesHandle73000ItemsWithConcurrentReaders() throws Exception {
        owner=accounts.initialize("Owner","owner@example.com","password123","password123");login();
        String send=UUID.randomUUID().toString(),empty=UUID.randomUUID().toString(),removal=UUID.randomUUID().toString();
        String now=java.time.Instant.now().toString();long epoch=System.currentTimeMillis();
        var ids=new ArrayList<String>();var values=new ArrayList<Object[]>();
        for(int n=0;n<73000;n++) {String id=UUID.randomUUID().toString();ids.add(id);values.add(new Object[]{id,send,n,10000L+n});}
        new org.springframework.transaction.support.TransactionTemplate(contextManager).executeWithoutResult(status->{
            for(String id:List.of(send,empty)) jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,'test','main','catalog','PAUSED')",id,now,now,owner.id(),owner.username());
            jdbc.batchUpdate("INSERT INTO calibre_job_items(id,job_id,position,name,epl_id,revision,state) VALUES(?,?,?,'Book',?,'1','WAITING_DOWNLOAD')",values);
            jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,library,state,confirmation,created_at,updated_at,expires_at,direct_removal) VALUES(?,'calibre','BOOK',?,?,'test','main','COMPLETED',?,?,?,?,1)",removal,owner.id(),owner.username(),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(ids),epoch,epoch,Long.MAX_VALUE);
            jdbc.update("INSERT INTO revision_cleanup_items(id,job_id,position,old_id,replacement_id,epl_id,title,revision,replacement_revision,formats,state) SELECT id,?,position,cast(epl_id AS TEXT),'',epl_id,name,revision,'','[]','SUCCEEDED' FROM calibre_job_items WHERE job_id=?",removal,send);
        });
        jdbc.setQueryTimeout(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var reader=executor.submit(()->{for(int n=0;n<100;n++) {assertThat(accounts.find(owner.id())).isNotNull();Thread.sleep(5);}return true;});
            var parallel=executor.submit(()->{login();try {return listings.list("calibre",0,20,List.of(),"",null,null,null,"createdAt,desc").items().size();}finally {logout();}});
            assertThat(transfers.job(send).pending()).isEqualTo(73000);
            assertThat(transfers.job(empty).total()).isZero();
            for(String field:List.of("total","accepted","failed","createdAt"))
                assertThat(transfers.jobs(0,20,"",List.of(field+",desc")).items()).hasSize(2);
            for(String field:List.of("total","accepted","failed","library","state"))
                assertThat(listings.list("calibre",0,20,List.of(),"",null,null,null,field+",desc").items()).hasSize(3);
            assertThat(cleanup.get(removal).selected()).isEqualTo(73000);
            assertThat(cleanup.get(removal).succeeded()).isEqualTo(73000);
            assertThat(cleanup.jobs("calibre",0,20).items()).hasSize(1);
            assertThat(parallel.get(10,TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(reader.get(10,TimeUnit.SECONDS)).isTrue();
        }
    }

}
