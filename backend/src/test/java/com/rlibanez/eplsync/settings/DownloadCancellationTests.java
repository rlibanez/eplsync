package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.tasks.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DownloadCancellationTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("cancellation.db"));}
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    @Autowired CalibreSettings settings;
    @Autowired CalibreTransfers transfers;
    @Autowired BulkStore bulk;
    @Autowired DownloadTrackingService tracking;
    @MockitoBean TorrentClientService torrent;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM calibre_job_items");jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM job_retry_jobs");jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");jdbc.update("DELETE FROM torrent_downloads");jdbc.update("DELETE FROM catalog_books");accounts.clear();
        var owner=accounts.initialize("Owner","owner@example.com","password123","password123");SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,owner.permissions().stream().map(p->new SimpleGrantedAuthority(p.name())).toList()));
        settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"main"));when(torrent.withDefaults(any())).thenAnswer(call->call.getArgument(0));when(client.hasEpub(any(),anyLong())).thenReturn(true);when(client.findRevision(any(),anyLong(),any())).thenReturn(null);
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenAnswer(call->{String name=call.getArgument(2);return Long.parseLong(name.replace("Book ",""))+10;});
        when(client.book(any(),anyLong())).thenAnswer(call->{long remote=call.getArgument(1);return new CalibreClient.Book(remote,"Book "+(remote-10),List.of("Author"),remote-10,BigDecimal.ONE,true);});
        for(long id=1;id<=5;id++)jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author,links) VALUES(?,1,?,'Author',?)",id,"Book "+id,String.format("%040X",id));
    }
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    String download(Long... ids) {var filter=new CatalogBookFilter();filter.setSelectedIds(ids);return bulk.create(filter,org.springframework.data.domain.Pageable.unpaged(),false,false,new BulkRequest(null,null,null,null,MultipleHashes.FIRST)).jobId();}
    String calibre(Map<Long,String> dependencies) {
        var waits=dependencies.keySet().stream().sorted().map(id->new CalibreUpdateFlow.Book(0,id,"Book "+id,1,List.of(),List.of(),null)).toList();var options=new HashMap<Long,CalibreUpdateFlow.Choice>();dependencies.forEach((id,job)->options.put(id,new CalibreUpdateFlow.Choice(0,1,true,List.of(),"KEEP",List.of(),false)));
        return transfers.enqueueCatalog(settings.connection(),"main",List.of(),waits,options,null,dependencies);
    }
    String item(String job,long book) {return jdbc.queryForObject("SELECT id FROM calibre_job_items WHERE job_id=? AND epl_id=?",String.class,job,book);}
    String state(String job,long book) {return jdbc.queryForObject("SELECT state FROM calibre_job_items WHERE job_id=? AND epl_id=?",String.class,job,book);}
    void ready(String job,long book,String state) throws Exception {
        byte[] bytes=CalibreTransfersTests.epub(book,"1");String record="record-"+book;
        jdbc.update("INSERT OR IGNORE INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,1,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0)",record,book,String.format("%040X",book),tracking.instanceId());
        jdbc.update("UPDATE calibre_job_items SET state=?,record_id=?,file_index=0,size=? WHERE job_id=? AND epl_id=?",state,record,bytes.length,job,book);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"Book "+book,bytes.length)));
        when(files.download(record,0)).thenAnswer(call->new EpubFiles.Opened("Book "+book,bytes.length,new java.io.ByteArrayInputStream(bytes)));
    }
    @Test void exclusiveJobCancelsUnsentItemsAndKeepsAlreadySentBooks() throws Exception {
        String parent=download(1L,2L,3L),job=calibre(Map.of(1L,parent,2L,parent,3L,parent));ready(job,1,"ACCEPTED");jdbc.update("UPDATE calibre_job_items SET calibre_id=11,message='Enviado y verificado' WHERE id=?",item(job,1));
        bulk.control(parent,"cancel");assertThat(transfers.job(job).state()).isEqualTo("CANCELLED");assertThat(transfers.job(job).accepted()).isEqualTo(1);assertThat(transfers.job(job).cancelled()).isEqualTo(2);assertThat(state(job,1)).isEqualTo("ACCEPTED");assertThat(state(job,2)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT message FROM calibre_job_items WHERE id=?",String.class,item(job,1))).isEqualTo("Enviado y verificado");assertThat(jdbc.queryForObject("SELECT cancelled_by_download_job_id FROM calibre_job_items WHERE id=?",String.class,item(job,2))).isEqualTo(parent);
        transfers.recoverInterrupted();bulk.recover();transfers.cycle();assertThat(transfers.job(job).state()).isEqualTo("CANCELLED");assertThatThrownBy(()->bulk.control(parent,"cancel")).hasMessageContaining("finalizado");verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void mixedJobKeepsItsOtherDependencyAndCompletesWithTheActualCounters() throws Exception {
        String a=download(1L),b=download(2L),job=calibre(Map.of(1L,a,2L,b));bulk.control(a,"cancel");assertThat(transfers.job(job).state()).isEqualTo("QUEUED");assertThat(state(job,1)).isEqualTo("CANCELLED");assertThat(state(job,2)).isEqualTo("WAITING_DOWNLOAD");assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id=?",String.class,b)).isEqualTo("QUEUED");
        ready(job,2,"PENDING");transfers.cycle();assertThat(transfers.job(job).state()).isEqualTo("COMPLETED");assertThat(transfers.job(job).accepted()).isEqualTo(1);assertThat(transfers.job(job).cancelled()).isEqualTo(1);verify(client,times(1)).uploadEpub(any(),any(),eq("Book 2"),anyString());
    }
    @Test void aReadyFileWithoutDownloadDependencyKeepsTheJobMixedAndUserPauseIsPreserved() throws Exception {
        String a=download(1L,2L),job=calibre(Map.of(1L,a,2L,a));ready(job,2,"PENDING");jdbc.update("UPDATE calibre_job_items SET download_job_id=NULL WHERE id=?",item(job,2));transfers.control(job,"pause");bulk.control(a,"cancel");assertThat(transfers.job(job).state()).isEqualTo("PAUSED");assertThat(state(job,1)).isEqualTo("CANCELLED");assertThat(state(job,2)).isEqualTo("PENDING");transfers.recoverInterrupted();assertThat(transfers.job(job).state()).isEqualTo("PAUSED");
    }
    @Test void anUploadAlreadyInFlightFinishesWithoutRepeatingOrRunningCleanup() throws Exception {
        String a=download(1L),b=download(2L),job=calibre(Map.of(1L,a,2L,b));ready(job,1,"PENDING");jdbc.update("UPDATE calibre_job_items SET cleanup_policy='BOOK',cleanup_ids='[99]' WHERE id=?",item(job,1));
        doAnswer(call->{bulk.control(a,"cancel");return 11L;}).when(client).uploadEpub(any(),any(),anyString(),anyString());transfers.cycle();assertThat(state(job,1)).isEqualTo("ACCEPTED");assertThat(state(job,2)).isEqualTo("WAITING_DOWNLOAD");assertThat(jdbc.queryForObject("SELECT message FROM calibre_job_items WHERE id=?",String.class,item(job,1))).contains(DownloadCancellation.REASON);verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void cancellationWhileReadingTheFilePreventsItsUpload() throws Exception {
        String a=download(1L),b=download(2L),job=calibre(Map.of(1L,a,2L,b));ready(job,1,"PENDING");byte[] bytes=CalibreTransfersTests.epub(1,"1");when(files.download("record-1",0)).thenAnswer(call->{bulk.control(a,"cancel");return new EpubFiles.Opened("Book 1",bytes.length,new java.io.ByteArrayInputStream(bytes));});transfers.cycle();assertThat(state(job,1)).isEqualTo("CANCELLED");assertThat(transfers.job(job).state()).isEqualTo("RUNNING");verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void anUnconfirmedUploadRemainsVerifiableAcrossRestartAndIsNotRepeated() throws Exception {
        String a=download(1L),job=calibre(Map.of(1L,a));ready(job,1,"PENDING");doAnswer(call->{bulk.control(a,"cancel");throw new CalibreClient.UnconfirmedUpload();}).when(client).uploadEpub(any(),any(),anyString(),anyString());transfers.cycle();assertThat(transfers.job(job).state()).isEqualTo("CANCELLED");assertThat(state(job,1)).isEqualTo("UNCERTAIN");transfers.recoverInterrupted();transfers.cycle();assertThat(state(job,1)).isEqualTo("UNCERTAIN");verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void cancellingOneLegacyRetryKeepsAnotherLiveRetryAndCancelsOnlyItsOwnBooks() throws Exception {
        String root=download(1L,2L),first=download(1L),other=download(2L),alternative=download(1L);jdbc.update("UPDATE torrent_bulk_jobs SET state='COMPLETED' WHERE id=?",root);jdbc.update("UPDATE torrent_bulk_items SET state='FAILED' WHERE job_id=?",root);
        for(String child:List.of(first,other,alternative))jdbc.update("INSERT INTO job_retry_jobs(kind,child_id,parent_id,root_id,request_id,created_at) VALUES('downloads',?,?,?,?,0)",child,root,root,UUID.randomUUID().toString());
        String job=calibre(Map.of(1L,root,2L,root));bulk.control(first,"cancel");assertThat(state(job,1)).isEqualTo("WAITING_DOWNLOAD");assertThat(state(job,2)).isEqualTo("WAITING_DOWNLOAD");bulk.control(alternative,"cancel");assertThat(state(job,1)).isEqualTo("CANCELLED");assertThat(state(job,2)).isEqualTo("WAITING_DOWNLOAD");assertThat(transfers.job(job).state()).isEqualTo("QUEUED");
    }
    @Test void aFailurePropagatingCancellationRollsBackTheTorrentCancellationToo() throws Exception {
        String parent=download(1L),job=calibre(Map.of(1L,parent));jdbc.execute("CREATE TRIGGER reject_cancel BEFORE UPDATE OF state ON calibre_job_items WHEN NEW.state='CANCELLED' BEGIN SELECT RAISE(ABORT,'test cancellation failure'); END");
        try {assertThatThrownBy(()->bulk.control(parent,"cancel")).isInstanceOf(RuntimeException.class);}finally {jdbc.execute("DROP TRIGGER reject_cancel");}
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id=?",String.class,parent)).isEqualTo("QUEUED");assertThat(state(job,1)).isEqualTo("WAITING_DOWNLOAD");assertThat(transfers.job(job).state()).isEqualTo("QUEUED");
    }
    @Test void sharedParentCancelsOnlyTheAffectedPartOfEachRelatedCalibreJob() throws Exception {
        String a=download(1L),b=download(2L),exclusive=calibre(Map.of(1L,a)),mixed=calibre(Map.of(1L,a,2L,b)),independent=calibre(Map.of(2L,b));bulk.control(a,"cancel");
        assertThat(transfers.job(exclusive).state()).isEqualTo("CANCELLED");assertThat(transfers.job(mixed).state()).isEqualTo("QUEUED");assertThat(state(mixed,1)).isEqualTo("CANCELLED");assertThat(state(mixed,2)).isEqualTo("WAITING_DOWNLOAD");assertThat(transfers.job(independent).state()).isEqualTo("QUEUED");assertThat(state(independent,2)).isEqualTo("WAITING_DOWNLOAD");
    }
    @Test void aRejectedInFlightUploadDoesNotReturnToPendingAfterPartialCancellation() throws Exception {
        String a=download(1L),b=download(2L),job=calibre(Map.of(1L,a,2L,b));ready(job,1,"PENDING");doAnswer(call->{bulk.control(a,"cancel");throw new CalibreClient.Busy();}).when(client).uploadEpub(any(),any(),anyString(),anyString());transfers.cycle();assertThat(state(job,1)).isEqualTo("CANCELLED");assertThat(state(job,2)).isEqualTo("WAITING_DOWNLOAD");transfers.recoverInterrupted();transfers.cycle();verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void cancellingDuringCleanupKeepsTheUploadedBookAndStopsFurtherDeletions() throws Exception {
        String a=download(1L),b=download(2L),job=calibre(Map.of(1L,a,2L,b));ready(job,1,"CLEANUP_PENDING");jdbc.update("UPDATE calibre_job_items SET calibre_id=11,cleanup_policy='BOOK',cleanup_ids='[99,100]' WHERE id=?",item(job,1));
        when(client.book(any(),eq(99L))).thenAnswer(call->{bulk.control(a,"cancel");return new CalibreClient.Book(99,"Old book",List.of("Author"),1L,new BigDecimal("0.5"),true);});transfers.cycle();assertThat(state(job,1)).isEqualTo("ACCEPTED");assertThat(state(job,2)).isEqualTo("WAITING_DOWNLOAD");assertThat(jdbc.queryForObject("SELECT message FROM calibre_job_items WHERE id=?",String.class,item(job,1))).contains(DownloadCancellation.REASON);verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }

    @Test void anExplicitVerifiedRetryClearsTheOldCancellationIntent() throws Exception {
        String a=download(1L),job=calibre(Map.of(1L,a));ready(job,1,"UNCERTAIN");bulk.control(a,"cancel");
        transfers.resolve(job,new CalibreTransfers.Resolution(item(job,1),"retry",null,true));assertThat(jdbc.queryForObject("SELECT cancelled_by_download_job_id FROM calibre_job_items WHERE id=?",String.class,item(job,1))).isNull();
        transfers.cycle();assertThat(state(job,1)).isEqualTo("ACCEPTED");verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }

}
