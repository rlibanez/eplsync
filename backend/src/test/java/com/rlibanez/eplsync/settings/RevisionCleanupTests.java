package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentClient;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.revision-cleanup.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class RevisionCleanupTests {
    @Autowired RevisionCleanup cleanup;
    @Autowired com.rlibanez.eplsync.torrent.updates.UpdateCleanupRepository cleanups;
    @Autowired com.rlibanez.eplsync.torrent.updates.CleanupQueue cleanupQueue;
    @Autowired WorkListings listings;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.rlibanez.eplsync.maintenance.DatabaseResetService reset;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    @Autowired CalibreSettings settings;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @MockitoBean TorrentClientService torrent;
    @MockitoBean EpubFiles files;
    @MockitoBean CalibreClient client;
    TorrentClient adapter;Account owner;String old,newer;
    List<RemoteTorrent> remote;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM revision_cleanup_jobs");jdbc.update("DELETE FROM torrent_update_cleanup");jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM catalog_books");jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM scheduled_tasks");downloads.deleteAll();accounts.clear();
        owner=accounts.initialize("Owner","owner@example.com","password123","password123");login(owner);
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        old=download(1,1,"A");newer=download(1,2,"B");
        remote=new ArrayList<>(List.of(remote("A","/downloads/old.epub"),remote("B","/downloads/new.epub")));
        adapter=mock(TorrentClient.class);
        when(adapter.listTorrents()).thenAnswer(call->List.copyOf(remote));
        when(adapter.listTorrents(anySet())).thenAnswer(call->List.copyOf(remote));
        doAnswer(call->{String hash=call.getArgument(0);remote.removeIf(row->row.hash().equals(hash));return null;}).when(adapter).deleteTorrent(anyString(),anyBoolean());
        when(torrent.exclusiveClient(any())).thenAnswer(call->{Function<TorrentClient,?> action=call.getArgument(0);return action.apply(adapter);});
        when(files.list(anyString())).thenReturn(List.of(new EpubFiles.Item(0,"new.epub",20)));
        calibre(10,1);calibre(20,2);
        when(client.book(any(),eq(10L))).thenReturn(book(10,1));when(client.book(any(),eq(20L))).thenReturn(book(20,2));
        when(client.hasEpub(any(),anyLong())).thenReturn(true);
        when(client.formats(any(),anyLong())).thenReturn(List.of("EPUB","PDF"));
    }
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    void login(Account a) {var authorities=a.permissions().stream().map(p->new SimpleGrantedAuthority(p.name())).toList();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(a,null,authorities));}
    String download(long epl,double revision,String hash) {var row=new DownloadRecord();row.setEplId(epl);row.setRevision(revision);row.setHash(hash.repeat(40));row.setClient("qbittorrent");row.setClientInstanceId(tracking.instanceId());row.setStatus(DownloadStatus.DOWNLOADED);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());return downloads.save(row).getId();}
    RemoteTorrent remote(String hash,String path) {return new RemoteTorrent(hash.repeat(40),DownloadStatus.DOWNLOADED,Instant.now(),Set.of(),path,"Book","/downloads",false);}
    void calibre(long id,int revision) {jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?, 'generation',1,?,1,?,'Book','Author')",UUID.randomUUID().toString(),id,revision);}
    CalibreClient.Book book(long id,int revision) {return new CalibreClient.Book(id,"Book",List.of("Author"),1L,BigDecimal.valueOf(revision),true);}
    RevisionCleanup.Job preview(String source,String action) {return cleanup.preview(new RevisionCleanup.PreviewInput(source,action,List.of()));}
    RevisionCleanup.Item item(RevisionCleanup.Job job) {return cleanup.items(job.id(),0,1000).items().getFirst();}
    RevisionCleanup.Job start(RevisionCleanup.Job job) {return cleanup.start(job.id(),new RevisionCleanup.Confirmation(List.of(item(job).id()),true,true));}
    @Test void previewIsReadOnlyAndExcludesEqualRevisions() {download(2,1,"C");download(2,1,"D");var job=preview("downloads","FILES");assertThat(job.total()).isEqualTo(1);assertThat(job.ready()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(item(job).replacementId()).isEqualTo(newer);}
    @Test void previewShowsPendingHigherRevisionAsExcludedInsteadOfHidingOldCopy() {
        jdbc.update("UPDATE torrent_downloads SET status='QUEUED' WHERE id=?",newer);
        remote.set(1,new RemoteTorrent("B".repeat(40),DownloadStatus.QUEUED,null,Set.of(),null));
        for(var action:List.of("RECORD","TORRENT","FILES")) {
            var job=preview("downloads",action);
            assertThat(job.total()).isEqualTo(1);assertThat(job.ready()).isZero();assertThat(job.excluded()).isEqualTo(1);
            assertThat(item(job).oldId()).isEqualTo(old);assertThat(item(job).replacementId()).isEqualTo(newer);
            assertThat(item(job).message()).contains("no está descargada");
            assertThatThrownBy(()->start(job)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        }
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(downloads.findById(old)).isPresent();
    }
    @Test void previewBecomesAvailableAfterHigherRevisionCompletesAndIsAccessible() {
        jdbc.update("UPDATE torrent_downloads SET status='QUEUED' WHERE id=?",newer);
        remote.set(1,new RemoteTorrent("B".repeat(40),DownloadStatus.QUEUED,null,Set.of(),null));
        assertThat(preview("downloads","FILES").excluded()).isEqualTo(1);
        jdbc.update("UPDATE torrent_downloads SET status='DOWNLOADED' WHERE id=?",newer);
        remote.set(1,remote("B","/downloads/new.epub"));
        var job=preview("downloads","FILES");assertThat(job.ready()).isEqualTo(1);assertThat(job.excluded()).isZero();
        start(job);cleanup.cycle();verify(adapter).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void previewKeepsCompletedHigherRevisionWhenLatestIsStillQueued() {
        var latest=download(1,3,"C");jdbc.update("UPDATE torrent_downloads SET status='QUEUED' WHERE id=?",latest);
        remote.add(new RemoteTorrent("C".repeat(40),DownloadStatus.QUEUED,null,Set.of(),null,"Book","/downloads",true));
        var job=preview("downloads","FILES");var rows=cleanup.items(job.id(),0,1000).items();
        assertThat(job.total()).isEqualTo(2);assertThat(job.ready()).isEqualTo(1);assertThat(job.excluded()).isEqualTo(1);
        assertThat(rows).anySatisfy(row->{assertThat(row.oldId()).isEqualTo(old);assertThat(row.replacementId()).isEqualTo(newer);assertThat(row.state()).isEqualTo("READY");});
        assertThat(rows).anySatisfy(row->{assertThat(row.oldId()).isEqualTo(newer);assertThat(row.replacementId()).isEqualTo(latest);assertThat(row.state()).isEqualTo("EXCLUDED");});
        start(job);cleanup.cycle();verify(adapter).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());verify(adapter,never()).deleteTorrent(eq("C".repeat(40)),anyBoolean());
    }
    RevisionCleanup.Job forced(String action) {return cleanup.preview(new RevisionCleanup.PreviewInput("downloads",action,List.of(),true));}
    void pendingReplacement() {
        jdbc.update("UPDATE torrent_downloads SET status='QUEUED' WHERE id=?",newer);
        remote.set(1,new RemoteTorrent("B".repeat(40),DownloadStatus.QUEUED,null,Set.of(),null,"Book","/downloads",true));
    }
    com.rlibanez.eplsync.torrent.updates.UpdateCleanup automaticCleanup() {
        var entry=new com.rlibanez.eplsync.torrent.updates.UpdateCleanup();entry.setJobId("auto");entry.setDownloadId(old);entry.setEplId(1L);entry.setHash("A".repeat(40));entry.setClientInstanceId(tracking.instanceId());
        entry.setState(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.WAITING);entry.setPreviousVersions(com.rlibanez.eplsync.torrent.updates.PreviousVersions.REMOVE_TORRENT_AND_FILES);
        entry.setTargetHashes("[\""+"B".repeat(40)+"\"]");entry.setAutomatic(true);entry.setImmediate(false);entry.setCreatedAt(Instant.now());entry.setUpdatedAt(Instant.now());
        return cleanups.saveAndFlush(entry);
    }
    @Test void forcedCleanupIsPersistedAndNeverDeletesReplacement() {
        pendingReplacement();var job=forced("FILES");assertThat(job.allowUndownloaded()).isTrue();assertThat(job.ready()).isEqualTo(1);
        start(job);jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING' WHERE id=?",job.id());cleanup.recover();cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        verify(files,never()).list(newer);verify(adapter).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void forcedCleanupFulfilsIdenticalAutomaticDeletionWithoutRepeatingIt() {
        pendingReplacement();
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state,created_at) VALUES('auto',1,1,0,1,'UPDATE','CLEANUP_WAIT',?)",Instant.now().toEpochMilli());
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,epl_id,revision,position,attempts,state) VALUES('auto-item','auto',1,2,0,1,'ACCEPTED')");
        var automatic=automaticCleanup();var job=forced("FILES");assertThat(job.ready()).isEqualTo(1);start(job);cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        assertThat(cleanups.findById(automatic.getId()).orElseThrow().getState()).isEqualTo(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REMOVED);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id='auto'",String.class)).isEqualTo("COMPLETED");
        cleanupQueue.runAutomatic(20);verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void verificationCoordinatesHistoryDeletionWithAutomaticRequest() {
        pendingReplacement();var automatic=automaticCleanup();automatic.setDeleteHistory(true);cleanups.saveAndFlush(automatic);
        var job=start(cleanup.preview(new RevisionCleanup.PreviewInput("downloads","FILES",List.of(),true,true)));
        doThrow(new IllegalStateException("timeout")).when(adapter).deleteTorrent("A".repeat(40),true);
        cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);assertThat(downloads.findById(old)).isPresent();
        remote.removeIf(row->row.hash().equals("A".repeat(40)));
        cleanup.control(job.id(),"verify");cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);assertThat(cleanup.get(job.id()).uncertain()).isZero();
        assertThat(downloads.findById(old)).isEmpty();assertThat(downloads.findById(newer)).isPresent();
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void incompatibleHistoryChoicesCannotSilentlyFulfilAutomaticDeletion(boolean manualHistory) {
        pendingReplacement();var automatic=automaticCleanup();automatic.setDeleteHistory(!manualHistory);cleanups.saveAndFlush(automatic);
        var job=cleanup.preview(new RevisionCleanup.PreviewInput("downloads","FILES",List.of(),true,manualHistory));assertThat(job.ready()).isZero();assertThat(job.excluded()).isEqualTo(1);
        assertThat(downloads.findById(old)).isPresent();verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void automaticDeletionWinningAfterPreviewIsNotRepeatedByManualJob() {
        var automatic=automaticCleanup();var job=start(forced("FILES"));
        cleanupQueue.runAutomatic(20);
        assertThat(cleanups.findById(automatic.getId()).orElseThrow().getState()).isEqualTo(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REMOVED);
        cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
        assertThat(downloads.findById(newer)).isPresent();
    }
    @Test void uncertainManualDeletionIsNeverRepeatedByAutomaticCycles() {
        pendingReplacement();var automatic=automaticCleanup();var job=start(forced("FILES"));
        doThrow(new IllegalStateException("timeout")).when(adapter).deleteTorrent("A".repeat(40),true);
        cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);
        for(int n=0;n<3;n++) cleanupQueue.runAutomatic(20);
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);
        assertThat(cleanups.findById(automatic.getId()).orElseThrow().getState()).isNotEqualTo(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REMOVED);
        remote.removeIf(row->row.hash().equals("A".repeat(40)));
        cleanupQueue.runAutomatic(20);cleanup.control(job.id(),"verify");cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        assertThat(downloads.findById(old)).isPresent();assertThat(downloads.findById(newer)).isPresent();
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void concurrentManualAndAutomaticCleanupUseSameClientLockAndDeleteOnlyOnce() throws Exception {
        pendingReplacement();var automatic=automaticCleanup();var job=start(forced("FILES"));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var automaticStarted=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{
            if(Thread.currentThread().getName().equals("audit-automatic")) automaticStarted.countDown();
            Function<TorrentClient,?> action=call.getArgument(0);return tracking.exclusive(()->action.apply(adapter));
        }).when(torrent).exclusiveClient(any());
        doAnswer(call->{entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");remote.removeIf(row->row.hash().equals("A".repeat(40)));return null;}).when(adapter).deleteTorrent("A".repeat(40),true);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var manual=Thread.ofPlatform().name("audit-manual").unstarted(()->{try {cleanup.cycle();}catch(Throwable ex) {failure.compareAndSet(null,ex);}});
        var automaticThread=Thread.ofPlatform().name("audit-automatic").unstarted(()->{try {cleanupQueue.runAutomatic(20);}catch(com.rlibanez.eplsync.exception.TorrentOperationException ex) {
            // The production client uses tryLock: a busy maintenance cycle retries on its next tick.
            if(!Objects.toString(ex.getMessage(),"").contains("Hay envíos o una sincronización en curso")) failure.compareAndSet(null,ex);
        }catch(Throwable ex) {failure.compareAndSet(null,ex);}});
        try {
            manual.start();assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            automaticThread.start();assertThat(automaticStarted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();manual.join(5000);if(automaticThread.getState()!=Thread.State.NEW) automaticThread.join(5000);}
        assertThat(manual.isAlive()).isFalse();assertThat(automaticThread.isAlive()).isFalse();assertThat(failure.get()).isNull();
        cleanupQueue.runAutomatic(20);
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        assertThat(cleanups.findById(automatic.getId()).orElseThrow().getState()).isEqualTo(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REMOVED);
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
        assertThat(remote).anyMatch(row->row.hash().equals("B".repeat(40)));
    }
    @Test void automaticCleanupTargetingRetainedRevisionAfterPreviewBlocksManualDeletion() {
        var job=start(forced("FILES"));var latest=download(1,3,"C");remote.add(remote("C","/downloads/latest.epub"));
        var automatic=automaticCleanup();automatic.setHash("B".repeat(40));automatic.setDownloadId(newer);automatic.setTargetHashes("[\""+"C".repeat(40)+"\"]");cleanups.saveAndFlush(automatic);
        cleanup.cycle();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(downloads.findById(newer)).isPresent();assertThat(downloads.findById(latest)).isPresent();
    }
    @Test void forcedCleanupStillRevalidatesRetainedHashAfterPreview() {
        pendingReplacement();var job=start(forced("FILES"));jdbc.update("UPDATE torrent_downloads SET hash=? WHERE id=?","C".repeat(40),newer);
        cleanup.cycle();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    RevisionCleanup.Job failedDeletion() {
        var job=start(forced("FILES"));
        doThrow(new IllegalStateException("connection lost")).when(adapter).deleteTorrent("A".repeat(40),true);
        cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);return job;
    }
    void restoreDeletion() {doAnswer(call->{remote.removeIf(row->row.hash().equals("A".repeat(40)));return null;}).when(adapter).deleteTorrent("A".repeat(40),true);}
    RevisionCleanup.RetryInput retryInput(String request) {return new RevisionCleanup.RetryInput(request,true,true);}
    @Test void explicitRetryReusesRemovalRequestAndNeverDeletesRetainedRevision() {
        var job=failedDeletion();var requests=jdbc.queryForList("SELECT id FROM torrent_update_cleanup WHERE job_id=?",String.class,"revision:"+job.id());
        restoreDeletion();cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);assertThat(cleanup.get(job.id()).uncertain()).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM torrent_update_cleanup WHERE job_id=?",String.class,"revision:"+job.id())).containsExactlyElementsOf(requests);
        verify(adapter,times(2)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());assertThat(downloads.findById(newer)).isPresent();
        cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));cleanup.cycle();verify(adapter,times(2)).deleteTorrent("A".repeat(40),true);
    }
    @Test void lostRetryResponseCannotAutomaticallyRepeatAnUncertainDeletion() {
        var job=failedDeletion();cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);
        cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));cleanup.cycle();verify(adapter,times(2)).deleteTorrent("A".repeat(40),true);
        restoreDeletion();cleanup.retryUnconfirmed(job.id(),retryInput("request-two"));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        verify(adapter,times(3)).deleteTorrent("A".repeat(40),true);
    }
    @Test void retryRequiresExplicitAndDestructiveConfirmation() {
        var job=failedDeletion();
        assertThatThrownBy(()->cleanup.retryUnconfirmed(job.id(),new RevisionCleanup.RetryInput("request-one",false,true))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(()->cleanup.retryUnconfirmed(job.id(),new RevisionCleanup.RetryInput("request-one",true,false))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);verify(adapter,times(1)).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void retryCannotBypassOwnershipOrChangedConnection() {
        var job=failedDeletion();
        var other=new Account("other","Other","other@example.com","ADMIN","ACTIVE",false,null,0,owner.permissions(),Instant.now());login(other);
        assertThatThrownBy(()->cleanup.retryUnconfirmed(job.id(),retryInput("request-one"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        login(owner);jdbc.update("UPDATE revision_cleanup_jobs SET context='changed' WHERE id=?",job.id());
        assertThatThrownBy(()->cleanup.retryUnconfirmed(job.id(),retryInput("request-one"))).hasMessageContaining("conexión cambió");
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);
    }
    @Test void retryDoesNotEnableCalibreOrRecordDeletion() {
        var record=preview("downloads","RECORD");var calibre=preview("calibre","BOOK");
        assertThatThrownBy(()->cleanup.retryUnconfirmed(record.id(),retryInput("request-one"))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(()->cleanup.retryUnconfirmed(calibre.id(),retryInput("request-two"))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void retryOnlyChecksAbsenceWhenTorrentWasAlreadyRemoved() {
        var job=failedDeletion();remote.removeIf(row->row.hash().equals("A".repeat(40)));
        cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void retryRevalidatesRetainedRevisionAndSharedPaths() {
        var job=failedDeletion();restoreDeletion();cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));
        remote.set(0,remote("A","/downloads/new.epub"));cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);
        remote.clear();remote.add(remote("A","/downloads/old.epub"));cleanup.retryUnconfirmed(job.id(),retryInput("request-two"));cleanup.cycle();
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);assertThat(downloads.findById(newer)).isPresent();
    }
    @Test void explicitRetryResolvesAutomaticRequestAlsoMarkedUnconfirmed() {
        pendingReplacement();var automatic=automaticCleanup();var job=failedDeletion();cleanupQueue.runAutomatic(20);
        assertThat(cleanups.findById(automatic.getId()).orElseThrow().getState()).isEqualTo(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REQUESTED);
        restoreDeletion();cleanup.retryUnconfirmed(job.id(),retryInput("request-one"));cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        assertThat(cleanups.findById(automatic.getId()).orElseThrow().getState()).isEqualTo(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REMOVED);
        verify(adapter,times(2)).deleteTorrent("A".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void forcedCleanupDoesNotOverrideUnconfirmedOrIncompatibleDeletion() {
        pendingReplacement();var automatic=automaticCleanup();
        assertThat(forced("TORRENT").excluded()).isEqualTo(1);
        automatic.setState(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REQUESTED);cleanups.saveAndFlush(automatic);
        assertThat(forced("FILES").excluded()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void forcedCleanupStillProtectsSharedPathsAndMissingReplacement() {
        pendingReplacement();remote.set(0,remote("A","/downloads/shared"));remote.set(1,new RemoteTorrent("B".repeat(40),DownloadStatus.QUEUED,null,Set.of(),"/downloads/shared/new.epub"));
        assertThat(forced("FILES").excluded()).isEqualTo(1);
        remote.remove(1);assertThat(forced("FILES").excluded()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void forcedCleanupRevalidatesIdentityAndMissingTorrentBeforeDispatch() {
        pendingReplacement();var job=start(forced("FILES"));
        jdbc.update("UPDATE torrent_downloads SET revision=3 WHERE id=?",old);cleanup.cycle();
        assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void forcedCleanupDoesNotOverrideActiveSendingJob() {
        pendingReplacement();jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state,created_at) VALUES('sending',1,1,0,1,'UPDATE','RUNNING',?)",Instant.now().toEpochMilli());
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,epl_id,revision,position,attempts,state) VALUES('sending-item','sending',1,2,0,1,'IN_FLIGHT')");
        assertThat(forced("FILES").excluded()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void forcedCleanupDoesNotApplyToCalibre() {
        assertThatThrownBy(()->cleanup.preview(new RevisionCleanup.PreviewInput("calibre","BOOK",List.of(),true))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    @Test void previewExcludesUnreadableReplacement() {when(files.list(newer)).thenThrow(new IllegalStateException());var job=preview("downloads","FILES");assertThat(job.excluded()).isEqualTo(1);assertThatThrownBy(()->start(job)).hasMessageContaining("no puede eliminarse");}
    @Test void sharedFilesAreExcluded() {remote.set(0,remote("A","/downloads/new.epub"));assertThat(preview("downloads","FILES").excluded()).isEqualTo(1);assertThat(preview("downloads","TORRENT").ready()).isEqualTo(1);}
    @Test void pathChangeAfterPreviewStopsDeletion() {var job=preview("downloads","FILES");start(job);remote.set(0,remote("A","/downloads/new.epub"));cleanup.cycle();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());}
    @Test void replacementMissingStopsAllActions() {var job=preview("downloads","RECORD");start(job);remote.remove(1);cleanup.cycle();assertThat(downloads.findById(old)).isPresent();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);}
    @Test void torrentCleanupKeepsHistoryByDefaultForBothClientActions() {
        for(var action:List.of("TORRENT","FILES")) {
            remote=new ArrayList<>(List.of(remote("A","/downloads/old.epub"),remote("B","/downloads/new.epub")));
            var job=start(cleanup.preview(new RevisionCleanup.PreviewInput("downloads",action,List.of(),false,false)));
            assertThat(job.deleteHistory()).isFalse();cleanup.cycle();
            assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
            assertThat(downloads.findById(old)).isPresent();assertThat(downloads.findById(newer)).isPresent();
            jdbc.update("DELETE FROM revision_cleanup_jobs");
        }
        verify(adapter).deleteTorrent("A".repeat(40),false);verify(adapter).deleteTorrent("A".repeat(40),true);
        verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
    }
    @Test void uncertainCleanupKeepsHistoryUntilExplicitlyConfirmedAbsent() {
        doThrow(new RuntimeException("Disconnected")).when(adapter).deleteTorrent(anyString(),anyBoolean());
        var job=start(cleanup.preview(new RevisionCleanup.PreviewInput("downloads","FILES",List.of(),false,true)));
        cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);
        assertThat(downloads.findById(old)).isPresent();
        remote.removeIf(row->row.hash().equals("A".repeat(40)));
        cleanup.control(job.id(),"verify");cleanup.cycle();
        assertThat(downloads.findById(old)).isEmpty();assertThat(downloads.findById(newer)).isPresent();
        verify(adapter,times(1)).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void keepClientActionRequiresExplicitHistoryDeletion() {
        assertThatThrownBy(()->cleanup.preview(new RevisionCleanup.PreviewInput("downloads","KEEP",List.of(),false,false)))
            .isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        var job=start(cleanup.preview(new RevisionCleanup.PreviewInput("downloads","KEEP",List.of(),false,true)));
        assertThat(job.action()).isEqualTo("RECORD");assertThat(job.deleteHistory()).isTrue();cleanup.cycle();
        assertThat(downloads.findById(old)).isEmpty();assertThat(downloads.findById(newer)).isPresent();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void recordOnlyNeverDeletesTorrent() {var job=start(preview("downloads","RECORD"));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);assertThat(downloads.findById(old)).isEmpty();assertThat(downloads.findById(newer)).isPresent();verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());}
    @Test void deletesTorrentAndFilesOnlyAfterChecks() {var job=start(cleanup.preview(new RevisionCleanup.PreviewInput("downloads","FILES",List.of(),false,true)));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);assertThat(downloads.findById(old)).isEmpty();verify(adapter).deleteTorrent("A".repeat(40),true);assertThat(remote).hasSize(1);}
    @Test void torrentOnlyKeepsFiles() {var job=start(preview("downloads","TORRENT"));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);verify(adapter).deleteTorrent("A".repeat(40),false);}
    @Test void explicitDestructiveConfirmationIsMandatoryAndStartIdempotent() {var job=preview("downloads","FILES");var selection=List.of(item(job).id());assertThatThrownBy(()->cleanup.start(job.id(),new RevisionCleanup.Confirmation(selection,true,false))).hasMessageContaining("irreversible");start(job);start(job);cleanup.cycle();verify(adapter,times(1)).deleteTorrent(anyString(),anyBoolean());}
    @Test void unconfirmedDeletionIsNeverAutomaticallyRepeated() {doNothing().when(adapter).deleteTorrent(anyString(),anyBoolean());var job=start(preview("downloads","TORRENT"));cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);cleanup.cycle();cleanup.control(job.id(),"verify");cleanup.cycle();verify(adapter,times(1)).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);remote.remove(0);cleanup.control(job.id(),"verify");cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);}
    @Test void cancellationKeepsEverything() {var job=start(preview("downloads","FILES"));cleanup.control(job.id(),"cancel");cleanup.cycle();assertThat(cleanup.get(job.id()).state()).isEqualTo("CANCELLED");verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(downloads.findById(old)).isPresent();}
    @Test void previewExpiresAndCannotBeTamperedWith() {var job=preview("downloads","RECORD");assertThatThrownBy(()->cleanup.start(job.id(),new RevisionCleanup.Confirmation(List.of(UUID.randomUUID().toString()),true,false))).hasMessageContaining("selección");jdbc.update("UPDATE revision_cleanup_jobs SET expires_at=0 WHERE id=?",job.id());assertThatThrownBy(()->start(job)).hasMessageContaining("caducó");}
    @Test void changedConnectionInvalidatesPreview() {var job=preview("calibre","BOOK");settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"other"));assertThatThrownBy(()->start(job)).hasMessageContaining("conexión");}
    @Test void pendingJobForSameBookIsExcluded() {jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('active','now','now','Owner','target','main','source','QUEUED')");jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state) VALUES('active-item','active',0,'record',0,'book.epub',1,1,'1.0','PENDING')");assertThat(preview("downloads","FILES").excluded()).isEqualTo(1);}
    @Test void higherCalibreRevisionMustContainEpub() {when(client.hasEpub(any(),eq(20L))).thenReturn(false);assertThat(preview("calibre","BOOK").excluded()).isEqualTo(1);}
    @Test void calibreEpubOnlyCleanupIsRejected() {assertThatThrownBy(()->preview("calibre","EPUB")).hasMessageContaining("entradas completas");verify(client,never()).remove(any(),anyLong(),anyBoolean());}

    @Test void calibreDoesNotReplayUnconfirmedDeletion() {var job=start(preview("calibre","BOOK"));cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);cleanup.control(job.id(),"verify");cleanup.cycle();verify(client,times(1)).remove(any(),eq(10L),eq(true));}
    @Test void calibreIdentityChangeAfterPreviewBlocksDeletion() {var job=start(preview("calibre","BOOK"));when(client.book(any(),eq(20L))).thenReturn(new CalibreClient.Book(20,"Different",List.of(),2L,BigDecimal.TWO,true));cleanup.cycle();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);verify(client,never()).remove(any(),anyLong(),anyBoolean());}
    @Test void restartContinuesUndispatchedCleanupExactlyOnceAndRetainsReplacement() {
        var job=start(preview("downloads","FILES"));
        jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',dispatched=0 WHERE job_id=?",job.id());
        cleanup.recover();assertThat(item(job).state()).isEqualTo("PENDING");cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        cleanup.recover();cleanup.cycle();
        verify(adapter,times(1)).deleteTorrent("A".repeat(40),true);
        verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());
        assertThat(downloads.findById(newer)).isPresent();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void restartConfirmsDispatchedDeletionWithoutRepeatingItAndRespectsHistoryChoice(boolean deleteHistory) {
        var job=start(cleanup.preview(new RevisionCleanup.PreviewInput("downloads","FILES",List.of(),false,deleteHistory)));
        jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',dispatched=1 WHERE job_id=?",job.id());
        remote.removeIf(row->row.hash().equals("A".repeat(40)));
        cleanup.recover();cleanup.cycle();
        assertThat(cleanup.get(job.id()).state()).isEqualTo("COMPLETED");assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        assertThat(downloads.findById(old).isPresent()).isEqualTo(!deleteHistory);
        assertThat(downloads.findById(newer)).isPresent();verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void restartPreservesPausedCleanupAndOnlyMarksItsInFlightItemUncertain() {
        var job=start(preview("downloads","FILES"));
        jdbc.update("UPDATE revision_cleanup_jobs SET state='PAUSED' WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',dispatched=1 WHERE job_id=?",job.id());
        clearInvocations(adapter,client);
        cleanup.recover();cleanup.cycle();
        assertThat(cleanup.get(job.id()).state()).isEqualTo("PAUSED");assertThat(item(job).state()).isEqualTo("UNCERTAIN");
        verifyNoInteractions(adapter,client);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"RUNNING","VERIFYING"})
    void restartPreservesCancellationRequestedBeforeTheCrash(String itemState) {
        var job=start(preview("downloads","FILES"));
        jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING',cancel_requested=1 WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state=?,dispatched=? WHERE job_id=?",itemState,itemState.equals("VERIFYING"),job.id());
        cleanup.recover();cleanup.cycle();
        assertThat(cleanup.get(job.id()).state()).isEqualTo("CANCELLED");assertThat(item(job).state()).isEqualTo(itemState.equals("VERIFYING")?"UNCERTAIN":"CANCELLED");
        assertThat(cleanup.get(job.id()).pending()).isZero();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());
    }
    @Test void restartChecksCalibreDeletionWithoutRepeatingIt() {
        var job=start(preview("calibre","BOOK"));
        jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',dispatched=1 WHERE job_id=?",job.id());
        when(client.book(any(),eq(10L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        cleanup.recover();cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE calibre_id=20",Integer.class)).isEqualTo(1);
    }
    @Test void restartKeepsAStillPresentCalibreDeletionUncertain() {
        var job=start(preview("calibre","BOOK"));
        jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',dispatched=1 WHERE job_id=?",job.id());
        cleanup.recover();cleanup.cycle();
        assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void interruptedDestructiveWorkIsVerifiedAutomaticallyWithoutRepeatingDeletion() {var job=start(preview("downloads","FILES"));jdbc.update("UPDATE revision_cleanup_jobs SET state='RUNNING' WHERE id=?",job.id());jdbc.update("UPDATE revision_cleanup_items SET state='RUNNING',dispatched=1 WHERE job_id=?",job.id());cleanup.recover();assertThat(cleanup.get(job.id()).state()).isEqualTo("QUEUED");assertThat(item(job).state()).isEqualTo("VERIFYING");cleanup.cycle();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());}
    @Test void entireCalibreEntryIsRemovedOnlyAfterAbsenceConfirmed() {
        doAnswer(call->{when(client.book(any(),eq(10L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));return null;}).when(client).remove(any(),eq(10L),eq(true));
        var job=start(preview("calibre","BOOK"));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE calibre_id=10",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE calibre_id=20",Integer.class)).isEqualTo(1);
    }
    @Test void anotherCleanupCannotReserveTheSameBook() {start(preview("downloads","RECORD"));assertThat(preview("downloads","RECORD").excluded()).isEqualTo(1);assertThat(preview("calibre","BOOK").excluded()).isEqualTo(1);}
    @Test void metadataFreeTorrentCanBeRemovedWithoutTouchingReplacement() {remote.set(0,new RemoteTorrent("A".repeat(40),DownloadStatus.QUEUED,null,Set.of(),null,"Book","/downloads",true));var job=start(preview("downloads","FILES"));cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);verify(adapter).deleteTorrent("A".repeat(40),false);}
    @Test void unchangedVerificationFailuresDoNotRepeatEvents() {var job=start(preview("calibre","BOOK"));cleanup.cycle();for(int n=0;n<3;n++) {cleanup.control(job.id(),"verify");cleanup.cycle();}assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE action='REVISION_CLEANUP' AND operation_id=?",Integer.class,job.id())).isLessThanOrEqualTo(2);}
    @Test void resetErasesPreviewsAndTheirItems() {preview("downloads","RECORD");reset.reset(false);assertThat(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_items",Integer.class)).isZero();}
    @Test void httpMutationsRequireCsrfAndReturnOnlyOwnerJobs() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/revision-cleanups/preview").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)).contentType("application/json").content("{\"source\":\"downloads\",\"action\":\"FILES\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        login(owner);var job=start(preview("downloads","RECORD"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/revision-cleanups/"+job.id()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(job.id()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/torrent/jobs?type=CLEANUP").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].type").value("CLEANUP")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/calibre/transfers?type=CLEANUP").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meta.totalItems").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/revision-cleanups/"+job.id()+"/items?size=1001").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void verificationCannotResumePendingDestructiveWork() {var job=start(preview("downloads","FILES"));jdbc.update("UPDATE revision_cleanup_jobs SET state='PAUSED' WHERE id=?",job.id());assertThatThrownBy(()->cleanup.control(job.id(),"verify")).hasMessageContaining("cancela");verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());}
    @Test void recordVerificationNeverRepeatsEvenLocalDeletion() {var job=start(preview("downloads","RECORD"));jdbc.update("UPDATE revision_cleanup_jobs SET state='COMPLETED' WHERE id=?",job.id());jdbc.update("UPDATE revision_cleanup_items SET state='UNCERTAIN',dispatched=1 WHERE job_id=?",job.id());cleanup.control(job.id(),"verify");cleanup.cycle();assertThat(downloads.findById(old)).isPresent();assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);}
    @Test void selectedCountDoesNotChangeWhenRecordWasAlreadyDeleted() {var job=start(preview("downloads","TORRENT"));downloads.deleteById(old);cleanup.cycle();assertThat(cleanup.get(job.id()).selected()).isEqualTo(1);verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());}
    @Test void cleanupsShareTheMainListingsWithFiltersAndPaging() {
        var cleanupJob=start(preview("downloads","RECORD"));
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state,created_at,event_actor_username) VALUES('normal',1,1,0,5,'DOWNLOAD','COMPLETED',?,'Owner')",Instant.now().minusSeconds(100).toEpochMilli());
        var first=listings.list("downloads",0,1,List.of(),"","",null,null,"createdAt,desc");
        var excludedType=listings.list("downloads",0,1,List.of(),"CLEANUP","",null,null,"createdAt,desc","eq","ne");
        assertThat(excludedType.meta().totalItems()).isEqualTo(1);assertThat(excludedType.items().getFirst()).containsEntry("jobId","normal");
        assertThat(listings.neighbors("downloads","normal",List.of(),"CLEANUP","",null,null,"createdAt,desc","eq","ne")).isEqualTo(new WorkListings.Neighbors(null,null));
        var excluded=listings.list("downloads",0,1,List.of("QUEUED"),"","",null,null,"createdAt,desc","ne");
        assertThat(excluded.meta().totalItems()).isEqualTo(1);assertThat(excluded.items().getFirst()).containsEntry("jobId","normal");
        assertThat(listings.neighbors("downloads","normal",List.of("QUEUED"),"","",null,null,"createdAt,desc","ne")).isEqualTo(new WorkListings.Neighbors(null,null));
        assertThatThrownBy(()->listings.list("calibre",0,20,List.of(),"","",null,null,"createdAt,desc","invalid")).hasMessageContaining("Operador");
        assertThat(first.meta().totalItems()).isEqualTo(2);assertThat(first.items().getFirst()).containsEntry("jobId",cleanupJob.id()).containsEntry("type","CLEANUP");
        assertThat(listings.list("downloads",1,1,List.of(),"","",null,null,"createdAt,desc").items().getFirst()).containsEntry("jobId","normal");
        assertThat(listings.list("downloads",0,20,List.of("QUEUED"),"CLEANUP","own",null,null,"type,asc;createdAt,desc").meta().totalItems()).isEqualTo(1);
        assertThat(listings.list("downloads",0,20,List.of(),"DOWNLOAD","",null,null,"selectedBooks,desc").items().getFirst()).containsEntry("jobId","normal");
        assertThat(listings.list("downloads",0,20,List.of(),"CLEANUP","other",null,null,"createdAt,desc").items()).isEmpty();
        assertThat(listings.list("downloads",0,20,List.of(),"CLEANUP","",Instant.now().plusSeconds(10),null,"createdAt,desc").items()).isEmpty();
    }
    void normalJob(String id,String type,long created) {
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state,created_at,event_actor_username) VALUES(?,1,1,0,1,?,'COMPLETED',?,'Owner')",id,type,created);
    }
    @Test void navigationUsesSameFilteredOrderAcrossNormalAndCleanupJobs() {
        var job=start(preview("downloads","RECORD"));normalJob("older","DOWNLOAD",job.createdAt()-1000);normalJob("newer","UPDATE",job.createdAt()+1000);
        var neighbors=listings.neighbors("downloads",job.id(),List.of(),"","",null,null,"createdAt,asc");
        assertThat(neighbors.previous()).isEqualTo(new WorkListings.Neighbor("older",false,0));assertThat(neighbors.next()).isEqualTo(new WorkListings.Neighbor("newer",false,2));
        assertThat(listings.neighbors("downloads","older",List.of(),"","",null,null,"createdAt,asc").next()).isEqualTo(new WorkListings.Neighbor(job.id(),true,1));
        assertThat(listings.neighbors("downloads","older",List.of(),"","",null,null,"createdAt,asc").previous()).isNull();
        assertThat(listings.neighbors("downloads","newer",List.of(),"","",null,null,"createdAt,asc").next()).isNull();
        var reversed=listings.neighbors("downloads",job.id(),List.of(),"","",null,null,"createdAt,desc");assertThat(reversed.previous().id()).isEqualTo("newer");assertThat(reversed.next().id()).isEqualTo("older");
        var onlyCleanup=listings.neighbors("downloads",job.id(),List.of("QUEUED"),"CLEANUP","own",null,null,"createdAt,desc");assertThat(onlyCleanup.previous()).isNull();assertThat(onlyCleanup.next()).isNull();
        assertThat(listings.neighbors("downloads",job.id(),List.of(),"UPDATE","",null,null,"createdAt,desc")).isEqualTo(new WorkListings.Neighbors(null,null));
        assertThat(listings.neighbors("downloads",job.id(),List.of(),"","",Instant.ofEpochMilli(job.createdAt()+1),null,"createdAt,desc")).isEqualTo(new WorkListings.Neighbors(null,null));
    }
    @Test void navigationKeepsStableTiesAndDoesNotExposeJobsOutsidePermissions() {
        var job=start(preview("downloads","RECORD"));normalJob("normal-a","DOWNLOAD",job.createdAt());normalJob("normal-b","UPDATE",job.createdAt());
        var list=listings.list("downloads",0,20,List.of(),"","",null,null,"createdAt,desc").items();
        for(int n=0;n<list.size();n++) {
            String current=(String)list.get(n).get("jobId");var adjacent=listings.neighbors("downloads",current,List.of(),"","",null,null,"createdAt,desc");
            if(n==0) assertThat(adjacent.previous()).isNull();else assertThat(adjacent.previous().id()).isEqualTo(list.get(n-1).get("jobId"));
            if(n+1==list.size()) assertThat(adjacent.next()).isNull();else assertThat(adjacent.next().id()).isEqualTo(list.get(n+1).get("jobId"));
        }
        var restricted=new Account(owner.id(),owner.username(),owner.email(),"USER","ACTIVE",false,null,0,Set.of(Permission.TORRENT_SYNC,Permission.DOWNLOADS_DELETE),Instant.now());login(restricted);
        assertThat(listings.neighbors("downloads",job.id(),List.of(),"","",null,null,"createdAt,desc")).isEqualTo(new WorkListings.Neighbors(null,null));
        assertThat(listings.neighbors("downloads","normal-a",List.of(),"","",null,null,"createdAt,desc")).isEqualTo(new WorkListings.Neighbors(null,null));
    }
    @Test void navigationEndpointEnforcesPermissionsAndValidatesOrdering() throws Exception {
        var job=start(preview("downloads","RECORD"));normalJob("older","DOWNLOAD",job.createdAt()-1000);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/settings/work/downloads/"+job.id()+"/neighbors?sort=createdAt,asc").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.previous.id").value("older"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/settings/work/downloads/"+job.id()+"/neighbors?sort=secret,asc").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var restricted=new UsernamePasswordAuthenticationToken(owner,null,List.of(new SimpleGrantedAuthority("CATALOG_READ")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/settings/work/downloads/"+job.id()+"/neighbors").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(restricted)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Test void calibreNavigationIncludesItsCleanupButNotDownloadJobs() {
        var job=start(preview("calibre","BOOK"));
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('calibre-old',?,'now','Owner','target','main','source','COMPLETED')",Instant.ofEpochMilli(job.createdAt()-1000).toString());
        normalJob("torrent-job","UPDATE",job.createdAt()-500);
        assertThat(listings.neighbors("calibre",job.id(),List.of(),"","",null,null,"createdAt,asc").previous()).isEqualTo(new WorkListings.Neighbor("calibre-old",false,0));
        assertThat(listings.neighbors("calibre","calibre-old",List.of(),"","",null,null,"createdAt,asc").next()).isEqualTo(new WorkListings.Neighbor(job.id(),true,1));
    }
    @Test void calibreListingCombinesTransfersAndCleanupBeforePagination() {
        var cleanupJob=start(preview("calibre","BOOK"));
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('normal',?,'now','Owner','destination','main','source','COMPLETED')",Instant.now().minusSeconds(100).toString());
        var mixed=listings.list("calibre",0,20,List.of(),"","",null,null,"createdAt,asc");
        assertThat(mixed.items()).hasSize(2);assertThat(mixed.items().getFirst()).containsEntry("type","SEND");assertThat(mixed.items().getLast()).containsEntry("type","CLEANUP").containsEntry("id",cleanupJob.id());
        assertThat(listings.list("calibre",0,20,List.of(),"CLEANUP","",null,null,"library,asc").items()).hasSize(1);
    }
    @Test void listingDatesRespectInclusiveStartAndExclusiveEnd() {
        var from=Instant.parse("2026-10-15T22:00:00Z");
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('boundary',?,'now','Owner','target','main','source','COMPLETED')",from.toString());
        assertThat(listings.list("calibre",0,20,List.of(),"SEND","",from,from.plusSeconds(1),"createdAt,asc").items()).hasSize(1);
        assertThat(listings.list("calibre",0,20,List.of(),"SEND","",from.minusSeconds(1),from,"createdAt,asc").items()).isEmpty();
    }
    @Test void cleanupListingsDoNotExposeOtherOwnersOrGrantAccessToRegularJobs() {
        start(preview("downloads","RECORD"));
        var restricted=new Account(owner.id(),owner.username(),owner.email(),"USER","ACTIVE",false,null,0,Set.of(Permission.TORRENT_SYNC,Permission.DOWNLOADS_DELETE),Instant.now());login(restricted);
        assertThat(listings.list("downloads",0,20,List.of(),"","",null,null,"createdAt,desc").items()).hasSize(1);
        login(new Account("other","Other","other@example.com","ADMIN","ACTIVE",false,null,0,owner.permissions(),Instant.now()));
        assertThat(listings.list("downloads",0,20,List.of(),"CLEANUP","",null,null,"createdAt,desc").items()).isEmpty();
    }
    @Test void jobsArePrivateAndPermissionsAreCheckedAgain() {var job=start(preview("downloads","RECORD"));var other=new Account("other","Other","other@example.com","ADMIN","ACTIVE",false,null,0,owner.permissions(),Instant.now());login(other);assertThatThrownBy(()->cleanup.get(job.id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);login(owner);jdbc.update("UPDATE users SET must_change_password=1 WHERE id=?",owner.id());cleanup.cycle();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);assertThat(downloads.findById(old)).isPresent();}
    @Test void calibreKeepsRevisionThatChangesDuringFinalChecks() {
        var job=start(preview("calibre","BOOK"));
        when(client.book(any(),eq(10L))).thenReturn(book(10,1),book(10,3));
        cleanup.cycle();
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
        assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @Test void calibreStopsIfRetainedEpubDisappearsDuringFinalChecks() {
        var job=start(preview("calibre","BOOK"));var reads=new java.util.concurrent.atomic.AtomicInteger();
        when(client.book(any(),eq(10L))).thenAnswer(call->{if(reads.incrementAndGet()==2) when(client.hasEpub(any(),eq(20L))).thenReturn(false);return book(10,1);});
        cleanup.cycle();
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
        assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }

    RevisionCleanup.Job startAll(RevisionCleanup.Job job) {
        return cleanup.start(job.id(),new RevisionCleanup.Confirmation(cleanup.items(job.id(),0,1000).items().stream().filter(i->i.state().equals("READY")).map(i->i.id()).toList(),true,true));
    }
    @Test void torrentCleanupOfSeveralOlderRevisionsNeverDeletesTheRetainedRevision() {
        String latest=download(1,3,"C");remote.add(remote("C","/downloads/latest.epub"));
        var preview=preview("downloads","FILES");
        assertThat(cleanup.items(preview.id(),0,1000).items()).allSatisfy(i->{assertThat(i.oldId()).isNotEqualTo(latest);assertThat(i.replacementId()).isEqualTo(latest);});
        var job=startAll(preview);cleanup.cycle();
        assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(2);verify(adapter).deleteTorrent("A".repeat(40),true);verify(adapter).deleteTorrent("B".repeat(40),true);verify(adapter,never()).deleteTorrent(eq("C".repeat(40)),anyBoolean());
        assertThat(downloads.findById(latest)).isPresent();assertThat(remote).extracting(r->r.hash()).containsExactly("C".repeat(40));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BOOK"})
    void calibreCleanupOfSeveralOlderRevisionsNeverDeletesTheRetainedRevision(String action) {
        calibre(30,3);when(client.book(any(),eq(30L))).thenReturn(book(30,3));
        doAnswer(call->{long id=call.getArgument(1);if(action.equals("BOOK")) when(client.book(any(),eq(id))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));else when(client.hasEpub(any(),eq(id))).thenReturn(false);return null;}).when(client).remove(any(),anyLong(),anyBoolean());
        var preview=preview("calibre",action);
        assertThat(cleanup.items(preview.id(),0,1000).items()).allSatisfy(i->{assertThat(i.oldId()).isNotEqualTo("30");assertThat(i.replacementId()).isEqualTo("30");});
        var job=startAll(preview);cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(2);
        verify(client).remove(any(),eq(10L),eq(action.equals("BOOK")));verify(client).remove(any(),eq(20L),eq(action.equals("BOOK")));verify(client,never()).remove(any(),eq(30L),anyBoolean());
        assertThat(client.hasEpub(settings.connection(),30)).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE calibre_id=30",Integer.class)).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"RECORD","TORRENT","FILES"})
    void incompleteReplacementAfterConfirmationPreventsEveryTorrentAction(String action) {
        var job=start(preview("downloads",action));remote.set(1,new RemoteTorrent("B".repeat(40),DownloadStatus.DOWNLOADING,null,Set.of(),"/downloads/new.epub"));cleanup.cycle();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(downloads.findById(old)).isPresent();assertThat(downloads.findById(newer)).isPresent();assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @Test void retainedTorrentEpubBecomingUnreadableAfterConfirmationPreventsDeletion() {
        var job=start(preview("downloads","FILES"));when(files.list(newer)).thenThrow(new IllegalStateException("unreadable"));cleanup.cycle();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);assertThat(downloads.findById(old)).isPresent();
    }
    @Test void torrentAliasesThatNowIdentifyTheSameRemoteTorrentPreventDeletion() {
        var job=start(preview("downloads","FILES"));remote.clear();remote.add(new RemoteTorrent("A".repeat(40),DownloadStatus.DOWNLOADED,Instant.now(),Set.of("B".repeat(40)),"/downloads/new.epub"));cleanup.cycle();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void parentAndChildPathsCannotDeleteRetainedFiles(boolean oldIsParent) {
        var job=start(preview("downloads","FILES"));remote.set(0,remote("A",oldIsParent?"/downloads/shared":"/downloads/shared/old.epub"));remote.set(1,remote("B",oldIsParent?"/downloads/shared/new.epub":"/downloads/shared"));cleanup.cycle();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BOOK"})
    void missingRetainedCalibreBookAfterConfirmationPreventsDeletion(String action) {
        var job=start(preview("calibre",action));when(client.book(any(),eq(20L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));cleanup.cycle();
        verify(client,never()).remove(any(),anyLong(),anyBoolean());assertThat(cleanup.get(job.id()).failed()+cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BOOK"})
    void lostRetainedCalibreEpubAfterConfirmationPreventsDeletion(String action) {
        var job=start(preview("calibre",action));when(client.hasEpub(any(),eq(20L))).thenReturn(false);cleanup.cycle();
        verify(client,never()).remove(any(),anyLong(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @Test void changedOldTorrentIdentityAfterConfirmationPreventsDeletion() {
        var job=start(preview("downloads","FILES"));jdbc.update("UPDATE torrent_downloads SET revision=3 WHERE id=?",old);cleanup.cycle();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @Test void changedCalibreConnectionAfterConfirmationPreventsDeletion() {
        var job=start(preview("calibre","BOOK"));settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"other"));cleanup.cycle();
        verify(client,never()).remove(any(),anyLong(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }
    @Test void unconfirmedFirstRemovalBlocksOtherRevisionsOfTheSameBook() {
        String latest=download(1,3,"C");remote.add(remote("C","/downloads/latest.epub"));doNothing().when(adapter).deleteTorrent(anyString(),anyBoolean());
        var job=startAll(preview("downloads","FILES"));cleanup.cycle();
        assertThat(cleanup.get(job.id()).uncertain()).isEqualTo(1);assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
        verify(adapter,times(1)).deleteTorrent(eq("A".repeat(40)),anyBoolean());verify(adapter,never()).deleteTorrent(eq("B".repeat(40)),anyBoolean());verify(adapter,never()).deleteTorrent(eq("C".repeat(40)),anyBoolean());
        assertThat(downloads.findById(latest)).isPresent();
    }
    @Test void catalogueSharedTorrentCannotBeDeletedEvenIfItPassedPreview() {
        var job=start(preview("downloads","FILES"));jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author,links) VALUES(2,1,'Shared','Author',?)","A".repeat(40));cleanup.cycle();
        verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }

    @Test void retainedDownloadIdCannotBeSubmittedInsteadOfAnOlderPreviewItem() {
        var job=preview("downloads","FILES");
        assertThatThrownBy(()->cleanup.start(job.id(),new RevisionCleanup.Confirmation(List.of(newer),true,true))).hasMessageContaining("selección");
        cleanup.cycle();verify(adapter,never()).deleteTorrent(anyString(),anyBoolean());assertThat(cleanup.get(job.id()).state()).isEqualTo("PREVIEW");
    }
    @Test void sameRevisionCalibreDuplicatesAreNeverCleanupTargets() {
        calibre(30,2);when(client.book(any(),eq(30L))).thenReturn(book(30,2));
        var job=preview("calibre","BOOK");var rows=cleanup.items(job.id(),0,1000).items();
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().oldId()).isEqualTo("10");assertThat(rows.getFirst().replacementId()).isEqualTo("20");
    }
    @Test void unknownCalibreIdentifiersCannotCreateDeletionCandidates() {
        jdbc.update("UPDATE calibre_books SET epl_id=NULL WHERE calibre_id=10");
        assertThat(preview("calibre","BOOK").total()).isZero();verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void retainedCalibreIdentityChangingDuringFinalCheckStopsDeletion() {
        var job=start(preview("calibre","BOOK"));
        when(client.book(any(),eq(20L))).thenReturn(book(20,2),new CalibreClient.Book(20,"Other",List.of(),2L,BigDecimal.TWO,true));
        cleanup.cycle();verify(client,never()).remove(any(),anyLong(),anyBoolean());assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);
    }

    @Test void oldEpubOnlyPreviewCannotBeConfirmedAsCompleteDeletion() {
        var job=preview("calibre","BOOK");jdbc.update("UPDATE revision_cleanup_jobs SET action='EPUB' WHERE id=?",job.id());
        assertThatThrownBy(()->start(job)).hasMessageContaining("entradas completas");cleanup.cycle();verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void queuedLegacyEpubItemsAreBlockedWithoutExpandingDeletion() {
        var job=start(preview("calibre","BOOK"));jdbc.update("UPDATE revision_cleanup_jobs SET action='EPUB' WHERE id=?",job.id());cleanup.cycle();
        assertThat(cleanup.get(job.id()).blocked()).isEqualTo(1);verify(client,never()).remove(any(),anyLong(),anyBoolean());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE calibre_id=10",Integer.class)).isEqualTo(1);
    }
    @Test void legacyEpubUncertaintyCanBeVerifiedWithoutDeletingTheEntry() {
        var job=start(preview("calibre","BOOK"));jdbc.update("UPDATE revision_cleanup_jobs SET action='EPUB',state='COMPLETED' WHERE id=?",job.id());
        jdbc.update("UPDATE revision_cleanup_items SET state='UNCERTAIN',dispatched=1 WHERE job_id=?",job.id());when(client.hasEpub(any(),eq(10L))).thenReturn(false);
        cleanup.control(job.id(),"verify");cleanup.cycle();assertThat(cleanup.get(job.id()).succeeded()).isEqualTo(1);
        verify(client,never()).remove(any(),anyLong(),anyBoolean());assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE calibre_id=10",Integer.class)).isEqualTo(1);
    }
    @Test void interruptedLegacyEpubCleanupCannotBeContinuedButCanBeCancelled() {
        var job=start(preview("calibre","BOOK"));jdbc.update("UPDATE revision_cleanup_jobs SET action='EPUB',state='PAUSED' WHERE id=?",job.id());
        assertThatThrownBy(()->cleanup.control(job.id(),"continue")).hasMessageContaining("entradas completas");cleanup.control(job.id(),"cancel");cleanup.cycle();
        assertThat(cleanup.get(job.id()).state()).isEqualTo("CANCELLED");verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }

}
