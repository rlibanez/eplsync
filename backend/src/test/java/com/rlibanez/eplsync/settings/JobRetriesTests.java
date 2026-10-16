package com.rlibanez.eplsync.settings;

import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import com.rlibanez.eplsync.jobs.JobRetryController;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.tasks.worker-enabled=false","eplsync.torrent.enabled=true","eplsync.torrent.qbittorrent.auth.api-key=test-retry-api-key","eplsync.torrent.cleanup.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@WithMockUser(authorities={"ROLE_ADMIN","SETTINGS_MANAGE","CALIBRE_READ","CALIBRE_SEND","CALIBRE_JOBS_MANAGE","CALIBRE_DELETE","DOWNLOADS_FILES_READ","DOWNLOADS_DELETE","TORRENT_JOBS_MANAGE","TORRENT_SEND","TORRENT_SYNC","TORRENT_CLEANUP","TORRENT_FILES_DELETE"})
class JobRetriesTests {
    @Autowired JobRetries retries;
    @Autowired JdbcTemplate jdbc;
    @Autowired TorrentProperties torrent;
    @Autowired DownloadTrackingService tracking;
    @Autowired CalibreSettings settings;
    @Autowired CalibreTransfers transfers;
    @Autowired CalibreUpdateFlow updateFlow;
    @Autowired AccountStore accounts;
    @Autowired BulkStore bulk;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkRetentionStore retention;
    @Autowired com.rlibanez.eplsync.maintenance.DatabaseResetService reset;
    @MockitoBean SessionAccess sessions;
    @MockitoBean EpubFiles files;
    @MockitoBean CalibreClient client;
    Account owner;
    byte[] epub;
    JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM job_retry_items");jdbc.update("DELETE FROM job_retry_jobs");jdbc.update("DELETE FROM job_retry_previews");
        jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM torrent_update_cleanup");jdbc.update("DELETE FROM torrent_update_plans");jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");jdbc.update("DELETE FROM torrent_downloads");
        accounts.clear();owner=accounts.initialize("RetryOwner","retry@example.org","password123","password123");
        when(sessions.current()).thenReturn(owner);
        settings.save(new CalibreSettings.Input("http://calibre.test","reader","",true,"main"));
        epub=CalibreTransfersTests.epub(1,"1.1");
        when(files.list(anyString())).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",epub.length)));
        when(files.download(anyString(),eq(0))).thenAnswer(c->new EpubFiles.Opened("book.epub",epub.length,new java.io.ByteArrayInputStream(epub)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true));
        when(client.hasEpub(any(),anyLong())).thenReturn(true);
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenReturn(9L);
        when(client.findRevision(any(),anyLong(),any())).thenReturn(null);
    }
    String digest(String s) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    String torrentJob(String state,String... states) throws Exception {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,state,type,client,target_fingerprint,multiple_hashes,batch_size,concurrency,interval_millis,selected_books,created_at,updated_at,event_actor_id,event_actor_username,event_actor_kind,event_origin) VALUES(?,?,'DOWNLOAD',?,?,'FIRST',10,2,500,?,0,0,?,?,'USER','MANUAL')",id,state,torrent.getClient(),digest(torrent.getClient()+"\n"+torrent.getBaseUrl().replaceAll("/+$","")),states.length,owner.id(),owner.username());
        for(int i=0;i<states.length;i++) {
            var book=new com.rlibanez.eplsync.model.CatalogBook();book.setEplId((long)i+1);book.setRevision(1.1);book.setTitle("Frozen title");
            String hash=String.format("%040d",i+1);
            var command=new com.rlibanez.eplsync.torrent.TorrentDownload(hash,"magnet:?xt=urn:btih:"+hash,true,"/downloads/original","Frozen name",null,book);
            jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,epl_id,hash,revision,state,attempts,message,command_json) VALUES(?,?,?,?,?,1.1,?,2,'Original failure',?)",id+"-"+i,id,i,i+1,hash,states[i],json.writeValueAsString(command));
        }
        return id;
    }
    String calibreJob(String state,String... states) throws Exception {
        String id=UUID.randomUUID().toString(),source=digest(tracking.instanceId()+"\n"+torrent.getFiles().getRemoteRoot()+"\n"+torrent.getFiles().getLocalRoot());
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,'now','now',?, ?,?,'main',?,?)",id,owner.id(),owner.username(),CalibreTransfers.destination(settings.connection()),source,state);
        for(int i=0;i<states.length;i++) jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,identity_complete) VALUES(?,?,?, ?,0,'book.epub',?,1,'1.1',?,?)",id+"-"+i,id,i,"record"+i,epub.length,states[i],states[i].equals("UNCERTAIN"));
        return id;
    }
    JobRetries.Preview failed(String kind,String parent) {return retries.preview(kind,parent,new JobRetryController.Selection(null,true));}
    JobRetries.Preview selected(String kind,String parent,String... ids) {return retries.preview(kind,parent,new JobRetryController.Selection(List.of(ids),false));}
    JobRetries.Link create(String kind,String parent,JobRetries.Preview p) {return retries.create(kind,parent,new JobRetryController.Confirmation(p.token(),false));}

    @Test void torrentRetryFreezesCommandsKeepsHistoryAndIsIdempotent() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED","ACCEPTED","CANCELLED");
        var preview=failed("downloads",parent);assertThat(preview.send()).isEqualTo(1);
        var child=create("downloads",parent,preview);assertThat(create("downloads",parent,preview)).isEqualTo(child);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE job_id=?",Long.class,child.jobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_items WHERE id=?",String.class,parent+"-0")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT command_json FROM torrent_bulk_items WHERE job_id=?",String.class,child.jobId())).isEqualTo(jdbc.queryForObject("SELECT command_json FROM torrent_bulk_items WHERE id=?",String.class,parent+"-0"));
        assertThat(bulk.view(child.jobId()).interval()).isEqualTo("500ms");
        assertThat(retries.history("downloads",child.jobId()).source().parentId()).isEqualTo(parent);
        assertThat(retries.history("downloads",parent).retries()).contains(child);
        assertThat(failed("downloads",parent).send()).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=com.rlibanez.eplsync.events.EventContext.Origin.class,names={"MANUAL","SCHEDULED"})
    void taskAndDirectRetryCreationDoNotPersistSuccessfulStartEvents(com.rlibanez.eplsync.events.EventContext.Origin origin) throws Exception {
        for(String kind:List.of("downloads","calibre")) {
            String parent=kind.equals("downloads")?torrentJob("COMPLETED","FAILED"):calibreJob("COMPLETED","FAILED");
            var preview=failed(kind,parent);
            var child=com.rlibanez.eplsync.events.EventContext.withOrigin(origin,()->com.rlibanez.eplsync.tasks.AutomationContext.run(()->create(kind,parent,preview)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=?",Long.class,child.jobId())).isZero();
            String directParent=kind.equals("downloads")?torrentJob("COMPLETED","FAILED"):calibreJob("COMPLETED","FAILED");
            var direct=create(kind,directParent,failed(kind,directParent));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=? AND outcome='STARTED'",Long.class,direct.jobId())).isZero();
        }
    }
    @Test void selectedRetriesExcludeSuccessAndRejectForeignItemsAndRunningJobs() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED","ACCEPTED","CANCELLED");
        var p=selected("downloads",parent,parent+"-0",parent+"-1",parent+"-2");assertThat(p.send()).isEqualTo(2);assertThat(p.excluded()).isEqualTo(1);
        assertThatThrownBy(()->selected("downloads",parent,"foreign")).hasMessageContaining("no pertenece");
        jdbc.update("UPDATE torrent_bulk_jobs SET state='PAUSED' WHERE id=?",parent);
        assertThatThrownBy(()->failed("downloads",parent)).hasMessageContaining("Reanuda");
        jdbc.update("UPDATE torrent_bulk_jobs SET state='CANCELLED' WHERE id=?",parent);jdbc.update("UPDATE torrent_bulk_items SET state='IN_FLIGHT' WHERE id=?",parent+"-0");
        assertThat(retries.history("downloads",parent).terminal()).isFalse();
    }
    @Test void changedDestinationRequiresConfirmationAndStalePreviewsNeverCreateAJob() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");jdbc.update("UPDATE torrent_bulk_jobs SET target_fingerprint='old-destination' WHERE id=?",parent);
        var p=failed("downloads",parent);assertThat(p.changes()).isNotEmpty();
        assertThatThrownBy(()->create("downloads",parent,p)).hasMessageContaining("Confirma");
        var child=retries.create("downloads",parent,new JobRetryController.Confirmation(p.token(),true));assertThat(child.jobId()).isNotEqualTo(parent);
        var second=torrentJob("COMPLETED","FAILED");var old=failed("downloads",second);
        jdbc.update("UPDATE torrent_bulk_items SET message='changed' WHERE job_id=?",second);
        assertThatThrownBy(()->create("downloads",second,old)).hasMessageContaining("cambiaron");
    }
    @Test void previewsAreUserBoundExpireAndSurviveServiceCalls() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");var p=failed("downloads",parent);
        when(sessions.current()).thenReturn(new Account("other","Other","","ADMIN","ACTIVE",false,null,0,Set.of(),Instant.now()));
        assertThatThrownBy(()->create("downloads",parent,p)).hasMessageContaining("caducado");
        when(sessions.current()).thenReturn(owner);jdbc.update("UPDATE job_retry_previews SET expires_at=0 WHERE id=?",p.token());
        assertThatThrownBy(()->create("downloads",parent,p)).hasMessageContaining("caducado");
    }
    @Test void calibreFailuresProduceANewJobAndCanBeRetriedAgainWithTheSameRoot() throws Exception {
        var parent=calibreJob("COMPLETED","FAILED","ACCEPTED");var p=failed("calibre",parent);
        var child=create("calibre",parent,p);assertThat(transfers.job(child.jobId()).total()).isEqualTo(1);
        jdbc.update("UPDATE calibre_job_items SET state='FAILED' WHERE job_id=?",child.jobId());jdbc.update("UPDATE calibre_jobs SET state='COMPLETED' WHERE id=?",child.jobId());
        var next=create("calibre",child.jobId(),failed("calibre",child.jobId()));assertThat(next.rootId()).isEqualTo(parent);assertThat(next.parentId()).isEqualTo(child.jobId());
    }
    @Test void uncertainCalibreTransfersAreVerifiedWithoutAnUpload() throws Exception {
        var parent=calibreJob("CANCELLED","UNCERTAIN");jdbc.update("UPDATE calibre_job_items SET calibre_id=9 WHERE job_id=?",parent);
        var p=selected("calibre",parent,parent+"-0");assertThat(p.verify()).isEqualTo(1);
        var child=create("calibre",parent,p);transfers.cycle();
        assertThat(transfers.job(child.jobId()).accepted()).isEqualTo(1);verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        assertThatThrownBy(()->transfers.control(parent,"resume")).hasMessageContaining("transferidos");
    }
    @Test void unconfirmedCalibreRetryPausesWithoutResending() throws Exception {
        var parent=calibreJob("CANCELLED","UNCERTAIN");var child=create("calibre",parent,selected("calibre",parent,parent+"-0"));
        transfers.cycle();assertThat(transfers.job(child.jobId()).state()).isEqualTo("PAUSED");assertThat(transfers.job(child.jobId()).uncertain()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void modifiedFileRequiresConfirmationAndRetainsTheFrozenRevision() throws Exception {
        var parent=calibreJob("COMPLETED","FAILED");when(files.list(anyString())).thenReturn(List.of(new EpubFiles.Item(0,"new-name.epub",epub.length+1,epub.length)));
        var p=failed("calibre",parent);assertThat(p.changes()).isNotEmpty();assertThatThrownBy(()->create("calibre",parent,p)).hasMessageContaining("Confirma");
        var child=retries.create("calibre",parent,new JobRetryController.Confirmation(p.token(),true));
        var item=transfers.items(child.jobId(),0,20,List.of("position,asc")).items().getFirst();assertThat(item.revision()).isEqualTo("1.1");assertThat(item.name()).isEqualTo("new-name.epub");assertThat(item.message()).contains(CalibreTransfers.SIZE_WARNING);
    }
    @Test void missingFilesAreExcludedAndNewFilesChangesInvalidateThePreparedRequest() throws Exception {
        var parent=calibreJob("COMPLETED","FAILED");when(files.list(anyString())).thenReturn(List.of());
        assertThat(failed("calibre",parent).excluded()).isEqualTo(1);
        when(files.list(anyString())).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",epub.length)));var p=failed("calibre",parent);
        when(files.list(anyString())).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",epub.length+1)));
        assertThatThrownBy(()->create("calibre",parent,p)).hasMessageContaining("cambiaron");
    }
    @Test void cancelledCalibreCleanupIsRetriedWithoutUploadingAgain() throws Exception {
        var parent=calibreJob("PAUSED","CLEANUP_PENDING");
        jdbc.update("UPDATE calibre_job_items SET calibre_id=9,cleanup_policy='EPUB',cleanup_ids='[8]' WHERE job_id=?",parent);
        transfers.control(parent,"cancel");
        assertThat(jdbc.queryForObject("SELECT requested_cleanup_policy FROM calibre_job_items WHERE job_id=?",String.class,parent)).isEqualTo("EPUB");
        var p=selected("calibre",parent,parent+"-0");assertThat(p.send()).isZero();assertThat(p.cleanup()).isEqualTo(1);
        var child=create("calibre",parent,p);
        when(client.book(any(),eq(8L))).thenReturn(new CalibreClient.Book(8,"Old",List.of("Author"),1L,new BigDecimal("1.0"),true));
        var removed=new java.util.concurrent.atomic.AtomicBoolean();
        when(client.hasEpub(any(),eq(8L))).thenAnswer(c->!removed.get());
        doAnswer(c->{removed.set(true);return null;}).when(client).remove(any(),eq(8L),eq(false));
        transfers.cycle();assertThat(transfers.job(child.jobId()).state()).isEqualTo("COMPLETED");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());verify(client).remove(any(),eq(8L),eq(false));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void cleanupOnlyTorrentRetryDoesNotQueueAnotherSendOrChangeTheOriginal(boolean deleteHistory) throws Exception {
        var parent=torrentJob("CANCELLED","ACCEPTED");
        jdbc.update("UPDATE torrent_bulk_jobs SET type='UPDATE',previous_versions='REMOVE_TORRENT_AND_FILES' WHERE id=?",parent);
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('old',1,1,'oldhash','qbittorrent',?,'DOWNLOADED','EPLSYNC',0)",tracking.instanceId());
        jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,cleanup_timing,created_at,snapshot,automatic_cleanup) VALUES(?,?,'REMOVE_TORRENT_AND_FILES','IMMEDIATE',0,'{\"items\":[]}',1)",parent,tracking.instanceId());
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state,created_at,client_instance_id,previous_versions,target_hashes,automatic,immediate,replacement_accepted) VALUES('cleanup',?,'old',1,'oldhash','CANCELLED',0,?,'REMOVE_TORRENT_AND_FILES','[\"0000000000000000000000000000000000000001\"]',1,1,1)",parent,tracking.instanceId());
        jdbc.update("UPDATE torrent_update_plans SET delete_history=? WHERE job_id=?",deleteHistory,parent);
        jdbc.update("UPDATE torrent_update_cleanup SET delete_history=? WHERE job_id=?",deleteHistory,parent);
        var p=selected("downloads",parent,parent+"-0");assertThat(p.cleanup()).isEqualTo(1);assertThat(p.send()).isZero();
        var child=create("downloads",parent,p);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE job_id=? AND state='PENDING'",Long.class,child.jobId())).isZero();
        assertThat(jdbc.queryForObject("SELECT replacement_accepted FROM torrent_update_cleanup WHERE job_id=?",Integer.class,child.jobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delete_history FROM torrent_update_plans WHERE job_id=?",Boolean.class,child.jobId())).isEqualTo(deleteHistory);
        assertThat(jdbc.queryForObject("SELECT delete_history FROM torrent_update_cleanup WHERE job_id=?",Boolean.class,child.jobId())).isEqualTo(deleteHistory);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_update_cleanup WHERE id='cleanup'",String.class)).isEqualTo("CANCELLED");
        jdbc.update("UPDATE torrent_update_cleanup SET state='REQUESTED' WHERE job_id=?",parent);
        assertThat(retries.history("downloads",parent).terminal()).isFalse();
    }
    @Test void updateRetryRemainsPendingUntilBothSendAndCleanupFinishEvenAfterRecovery() throws Exception {
        var parent=torrentJob("CANCELLED","FAILED");
        jdbc.update("UPDATE torrent_bulk_jobs SET type='UPDATE',previous_versions='REMOVE_TORRENT' WHERE id=?",parent);
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('old',1,1,'oldhash','qbittorrent',?,'DOWNLOADED','EPLSYNC',0)",tracking.instanceId());
        jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,cleanup_timing,created_at,snapshot,automatic_cleanup) VALUES(?,?,'REMOVE_TORRENT','AFTER_DOWNLOAD',0,'{\"items\":[]}',1)",parent,tracking.instanceId());
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state,created_at,client_instance_id,previous_versions,target_hashes,automatic,immediate,replacement_accepted) VALUES('cleanup',?,'old',1,'oldhash','CANCELLED',0,?,'REMOVE_TORRENT','[\"0000000000000000000000000000000000000001\"]',1,0,0)",parent,tracking.instanceId());
        var preview=selected("downloads",parent,parent+"-0");
        assertThat(preview.send()).isEqualTo(1);assertThat(preview.cleanup()).isEqualTo(1);
        var child=create("downloads",parent,preview);
        bulk.next();var item=bulk.claim(child.jobId(),bulk.pending(child.jobId(),1).getFirst());
        assertThat(item).isNotNull();
        bulk.finish(item.getId(),com.rlibanez.eplsync.torrent.bulk.BulkItem.State.ACCEPTED,null,false,false);
        assertThat(bulk.view(child.jobId()).status().name()).isEqualTo("CLEANUP_WAIT");
        bulk.recover();
        assertThat(bulk.view(child.jobId()).status().name()).isEqualTo("CLEANUP_WAIT");
        // Persist the confirmation normally supplied by the cleanup worker.
        jdbc.update("UPDATE torrent_update_cleanup SET state='REMOVED' WHERE job_id=?",child.jobId());
        bulk.cleanupChanged(child.jobId());
        assertThat(bulk.view(child.jobId()).status().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_update_cleanup WHERE id='cleanup'",String.class)).isEqualTo("CANCELLED");
    }
    @Test void waitingCalibreRetryKeepsItsDownloadDependencyAndFollowsTorrentRetryDescendants() throws Exception {
        var download=torrentJob("CANCELLED","CANCELLED");
        var torrentRetry=create("downloads",download,selected("downloads",download,download+"-0"));
        jdbc.update("UPDATE torrent_bulk_items SET state='ACCEPTED' WHERE job_id=?",torrentRetry.jobId());
        var parent=calibreJob("CANCELLED","CANCELLED");
        jdbc.update("UPDATE calibre_job_items SET record_id=NULL,file_index=NULL,size=NULL,download_job_id=? WHERE job_id=?",download,parent);
        var child=create("calibre",parent,selected("calibre",parent,parent+"-0"));
        var item=transfers.items(child.jobId(),0,20,List.of("position,asc")).items().getFirst();
        assertThat(item.state()).isEqualTo("WAITING_DOWNLOAD");assertThat(item.downloadJobId()).isEqualTo(download);
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('downloaded',1,1.1,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0)","0".repeat(39)+"1",tracking.instanceId());
        assertThat(updateFlow.waiting(child.jobId(),item)).isTrue();
        assertThat(transfers.items(child.jobId(),0,20,List.of("position,asc")).items().getFirst().recordId()).isEqualTo("downloaded");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        transfers.cycle();
        assertThat(transfers.job(child.jobId()).state()).isEqualTo("COMPLETED");
        assertThat(transfers.job(child.jobId()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        assertThat(transfers.job(parent).state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id=?",String.class,download)).isEqualTo("CANCELLED");
    }
    @Test void retryRecoveryKeepsProvenanceAndVerifiesInterruptedCalibreUploadWithoutResending() throws Exception {
        var parent=calibreJob("COMPLETED","FAILED");
        var preview=failed("calibre",parent);
        var child=create("calibre",parent,preview);
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",child.jobId());
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=1,calibre_id=9 WHERE job_id=?",child.jobId());
        transfers.recoverInterrupted();
        assertThat(transfers.job(child.jobId()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(child.jobId()).uncertain()).isEqualTo(1);
        assertThat(create("calibre",parent,preview)).isEqualTo(child);
        transfers.control(child.jobId(),"resume");transfers.cycle();
        assertThat(transfers.job(child.jobId()).state()).isEqualTo("COMPLETED");
        assertThat(retries.history("calibre",child.jobId()).source().rootId()).isEqualTo(parent);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void retryRecoveryRequeuesInterruptedTorrentSendWithoutLosingItsFrozenCommand() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");
        var preview=failed("downloads",parent);var child=create("downloads",parent,preview);
        var command=jdbc.queryForObject("SELECT command_json FROM torrent_bulk_items WHERE job_id=?",String.class,child.jobId());
        jdbc.update("UPDATE torrent_bulk_jobs SET state='RUNNING' WHERE id=?",child.jobId());
        jdbc.update("UPDATE torrent_bulk_items SET state='IN_FLIGHT' WHERE job_id=?",child.jobId());
        bulk.recover();
        assertThat(bulk.view(child.jobId()).status().name()).isEqualTo("QUEUED");
        assertThat(bulk.view(child.jobId()).pending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT command_json FROM torrent_bulk_items WHERE job_id=?",String.class,child.jobId())).isEqualTo(command);
        assertThat(create("downloads",parent,preview)).isEqualTo(child);
        assertThat(retries.history("downloads",child.jobId()).source().parentId()).isEqualTo(parent);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_items WHERE id=?",String.class,parent+"-0")).isEqualTo("FAILED");
    }
    @Test void destructiveRetryCannotMoveToAnotherConnection() throws Exception {
        var parent=calibreJob("CANCELLED","UNCERTAIN");
        jdbc.update("UPDATE calibre_jobs SET destination='other' WHERE id=?",parent);
        var p=selected("calibre",parent,parent+"-0");assertThat(p.verify()).isZero();assertThat(p.excluded()).isEqualTo(1);
        verify(client,never()).findRevision(any(),anyLong(),any());
    }
    @Test void revokedTorrentPermissionsPauseTheRetryBeforeSending() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");var child=create("downloads",parent,failed("downloads",parent));
        accounts.create("Keeper","keeper@example.org","ADMIN",owner.id());
        accounts.update(owner.id(),"USER","ACTIVE",Map.of(),owner.id());
        bulk.next();assertThat(bulk.checkDispatchConfiguration(child.jobId())).isFalse();
        assertThat(bulk.view(child.jobId()).message()).contains("ya no está autorizado");
    }
    @Test void differentPreviewTokensCannotCreateTwoRetriesForTheSameSourceItem() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");var first=failed("downloads",parent);var second=failed("downloads",parent);
        create("downloads",parent,first);
        assertThatThrownBy(()->create("downloads",parent,second)).hasMessageContaining("cambiaron");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_retry_jobs WHERE parent_id=?",Long.class,parent)).isEqualTo(1);
    }
    @Test void oversizedPreviewsAreRejectedBeforeTheyArePersisted() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");
        jdbc.update("UPDATE torrent_bulk_items SET command_json=? WHERE job_id=?","{\"padding\":\""+"x".repeat(9*1024*1024)+"\"}",parent);
        assertThatThrownBy(()->failed("downloads",parent)).hasMessageContaining("8 MiB");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_retry_previews",Long.class)).isZero();
    }
    @Test void retentionKeepsTorrentParentsUntilTheirChildrenHaveExpired() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");var child=create("downloads",parent,failed("downloads",parent));
        var cutoff=Instant.now().minus(java.time.Duration.ofDays(90));
        assertThat(retention.purgeOne(cutoff).jobs()).isZero();
        jdbc.update("UPDATE torrent_bulk_items SET state='FAILED' WHERE job_id=?",child.jobId());
        jdbc.update("UPDATE torrent_bulk_jobs SET state='COMPLETED',updated_at=0 WHERE id=?",child.jobId());
        assertThat(retention.purgeOne(cutoff).jobs()).isEqualTo(1);
        assertThat(retention.purgeOne(cutoff).jobs()).isEqualTo(1);
    }
    @Test void retentionKeepsCalibreParentsAndDatabaseResetClearsTheRetryInventory() throws Exception {
        var parent=calibreJob("COMPLETED","FAILED");create("calibre",parent,failed("calibre",parent));
        jdbc.update("UPDATE calibre_jobs SET updated_at='2020-01-01T00:00:00Z' WHERE id=?",parent);
        transfers.retention();assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs WHERE id=?",Long.class,parent)).isEqualTo(1);
        reset.reset();
        for(String table:List.of("job_retry_items","job_retry_jobs","job_retry_previews")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
    }
    @Test @WithMockUser(authorities={"TORRENT_JOBS_MANAGE","CALIBRE_JOBS_MANAGE"}) void retriesRespectSendPermissions() throws Exception {
        var parent=torrentJob("COMPLETED","FAILED");assertThatThrownBy(()->failed("downloads",parent)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
