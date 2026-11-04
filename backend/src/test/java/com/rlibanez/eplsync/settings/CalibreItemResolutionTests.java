package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.tasks.worker-enabled=false","eplsync.torrent.enabled=true","eplsync.torrent.qbittorrent.auth.api-key=fixture-key","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@WithMockUser(authorities={"CALIBRE_READ","CALIBRE_SEND","CALIBRE_JOBS_MANAGE","DOWNLOADS_FILES_READ","TORRENT_SYNC"})
class CalibreItemResolutionTests {
    @Autowired CalibreItemResolution resolution;
    @Autowired CalibreTransfers transfers;
    @Autowired JdbcTemplate jdbc;
    @Autowired CalibreSettings settings;
    @Autowired AccountStore accounts;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @Autowired TorrentProperties torrent;
    @Autowired JobRetryHistory history;
    @MockitoBean EpubFiles files;
    @MockitoBean CalibreClient client;
    Account owner;byte[] epub;
    <T> T actor(Supplier<T> work) {return EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),work);}
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM job_retry_attempts");jdbc.update("DELETE FROM calibre_jobs");downloads.deleteAll();accounts.clear();
        owner=accounts.initialize("Owner","owner@example.org","password123","password123");
        settings.save(new CalibreSettings.Input("http://calibre.test","","",true,"main"));
        String source=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((tracking.instanceId()+"\n"+torrent.getFiles().getRemoteRoot()+"\n"+torrent.getFiles().getLocalRoot()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES('job','now','now',?,'Owner',?,'main',?,'COMPLETED')",owner.id(),CalibreTransfers.destination(settings.connection()),source);
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,name,epl_id,revision,state,wait_until,message) VALUES('item','job',0,'record','Book',1,'1.1','FAILED',9999999999999,'La descarga contiene varios EPUB')");
        var record=new DownloadRecord();record.setId("record");record.setEplId(1L);record.setRevision(1.1);record.setHash("A".repeat(40));record.setClient("qbittorrent");record.setClientInstanceId(tracking.instanceId());record.setStatus(DownloadStatus.DOWNLOADED);record.setOrigin(DownloadRecord.Origin.EPLSYNC);record.setCreatedAt(Instant.now());downloads.save(record);
        epub=CalibreTransfersTests.epub(1,"1.1");
        when(files.list("record")).thenReturn(List.of(new EpubFiles.Item(0,"one.epub",epub.length),new EpubFiles.Item(1,"two.epub",epub.length)));
        when(files.download("record",1)).thenAnswer(c->new EpubFiles.Opened("two.epub",epub.length,new java.io.ByteArrayInputStream(epub)));
        when(client.findRevision(any(),anyLong(),any())).thenReturn(null);
        when(client.findBookRevision(any(),anyLong(),any())).thenReturn(null);
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenReturn(9L);
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true));
        when(client.hasEpub(any(),anyLong())).thenReturn(true);
    }
    CalibreItemResolution.Preview preview() {return actor(()->resolution.preview("job","item"));}
    CalibreItemResolution.Input retry(String token) {return new CalibreItemResolution.Input(token,"retry","record",1,"two.epub",(long)epub.length,null,"",true);}
    CalibreItemResolution.Input omit(String token) {return new CalibreItemResolution.Input(token,"omit",null,null,null,null,CalibreItemResolution.Reason.MANUAL,"Lo he revisado",true);}
    CalibreTransfers.Job apply(CalibreItemResolution.Input input) {return actor(()->resolution.apply("job","item",input));}
    @Test void previewsDoNotChangeTheJobOrAutomaticallyChooseAnEpub() {
        var p=preview();assertThat(p.files()).hasSize(2);assertThat(p.problem()).isNull();
        assertThat(transfers.job("job").failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_retry_attempts",Long.class)).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void aSelectedEpubRunsInTheOriginalJobOnceAndKeepsItsFailureHistory() {
        var p=preview();var request=retry(p.token());
        assertThat(apply(request).id()).isEqualTo("job");
        assertThatThrownBy(()->apply(request)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        transfers.cycle();transfers.recoverInterrupted();transfers.cycle();
        var job=transfers.job("job");assertThat(job.accepted()).isEqualTo(1);assertThat(job.omitted()).isZero();assertThat(job.state()).isEqualTo("COMPLETED");
        verify(client,times(1)).uploadEpub(any(),any(),eq("two.epub"),eq("item"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_retry_attempts",Long.class)).isEqualTo(1);
        String attempt=jdbc.queryForObject("SELECT id FROM job_retry_attempts",String.class);
        assertThat(history.details("calibre","job",attempt,0,20).items().getFirst().beforeMessage()).contains("varios EPUB");
    }
    @Test void omissionIsDistinctFromSendingOrAnExistingCopyAndNeverRunsCleanup() {
        jdbc.update("UPDATE calibre_job_items SET cleanup_policy='BOOK',cleanup_ids='[7]' WHERE id='item'");
        var p=preview();clearInvocations(files,client);
        var job=apply(omit(p.token()));
        assertThat(job.omitted()).isEqualTo(1);assertThat(job.accepted()).isZero();assertThat(job.skipped()).isZero();assertThat(job.failed()).isZero();assertThat(job.state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_job_items",String.class)).isEqualTo("OMITTED");
        assertThat(jdbc.queryForObject("SELECT cleanup_policy FROM calibre_job_items",String.class)).isEqualTo("KEEP");
        assertThat(jdbc.queryForObject("SELECT cleanup_ids FROM calibre_job_items",String.class)).isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT message FROM calibre_job_items",String.class)).contains("Lo gestionaré manualmente","Lo he revisado");
        assertThat(jdbc.queryForObject("SELECT username FROM job_retry_attempts",String.class)).isEqualTo("Owner");
        transfers.recoverInterrupted();transfers.cycle();
        verifyNoInteractions(files,client);
        assertThat(downloads.count()).isEqualTo(1);
    }
    @Test void omissionDoesNotFinishOrResumeOtherPendingWork() {
        jdbc.update("UPDATE calibre_jobs SET state='PAUSED'");
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,name,epl_id,revision,state,wait_until) VALUES('other','job',1,'Other',2,'1','WAITING_DOWNLOAD',9999999999999)");
        var job=apply(omit(preview().token()));
        assertThat(job.state()).isEqualTo("PAUSED");assertThat(job.pending()).isEqualTo(1);assertThat(job.omitted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_job_items WHERE id='other'",String.class)).isEqualTo("WAITING_DOWNLOAD");
    }
    @Test void aChangedFileOrForgedSelectionCannotBeQueued() {
        var p=preview();
        doReturn(List.of(new EpubFiles.Item(1,"changed.epub",epub.length+1))).when(files).list("record");
        assertThatThrownBy(()->apply(retry(p.token()))).hasMessageContaining("ha cambiado");
        assertThat(transfers.job("job").failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_retry_attempts",Long.class)).isZero();
    }
    @Test void theWorkerStillRejectsAnEpubWithADifferentEplId() throws Exception {
        var p=preview();apply(retry(p.token()));
        byte[] wrong=CalibreTransfersTests.epub(2,"1.1");
        when(files.download("record",1)).thenAnswer(c->new EpubFiles.Opened("two.epub",epub.length,new java.io.ByteArrayInputStream(wrong)));
        transfers.cycle();assertThat(transfers.job("job").accepted()).isZero();assertThat(transfers.job("job").failed()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void uncertainAndSuccessfulItemsCannotBeOmittedOrRetriedByThisAction() {
        jdbc.update("UPDATE calibre_job_items SET file_index=0,size=?",epub.length);
        for(String state:List.of("UNCERTAIN","SENDING","ACCEPTED","SKIPPED","CLEANUP_PENDING","OMITTED","CANCELLED")) {
            jdbc.update("UPDATE calibre_job_items SET state=?",state);
            assertThatThrownBy(this::preview).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        }
        jdbc.update("UPDATE calibre_job_items SET state='FAILED',calibre_id=9");
        assertThatThrownBy(this::preview).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    @Test void rarAndPdfCanBeOmittedWithoutReadingOrExtractingThem() {
        doThrow(new EpubFiles.UnsupportedContent("La descarga contiene archivos RAR, PDF, pero ningún EPUB disponible")).when(files).list("record");
        var p=preview();assertThat(p.files()).isEmpty();assertThat(p.problem()).contains("RAR","PDF");
        clearInvocations(files,client);apply(omit(p.token()));verifyNoInteractions(files,client);
    }
    @Test @WithMockUser(authorities={"CALIBRE_READ"}) void readOnlyUsersCannotResolveItems() {
        assertThatThrownBy(this::preview).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->apply(omit("a".repeat(64)))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void concurrentOmissionRequestsRecordOnlyOneDecision() throws Exception {
        var p=preview();var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);var work=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int n=0;n<2;n++) work.add(executor.submit(()-> {
                org.springframework.security.core.context.SecurityContextHolder.setContext(security);
                try {start.await();apply(omit(p.token()));return true;}
                catch(com.rlibanez.eplsync.exception.UserInputException ex) {return false;}
                finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            }));
            start.countDown();int accepted=0;for(var f:work) if(f.get(10,java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_retry_attempts",Long.class)).isEqualTo(1);
    }
    @Test void selectionIsBoundToTheJobAndCurrentConfiguration() {
        var p=preview();settings.save(new CalibreSettings.Input("http://other-calibre.test","","",true,"main"));
        assertThatThrownBy(()->apply(retry(p.token()))).hasMessageContaining("conexión original");
        // Omitting a failed book does not contact either destination.
        assertThat(apply(omit(p.token())).omitted()).isEqualTo(1);
    }
    @Test void omissionRequiresAnExplicitReasonAndConfirmation() {
        var p=preview();
        assertThatThrownBy(()->apply(new CalibreItemResolution.Input(p.token(),"omit",null,null,null,null,null,"",true))).hasMessageContaining("motivo");
        assertThatThrownBy(()->apply(new CalibreItemResolution.Input(p.token(),"omit",null,null,null,null,CalibreItemResolution.Reason.OTHER,"",false))).hasMessageContaining("Confirma");
        assertThat(transfers.job("job").omitted()).isZero();
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Test void decisionsAreLoggedOnlyAfterCommitAndNotOnReplayOrRollback() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CalibreItemResolution.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            var p=preview();var request=omit(p.token());
            assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
                apply(request);
                assertThat(appender.list).isEmpty();
                throw new IllegalStateException("fixture rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(appender.list).isEmpty();
            apply(request);
            assertThatThrownBy(()->apply(request)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("acción=OMITIR","usuario=Owner","trabajo=job","elemento=item","EPL ID=1","motivo=MANUAL").doesNotContain("Lo he revisado");
            jdbc.update("UPDATE calibre_job_items SET state='FAILED' WHERE id='item'");
            apply(retry(preview().token()));
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getLast().getFormattedMessage()).contains("acción=RESOLVER","motivo=EPUB_SELECTED");
        } finally {logger.detachAppender(appender);appender.stop();}
    }

}
