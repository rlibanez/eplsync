package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.security.test.context.support.WithMockUser;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@WithMockUser(authorities={"CALIBRE_READ","CALIBRE_SEND","CALIBRE_DELETE","DOWNLOADS_FILES_READ","CALIBRE_JOBS_MANAGE","CATALOG_READ","TORRENT_SEND","TORRENT_SYNC"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.calibre.transfers.worker-enabled=false","eplsync.calibre.transfers.download-check-interval=15s","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class CalibreUpdateFlowTests {
    @Autowired CalibreTransfers transfers;
    @Autowired CalibreUpdateFlow flow;
    @Autowired com.rlibanez.eplsync.tasks.InternalTaskSettings internalTasks;
    @Autowired CalibreSettings settings;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired AccountStore accounts;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    @MockitoBean BulkStore bulk;
    @MockitoBean com.rlibanez.eplsync.service.TorrentClientService torrent;
    Account owner;String record;byte[] bytes;
    @BeforeEach void setup() throws Exception {
        internalTasks.save(com.rlibanez.eplsync.tasks.InternalTaskSettings.CALIBRE,"15s");
        jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");jdbc.update("DELETE FROM calibre_update_requests");jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM catalog_books");downloads.deleteAll();accounts.clear();
        jdbc.update("DELETE FROM app_settings WHERE setting_key IN ('calibre.connection','calibre.snapshot')");
        owner=accounts.initialize("Owner","owner@example.com","password123","password123");
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        for(int i=1;i<=2;i++) {
            jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(?,1.1,?, 'Author')",i,"Book "+i);
            jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?, 'test',true,?,?,1.0,?,'Author')","old"+i,i,i,"Book "+i);
        }
        var d=new DownloadRecord();d.setEplId(1L);d.setRevision(1.1);d.setHash("A".repeat(40));d.setClient("qbittorrent");d.setClientInstanceId(tracking.instanceId());d.setStatus(DownloadStatus.DOWNLOADED);d.setOrigin(DownloadRecord.Origin.EPLSYNC);d.setCreatedAt(Instant.now());record=downloads.save(d).getId();
        bytes=CalibreTransfersTests.epub(1,"1.1");
        when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main"));
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(files.download(record,0)).thenAnswer(call -> new EpubFiles.Opened("book.epub",bytes.length,new java.io.ByteArrayInputStream(bytes)));
        when(client.book(any(),eq(9L))).thenReturn(book(9,1,"1.1"));
        when(client.book(any(),eq(1L))).thenReturn(book(1,1,"1.0"));
        when(client.findRevision(any(),anyLong(),any())).thenReturn(null);
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenReturn(9L);
        when(client.hasEpub(any(),anyLong())).thenReturn(true);
        var view=mock(BulkStore.View.class);when(view.jobId()).thenReturn("download-job");when(bulk.create(any(),any(),anyBoolean(),anyBoolean(),any())).thenReturn(view);
    }
    @Test @WithMockUser(authorities={"CALIBRE_READ","DOWNLOADS_FILES_READ","CATALOG_READ"})
    void availabilityDoesNotRequireSendingAndDistinguishesMissingAndUnreadableFiles() {
        assertThat(flow.availability(List.of(1L,2L))).containsExactlyInAnyOrder(
            new CalibreUpdateFlow.Availability(1,"READY"),new CalibreUpdateFlow.Availability(2,"MISSING"));
        verify(client,never()).check(any());
        when(files.list(record)).thenThrow(new IllegalArgumentException("Unreadable"));
        assertThat(flow.availability(List.of(1L))).containsExactly(new CalibreUpdateFlow.Availability(1,"INACCESSIBLE"));
    }
    @Test @WithMockUser(authorities={"CALIBRE_READ","CATALOG_READ"})
    void availabilityRequiresFileReadPermission() {
        assertThatThrownBy(()->flow.availability(List.of(1L))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    static CalibreClient.Book book(long id,long epl,String rev) {return new CalibreClient.Book(id,"Book",List.of("Author"),epl,new BigDecimal(rev),true);}
    CalibreUpdateFlow.Choice ready(String policy) {return new CalibreUpdateFlow.Choice(1,1.1,true,List.of(new CalibreTransfers.FileChoice(record,0)),policy,List.of(1L),policy.equals("BOOK"));}
    CalibreUpdateFlow.Choice missing() {return new CalibreUpdateFlow.Choice(2,1.1,true,List.of(),"KEEP",List.of(2L),false);}
    CalibreUpdateFlow.Result submit(List<CalibreUpdateFlow.Choice> choices,boolean download,boolean deferred) {return submit(new CalibreUpdateFlow.Request(UUID.randomUUID().toString(),choices,download,deferred));}
    CalibreUpdateFlow.Result submit(CalibreUpdateFlow.Request request) {return EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->flow.create(request));}
    void downloadItem(String state) {
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,state) VALUES('download-job',100,1,500,1,'RUNNING')");
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,epl_id,revision,hash,state) VALUES('download-item','download-job',0,0,2,1.1,?,?)","B".repeat(40),state);
    }
    @Test void idempotentRequestsFromBeforeAutomationOptionsWereAddedRemainValid() {
        var request=new CalibreUpdateFlow.Request(UUID.randomUUID().toString(),List.of(ready("KEEP")),true,true);
        var result=submit(request);
        jdbc.update("UPDATE calibre_update_requests SET payload=json_remove(?,'$.downloadOptions') WHERE id=?",tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request),request.id());
        assertThat(submit(request)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Long.class)).isEqualTo(1);
    }
    @Test void configuredIntervalControlsBothClientProbeAndWaitingItem() {
        var result=submit(List.of(missing()),true,true);downloadItem("PENDING");
        long before=Instant.now().toEpochMilli();
        transfers.cycle();
        long after=Instant.now().toEpochMilli();
        long probe=jdbc.queryForObject("SELECT next_probe FROM calibre_jobs WHERE id=?",Long.class,result.calibreJobId());
        long check=jdbc.queryForObject("SELECT next_check FROM calibre_job_items WHERE job_id=?",Long.class,result.calibreJobId());
        assertThat(probe).isBetween(before+15000,after+15000);
        assertThat(check).isBetween(before+15000,after+15000);
        flow.refreshDownloads(result.calibreJobId());
        assertThat(jdbc.queryForObject("SELECT next_probe FROM calibre_jobs WHERE id=?",Long.class,result.calibreJobId())).isEqualTo(probe);
    }
    @Test void changingIntervalInSettingsChangesWaitingChecksWithoutRestart() {
        internalTasks.save(com.rlibanez.eplsync.tasks.InternalTaskSettings.CALIBRE,"1m");
        var result=submit(List.of(missing()),true,true);downloadItem("PENDING");long before=Instant.now().toEpochMilli();
        transfers.cycle();long after=Instant.now().toEpochMilli();
        assertThat(jdbc.queryForObject("SELECT next_probe FROM calibre_jobs WHERE id=?",Long.class,result.calibreJobId())).isBetween(before+60000,after+60000);
        assertThat(jdbc.queryForObject("SELECT next_check FROM calibre_job_items WHERE job_id=?",Long.class,result.calibreJobId())).isBetween(before+60000,after+60000);
    }
    @Test void downloadCheckIntervalRejectsNonPositiveAndSubMillisecondDurations() {
        assertThat(com.rlibanez.eplsync.tasks.InternalTaskSettings.parse("30s").toMillis()).isEqualTo(30000);
        assertThat(com.rlibanez.eplsync.tasks.InternalTaskSettings.parse("1m").toMillis()).isEqualTo(60000);
        for(String invalid:List.of("0s","-1s","0ns","invalid"))
            assertThatThrownBy(()->com.rlibanez.eplsync.tasks.InternalTaskSettings.parse(invalid).toMillis()).isInstanceOf(IllegalArgumentException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,10001})
    void activeTorrentSendsDeferDownloadChecksWithoutPausingOrDuplicating(int count) throws Exception {
        var result=submit(List.of(missing()),true,true);downloadItem("ACCEPTED");
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            var rows=new ArrayList<Object[]>();var linked=new ArrayList<Object[]>();var waiting=new ArrayList<Object[]>();
            for(int n=1;n<count;n++) {
                long epl=2L+n;String hash=String.format("%040X",epl);
                rows.add(new Object[]{epl,"Book "+epl});
                linked.add(new Object[]{"bulk-"+epl,n,epl,hash});
                waiting.add(new Object[]{"waiting-"+epl,result.calibreJobId(),n,"Book "+epl,epl,Instant.now().plusSeconds(3600).toEpochMilli()});
            }
            jdbc.batchUpdate("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(?,1.1,?,'Author')",rows);
            jdbc.batchUpdate("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,epl_id,revision,hash,state) VALUES(?,'download-job',?,0,?,1.1,?,'PENDING')",linked);
            jdbc.batchUpdate("INSERT INTO calibre_job_items(id,job_id,position,name,epl_id,revision,state,download_job_id,wait_until) VALUES(?,?,?,?,?,'1.1','WAITING_DOWNLOAD','download-job',?)",waiting);
        });
        var adapter=mock(com.rlibanez.eplsync.torrent.TorrentClient.class);
        when(torrent.exclusiveClient(any())).thenAnswer(call->{java.util.function.Function<com.rlibanez.eplsync.torrent.TorrentClient,?> work=call.getArgument(0);return tracking.exclusive(()->work.apply(adapter));});
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var sent=new java.util.concurrent.atomic.AtomicInteger();
        var command=new com.rlibanez.eplsync.torrent.TorrentDownload("B".repeat(40),"magnet:?xt=urn:btih:"+"B".repeat(40),true,null,null,null,com.rlibanez.eplsync.model.CatalogBook.builder().eplId(2L).revision(1.1).title("Book 2").build());
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var submitting=executor.submit(()->tracking.submit(command,()->{
                sent.incrementAndGet();entered.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
                catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                return com.rlibanez.eplsync.dto.TorrentDownloadResult.Status.ACCEPTED;
            }));
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                transfers.cycle();
                assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("RUNNING");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE job_id=? AND state='WAITING_DOWNLOAD'",Long.class,result.calibreJobId())).isEqualTo(count);
                assertThat(jdbc.queryForObject("SELECT next_check FROM calibre_jobs WHERE id=?",Long.class,result.calibreJobId())).isGreaterThan(Instant.now().toEpochMilli());
                transfers.cycle();verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
            } finally {release.countDown();}
            submitting.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        var record2=downloads.findByEplIdIn(List.of(2L)).getFirst().getId();
        byte[] payload=CalibreTransfersTests.epub(2,"1.1");
        when(adapter.listTorrents(anySet())).thenReturn(List.of(new RemoteTorrent("B".repeat(40),DownloadStatus.DOWNLOADED,Instant.now(),Set.of(),"/books/book.epub","Book 2","/books",false)));
        when(files.list(record2)).thenReturn(List.of(new EpubFiles.Item(0,"second.epub",payload.length)));
        when(files.download(record2,0)).thenAnswer(call->new EpubFiles.Opened("second.epub",payload.length,new java.io.ByteArrayInputStream(payload)));
        when(client.book(any(),eq(9L))).thenReturn(book(9,2,"1.1"));
        jdbc.update("UPDATE calibre_jobs SET next_check=0,next_probe=0 WHERE id=?",result.calibreJobId());jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE job_id=?",result.calibreJobId());
        transfers.cycle();transfers.cycle();transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).accepted()).isEqualTo(1);
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo(count==1?"COMPLETED":"RUNNING");
        assertThat(sent.get()).isEqualTo(1);verify(client,times(1)).uploadEpub(any(),any(),eq("second.epub"),anyString());
        verify(bulk,times(1)).create(any(),any(),anyBoolean(),anyBoolean(),any());
    }
    @Test void busyFileCheckIsDeferredWithoutBlockingTheDownload() {
        var result=submit(List.of(missing()),true,true);downloadItem("ACCEPTED");
        var d=new DownloadRecord();d.setEplId(2L);d.setRevision(1.1);d.setHash("B".repeat(40));d.setClient("qbittorrent");d.setClientInstanceId(tracking.instanceId());d.setStatus(DownloadStatus.DOWNLOADED);d.setOrigin(DownloadRecord.Origin.EPLSYNC);d.setCreatedAt(Instant.now());String second=downloads.save(d).getId();
        when(files.list(second)).thenThrow(new com.rlibanez.eplsync.exception.TorrentBusyException());
        transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("RUNNING");
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("WAITING_DOWNLOAD");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void aRealTorrentErrorStillRequiresReviewInsteadOfBeingTreatedAsContention() {
        var result=submit(List.of(missing()),true,true);downloadItem("ACCEPTED");
        var d=new DownloadRecord();d.setEplId(2L);d.setRevision(1.1);d.setHash("B".repeat(40));d.setClient("qbittorrent");d.setClientInstanceId(tracking.instanceId());d.setStatus(DownloadStatus.ERROR);d.setOrigin(DownloadRecord.Origin.EPLSYNC);d.setCreatedAt(Instant.now());downloads.save(d);
        transfers.cycle();assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("BLOCKED_DOWNLOAD");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void waitingTurnsIntoSendAfterDownloadAndPinsRevisionAndHash() throws Exception {
        var result=submit(List.of(missing()),true,true);downloadItem("PENDING");transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("RUNNING");
        transfers.recoverInterrupted();assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("QUEUED");
        jdbc.update("UPDATE torrent_bulk_items SET state='ACCEPTED'");
        var d=new DownloadRecord();d.setEplId(2L);d.setRevision(1.1);d.setHash("B".repeat(40));d.setClient("qbittorrent");d.setClientInstanceId(tracking.instanceId());d.setStatus(DownloadStatus.DOWNLOADED);d.setOrigin(DownloadRecord.Origin.EPLSYNC);d.setCreatedAt(Instant.now());String second=downloads.save(d).getId();
        byte[] epub=CalibreTransfersTests.epub(2,"1.1");when(files.list(second)).thenReturn(List.of(new EpubFiles.Item(0,"second.epub",epub.length)));when(files.download(second,0)).thenAnswer(call->new EpubFiles.Opened("second.epub",epub.length,new java.io.ByteArrayInputStream(epub)));
        when(client.book(any(),eq(9L))).thenReturn(book(9,2,"1.1"));
        jdbc.update("UPDATE calibre_job_items SET next_check=0");transfers.cycle();
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("PENDING");
        jdbc.update("UPDATE catalog_books SET revision=2 WHERE epl_id=2");transfers.cycle();assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("COMPLETED");
        verify(client).uploadEpub(any(),any(),eq("second.epub"),anyString());
    }
    @Test void finishedFullDeletionUpdatesSnapshotAndNeverTouchesTorrentFiles() {
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();
        doAnswer(call->{when(client.book(any(),eq(1L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));return null;}).when(client).remove(any(),eq(1L),eq(true));
        transfers.cycle();assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true AND calibre_id=1",Long.class)).isZero();verify(client,times(1)).remove(any(),eq(1L),eq(true));
    }
    @Test void restartFinishesPendingCleanupWithoutRepeatingTheUploadOrAnAlreadyAppliedDeletion() {
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("CLEANUP_PENDING");
        when(client.book(any(),eq(1L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true AND calibre_id=9",Long.class)).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void restartDoesNotDeletePreviousRevisionIfRetainedEpubCannotBeVerified() {
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();
        when(client.hasEpub(any(),eq(9L))).thenReturn(false);
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void cleanupCannotRunAfterCancellation() {
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();transfers.control(result.calibreJobId(),"cancel");transfers.cycle();verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void expirationBlocksAndResumeRenewsWaitingDeadline() {
        var result=submit(List.of(missing()),true,true);downloadItem("PENDING");jdbc.update("UPDATE calibre_job_items SET wait_until=1");transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");transfers.control(result.calibreJobId(),"resume");transfers.cycle();
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("WAITING_DOWNLOAD");
    }
    @Test void skippedExistingRevisionRemainsSkippedAfterCleanup() {
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        var result=submit(List.of(ready("EPUB")),false,false);transfers.cycle();
        when(client.hasEpub(any(),eq(1L))).thenReturn(false);transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).skipped()).isEqualTo(1);verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void deferredSendRequiresConfiguredFileRootsBeforeCreatingDownloads() {
        doThrow(new com.rlibanez.eplsync.exception.TorrentOperationException(org.springframework.http.HttpStatus.CONFLICT,"Configura las carpetas")).when(files).requireRoots();
        assertThatThrownBy(()->submit(List.of(missing()),true,true)).hasMessageContaining("carpetas");verify(bulk,never()).create(any(),any(),anyBoolean(),anyBoolean(),any());
    }
    @Test void verifyingCancelledUncertainUploadDoesNotReactivateCleanup() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).uncertain()).isEqualTo(1);
        transfers.control(result.calibreJobId(),"cancel");transfers.control(result.calibreJobId(),"resume");when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).accepted()).isEqualTo(1);verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void mixedSelectionCreatesReadyAndWaitingItemsAndOnlyOneDownloadJob() {
        var result=submit(List.of(ready("KEEP"),missing()),true,true);
        assertThat(result.downloadJobId()).isEqualTo("download-job");
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items()).extracting(i -> i.state()).containsExactly("PENDING","WAITING_DOWNLOAD");
        verify(bulk,times(1)).create(any(),any(),anyBoolean(),anyBoolean(),any());
        transfers.cycle();assertThat(transfers.job(result.calibreJobId()).pending()).isEqualTo(1);
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("RUNNING");
    }
    @Test void downloadOnlyDoesNotCreateFutureCalibreJob() {
        var result=submit(List.of(missing()),true,false);assertThat(result.calibreJobId()).isNull();assertThat(result.downloadJobId()).isEqualTo("download-job");
    }
    @Test void retriesReuseBothJobsAndChangedPayloadIsRejected() {
        var request=new CalibreUpdateFlow.Request("retry-id",List.of(ready("KEEP"),missing()),true,true);
        var first=submit(request);assertThat(submit(request)).isEqualTo(first);
        verify(bulk,times(1)).create(any(),any(),anyBoolean(),anyBoolean(),any());
        assertThatThrownBy(()->submit(new CalibreUpdateFlow.Request("retry-id",List.of(ready("KEEP")),false,false))).hasMessageContaining("otra selección");
    }
    @Test void cleanupWaitsForVerifiedUploadAndRetriesWithoutUploadingAgain() {
        var result=submit(List.of(ready("EPUB")),false,false);String id=result.calibreJobId();
        transfers.cycle();assertThat(transfers.items(id,0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("CLEANUP_PENDING");verify(client,never()).remove(any(),anyLong(),anyBoolean());
        transfers.cycle();assertThat(transfers.job(id).state()).isEqualTo("PAUSED");
        when(client.hasEpub(any(),eq(1L))).thenReturn(false);transfers.control(id,"resume");transfers.cycle();
        assertThat(transfers.job(id).state()).isEqualTo("COMPLETED");verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void missingIdentifiersNeverPermitCleanup() {
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),null,null,false));
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void changedOldMetadataAndUnconfirmedDeletionStayPending() {
        var result=submit(List.of(ready("BOOK")),false,false);transfers.cycle();
        when(client.book(any(),eq(1L))).thenReturn(book(1,999,"1.0"));transfers.cycle();
        verify(client,never()).remove(any(),anyLong(),anyBoolean());assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");
    }
    @Test void fullBookDeletionRequiresExplicitConfirmationAndDeletePermission() {
        var unconfirmed=new CalibreUpdateFlow.Choice(1,1.1,true,List.of(new CalibreTransfers.FileChoice(record,0)),"BOOK",List.of(1L),false);
        assertThatThrownBy(()->submit(List.of(unconfirmed),false,false)).hasMessageContaining("Confirma");
    }
    @Test @WithMockUser(authorities={"CALIBRE_SEND","DOWNLOADS_FILES_READ","CATALOG_READ"}) void cleanupDeniedWithoutPermission() {
        assertThatThrownBy(()->submit(List.of(ready("EPUB")),false,false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void waitingSurvivesRestartAndCancelDoesNotCancelDownloadJob() {
        var result=submit(List.of(missing()),true,true);transfers.recoverInterrupted();
        assertThat(transfers.job(result.calibreJobId()).pending()).isEqualTo(1);
        transfers.control(result.calibreJobId(),"cancel");assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("CANCELLED");verify(bulk,never()).control(anyString(),anyString());
    }
    @Test void missingDownloadJobBlocksInsteadOfCompletingOrLooping() {
        var result=submit(List.of(missing()),true,true);transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("BLOCKED_DOWNLOAD");
    }
    @Test void changedCatalogRevisionOrUnreadableFileDoesNotCreateJobs() {
        jdbc.update("UPDATE catalog_books SET revision=2 WHERE epl_id=1");
        assertThatThrownBy(()->submit(List.of(ready("KEEP")),false,false)).hasMessageContaining("revisión del catálogo cambió");
        jdbc.update("UPDATE catalog_books SET revision=1.1 WHERE epl_id=1");when(files.list(record)).thenThrow(new RuntimeException("unreadable"));
        assertThatThrownBy(()->submit(List.of(ready("KEEP")),true,true)).hasMessageContaining("no es accesible");assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Long.class)).isZero();
    }
    @Test void missingUploadedEpubPreventsCleanupUntilVerificationSucceeds() {
        when(client.hasEpub(any(),eq(9L))).thenReturn(false);
        var result=submit(List.of(ready("BOOK")),false,false);
        transfers.cycle();
        assertThat(transfers.job(result.calibreJobId()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(result.calibreJobId()).uncertain()).isEqualTo(1);
        transfers.cycle();verify(client,never()).remove(any(),anyLong(),anyBoolean());
        when(client.hasEpub(any(),eq(9L))).thenReturn(true);
        transfers.control(result.calibreJobId(),"resume");transfers.cycle();
        assertThat(transfers.items(result.calibreJobId(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("CLEANUP_PENDING");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
}
