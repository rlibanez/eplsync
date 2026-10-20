package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.service.TorrentClientService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.tasks.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CalibrePreparationOperationsTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("preparation.db"));}
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    @Autowired CalibreSettings settings;
    @Autowired CatalogCalibreTransfers catalog;
    @Autowired CalibreTransfers transfers;
    @Autowired com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService tracking;
    @Autowired CalibrePreparationOperations operations;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    @MockitoBean TorrentClientService torrentClient;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM calibre_preparation_operations");jdbc.update("DELETE FROM calibre_job_items");jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM calibre_update_requests");jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");jdbc.update("DELETE FROM catalog_books");jdbc.update("DELETE FROM torrent_downloads");accounts.clear();
        var owner=accounts.initialize("Owner","owner@example.com","password123","password123");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,owner.permissions().stream().map(p->new SimpleGrantedAuthority(p.name())).toList()));
        settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"main"));
        when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main"));
        when(torrentClient.withDefaults(any())).thenAnswer(call->call.getArgument(0));
    }
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    void seed(int count) {
        for(int start=1;start<=count;start+=500) {var rows=new ArrayList<Object[]>();for(int id=start;id<Math.min(start+500,count+1);id++)rows.add(new Object[]{id,1.0,"Book "+id,String.format("%040X",id==1001?1:id)});jdbc.batchUpdate("INSERT INTO catalog_books(epl_id,revision,title,links,author) VALUES(?,?,?,?,'Author')",rows);}
    }
    CatalogCalibreTransfers.Request request(String id,int count) {var books=java.util.stream.LongStream.rangeClosed(1,count).mapToObj(n->new CatalogCalibreTransfers.Book(n,1.0,"Book "+n)).toList();return new CatalogCalibreTransfers.Request(id,"main",books,List.of(),true,java.util.stream.LongStream.rangeClosed(1,count).boxed().toList());}
    @Test void batchesRemainInactiveAndResumeWithoutDuplicatingJobsOrItems() {
        seed(1501);var request=request("resume-batches",1501);assertThat(operations.send(request).state()).isEqualTo("QUEUED");operations.cycle();
        assertThat(operations.status(request.id()).processed()).isEqualTo(500);
        String calibre=jdbc.queryForObject("SELECT id FROM calibre_jobs",String.class),torrent=jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class);
        assertThat(transfers.job(calibre).state()).isEqualTo("PAUSED");
        assertThatThrownBy(()->transfers.control(calibre,"resume")).hasMessageContaining("preparando");assertThatThrownBy(()->bulk.control(torrent,"resume")).hasMessageContaining("preparando");
        operations.recover();operations.send(request);for(int i=0;i<10 && !operations.status(request.id()).state().equals("COMPLETED");i++)operations.cycle();
        assertThat(operations.status(request.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isEqualTo(1501);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items",Integer.class)).isEqualTo(1501);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT position) FROM calibre_job_items",Integer.class)).isEqualTo(1501);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE state='SKIPPED'",Integer.class)).isEqualTo(1);
        assertThat(transfers.job(calibre).state()).isEqualTo("QUEUED");assertThat(catalog.create(request).id()).isEqualTo(calibre);operations.send(request);operations.cycle();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());verify(files,never()).list(anyString());
    }
    @Test void previewRespectsExclusionsAndCreatesNoJobs() {
        seed(1201);var filter=new CatalogBookFilter();filter.setExcludedIds(new Long[]{1201L});operations.prepare(new CalibrePreparationOperations.PreviewRequest("preview",new CatalogCalibreTransfers.Selection(filter)));operations.cycle();
        assertThat(operations.status("preview").state()).isEqualTo("COMPLETED");assertThat(operations.status("preview").total()).isEqualTo(1200);assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isZero();
    }
    @Test void changedConnectionCancelsStagedJobsBeforeAnyDispatch() {
        seed(1001);var request=request("changed-connection",1001);operations.send(request);operations.cycle();settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"other"));operations.cycle();
        assertThat(operations.status(request.id()).state()).isEqualTo("FAILED");assertThat(jdbc.queryForObject("SELECT state FROM calibre_jobs",String.class)).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs",String.class)).isEqualTo("CANCELLED");
    }
    @Test void sixtyFiveThousandBooksReleaseTheWriterBetweenBatchesAndAllowConcurrentReaders() throws Exception {
        seed(65000);var request=request("large-operation",65000);operations.send(request);
        var running=new java.util.concurrent.atomic.AtomicBoolean(true);var reads=new java.util.concurrent.atomic.AtomicInteger();
        try(var readers=java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<3;n++) futures.add(readers.submit(()->{while(running.get()){jdbc.queryForObject("SELECT count(*) FROM catalog_books",Long.class);reads.incrementAndGet();try{Thread.sleep(5);}catch(InterruptedException ex){Thread.currentThread().interrupt();return;}}}));
            try {
                long previous=0;
                for(int batch=0;batch<135 && !operations.status(request.id()).state().equals("COMPLETED");batch++) {
                    operations.cycle();var progress=operations.status(request.id());assertThat(progress.processed()).isGreaterThanOrEqualTo(previous);previous=progress.processed();
                    // An unrelated committed write must fit between any two creation batches.
                    jdbc.update("INSERT INTO app_settings VALUES('preparation-reader-check',?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value",String.valueOf(batch));
                    if(batch==63) operations.recover();
                }
            } finally {running.set(false);}
            for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(reads.get()).isGreaterThan(10);assertThat(operations.status(request.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Long.class)).isEqualTo(65000);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items",Long.class)).isEqualTo(65000);
        assertThat(jdbc.queryForObject("SELECT selected_books FROM torrent_bulk_jobs",Long.class)).isEqualTo(65000);assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        operations.send(request);operations.cycle();assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Long.class)).isEqualTo(65000);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        String downloadJob=jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class);bulk.control(downloadJob,"cancel");
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_jobs",String.class)).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE state='CANCELLED'",Long.class)).isEqualTo(65000);
    }
    @Test void temporaryContentionKeepsItsCursorAndDoesNotRecreateConfirmedBatches() {
        seed(1001);var request=request("contention",1001);operations.send(request);operations.cycle();
        when(client.check(any())).thenThrow(new com.rlibanez.eplsync.ordering.SqliteContention.WriterBusy());operations.cycle();
        assertThat(operations.status(request.id()).state()).isEqualTo("QUEUED");assertThat(operations.status(request.id()).processed()).isEqualTo(500);
        doReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main")).when(client).check(any());
        for(int n=0;n<5 && !operations.status(request.id()).state().equals("COMPLETED");n++)operations.cycle();
        assertThat(operations.status(request.id()).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isEqualTo(1001);
    }
    @Test void anotherAccountCannotReadOrReuseThePreparation() {
        seed(1);var request=request("private-operation",1);operations.send(request);
        var owner=(Account)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var other=new Account("someone-else","Other","","ADMIN","ACTIVE",false,null,0,owner.permissions(),null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other,null,other.permissions().stream().map(p->new SimpleGrantedAuthority(p.name())).toList()));
        assertThatThrownBy(()->operations.status(request.id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->operations.cancel(request.id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->operations.send(request)).hasMessageContaining("otra selección");
    }

    @Test void downloadedBooksAreStagedWithoutCreatingAnotherTorrentJob() {
        seed(1501);String instance;
        // Obtain the same client identity used by the production selection resolver.
        instance=tracking.instanceId();var rows=new ArrayList<Object[]>();for(long n=1;n<=1501;n++)rows.add(new Object[]{"available-"+n,n,String.format("%040X",n),instance});
        jdbc.batchUpdate("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,1,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0)",rows);
        var original=request("already-downloaded",1501);var request=new CatalogCalibreTransfers.Request(original.id(),original.library(),original.books(),List.of(),false,original.pending());operations.send(request);
        for(int n=0;n<6 && !operations.status(request.id()).state().equals("COMPLETED");n++)operations.cycle();
        assertThat(operations.status(request.id()).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isEqualTo(1501);verify(files,never()).list(anyString());
    }
    @Test void revokedPermissionsPreventActivationOfPartlyPreparedJobs() {
        seed(1001);var request=request("revoked",1001);operations.send(request);operations.cycle();
        var owner=(Account)SecurityContextHolder.getContext().getAuthentication().getPrincipal();jdbc.update("UPDATE users SET role='USER' WHERE id=?",owner.id());
        for(var permission:owner.permissions()) if(permission!=Permission.TORRENT_SEND) jdbc.update("INSERT INTO user_permission_overrides(user_id,permission,effect) VALUES(?,?,'ALLOW')",owner.id(),permission.name());
        operations.cycle();assertThat(operations.status(request.id()).state()).isEqualTo("FAILED");assertThat(jdbc.queryForObject("SELECT state FROM calibre_jobs",String.class)).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs",String.class)).isEqualTo("CANCELLED");
    }

    @Test void cancellationDiscardsStagedJobsAndIsIdempotentAcrossRecovery() {
        seed(1501);var request=request("cancel-staged",1501);operations.send(request);operations.cycle();
        assertThat(operations.cancel(request.id()).state()).isEqualTo("CANCELLED");operations.recover();operations.cycle();operations.cancel(request.id());
        assertThat(operations.send(request).state()).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());verify(torrentClient,never()).addTorrent(any(),any());
    }
    @Test void cancellationRefusesToDeleteJobsWithRemoteAttempts() {
        seed(1501);var request=request("cancel-attempted",1501);operations.send(request);operations.cycle();
        jdbc.update("UPDATE torrent_bulk_items SET attempts=1 WHERE id=(SELECT id FROM torrent_bulk_items LIMIT 1)");
        assertThatThrownBy(()->operations.cancel(request.id())).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(operations.status(request.id()).state()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(1);
    }

    @Test void cancellationProtectsUnexpectedSharedDependencies() {
        seed(1501);var request=request("cancel-shared-guard",1501);operations.send(request);operations.cycle();
        String staged=jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class);
        var separate=catalog.create(request("separate-shared-guard",1));
        jdbc.update("UPDATE calibre_job_items SET download_job_id=? WHERE job_id=?",staged,separate.id());
        assertThatThrownBy(()->operations.cancel(request.id())).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(operations.status(request.id()).state()).isEqualTo("RUNNING");
        assertThat(transfers.job(separate.id()).state()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs WHERE id=?",Integer.class,staged)).isEqualTo(1);
    }

    @Test void failedDeletionRollsBackBothJobsAndCanBeRetried() {
        seed(1501);var request=request("cancel-delete-failure",1501);operations.send(request);operations.cycle();
        jdbc.execute("CREATE TRIGGER prevent_preparation_delete BEFORE DELETE ON torrent_bulk_jobs BEGIN SELECT RAISE(ABORT, 'test deletion failure'); END");
        try {
            assertThatThrownBy(()->operations.cancel(request.id())).isInstanceOf(RuntimeException.class);
            assertThat(operations.status(request.id()).state()).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isEqualTo(500);
        } finally {jdbc.execute("DROP TRIGGER prevent_preparation_delete");}
        operations.cancel(request.id());assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isZero();
    }

    @Test void cancellationDuringRemoteCheckRollsBackTheNextBatch() {
        seed(1501);var request=request("cancel-race",1501);operations.send(request);operations.cycle();
        when(client.check(any())).thenAnswer(call->{operations.cancel(request.id());return new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main");});operations.cycle();
        assertThat(operations.status(request.id()).state()).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items",Integer.class)).isZero();
    }
    @Test void cancellationCannotUndoAnAlreadyActivatedJob() {
        seed(1);var request=request("cancel-too-late",1);operations.send(request);operations.cycle();operations.cycle();assertThat(operations.cancel(request.id()).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT state FROM calibre_jobs",String.class)).isEqualTo("QUEUED");
    }
    @Test void fastPhaseLogsContainOnlyTheirFinalCountsAtInfo() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CalibrePreparationOperations.class);var level=logger.getLevel();var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            seed(1201);operations.prepare(new CalibrePreparationOperations.PreviewRequest("concise-logs",new CatalogCalibreTransfers.Selection(new CatalogBookFilter())));operations.cycle();
            var phases=appender.list.stream().filter(event->event.getLevel()==ch.qos.logback.classic.Level.INFO && event.getFormattedMessage().startsWith("Preparación de envío a Calibre:")).toList();assertThat(phases).hasSize(5);assertThat(phases).allSatisfy(event->assertThat(event.getFormattedMessage()).contains("procesados=1201, total=1201"));
            assertThat(appender.list).noneSatisfy(event->assertThat(event.getFormattedMessage()).contains("fase=CATALOG, procesados=0"));
            assertThat(appender.list).noneSatisfy(event->assertThat(event.getFormattedMessage()).contains("fase=DOWNLOAD_RECORDS, procesados=500"));
            assertThat(appender.list).noneSatisfy(event->assertThat(event.getFormattedMessage()).contains("fase=FILES, procesados=500"));
        } finally {logger.detachAppender(appender);appender.stop();logger.setLevel(level);}
    }

    @Test void cancellingPreparationDoesNotCancelAnotherIndependentSend() {
        seed(1501);var request=request("cancel-independent",1501);operations.send(request);operations.cycle();
        String staged=jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class);
        var separate=catalog.create(request("independent-send",1));String dependency=jdbc.queryForObject("SELECT download_job_id FROM calibre_job_items WHERE job_id=?",String.class,separate.id());assertThat(dependency).isNotEqualTo(staged);
        operations.cancel(request.id());assertThat(transfers.job(separate.id()).state()).isEqualTo("QUEUED");assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id=?",String.class,dependency)).isEqualTo("QUEUED");assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs WHERE id=?",Integer.class,staged)).isZero();
    }
    @Test void aQueuedPreparationCanBeCancelledBeforeCreatingAnyJob() {
        seed(1);var request=request("cancel-queued",1);operations.send(request);operations.cancel(request.id());operations.cycle();assertThat(operations.status(request.id()).state()).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isZero();
    }

}
