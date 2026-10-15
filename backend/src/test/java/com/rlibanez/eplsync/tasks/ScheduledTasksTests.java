package com.rlibanez.eplsync.tasks;

import java.time.*;
import java.util.*;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.EventJournal;
import com.rlibanez.eplsync.settings.CalibreCatalogService;
import com.rlibanez.eplsync.torrent.updates.UpdateCleanupService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.tasks.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@WithMockUser(roles="ADMIN")
class ScheduledTasksTests {
    @Autowired ScheduledTasks tasks;
    @Autowired InternalTaskSettings internal;
    @Autowired AccountStore accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventJournal journal;
    @MockitoBean CalibreCatalogService calibre;
    @MockitoBean UpdateCleanupService torrent;
    @MockitoBean UpdateSearches searches;
    @MockitoBean com.rlibanez.eplsync.service.CatalogImportService catalog;
    Account owner;
    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM scheduled_tasks");accounts.clear();
        owner=accounts.initialize("admin","admin@example.org","password123","password123");
        internal.save(InternalTaskSettings.CALIBRE,"30s");internal.save(InternalTaskSettings.CLEANUP,"30s");
        when(calibre.sync()).thenReturn(new CalibreCatalogService.Snapshot("main",Instant.now(),12,10));
    }
    ScheduledTasks.Input input(String name,ScheduledTasks.Operation operation) {return new ScheduledTasks.Input(name,operation,"0 */15 * * * *",owner.id(),true);}
    ScheduledTasks.Task due(String name,ScheduledTasks.Operation operation) {
        var task=tasks.save(null,input(name,operation));jdbc.update("UPDATE scheduled_tasks SET next_run=0 WHERE id=?",task.id());return task;
    }
    @Test @WithMockUser(authorities={"CALIBRE_SYNC","CALIBRE_READ","CATALOG_READ"})
    void explicitExecutionPreservesScheduleAndSharesClaimsWithCron() {
        var task=due("Library",ScheduledTasks.Operation.CALIBRE_SYNC);
        var claimed=tasks.claimNow(task.id(),Instant.now());
        assertThat(claimed.running()).isTrue();
        assertThat(claimed.nextRun()).isEqualTo(Instant.EPOCH);
        assertThat(tasks.claim(Instant.now())).isEmpty();
        assertThatThrownBy(()->tasks.claimNow(task.id(),Instant.now())).hasMessageContaining("ejecutándose");
        var paired=due("Updates",ScheduledTasks.Operation.CALIBRE_UPDATE_SEARCH);
        assertThatThrownBy(()->tasks.claimNow(paired.id(),Instant.now())).hasMessageContaining("ejecutándose");
        tasks.finish(task.id(),"SUCCEEDED",null);
        tasks.enable(task.id(),false);
        var disabled=tasks.claimNow(task.id(),Instant.now());
        assertThat(disabled.enabled()).isFalse();assertThat(disabled.nextRun()).isNull();
        tasks.finish(task.id(),"SUCCEEDED",null);
        assertThat(tasks.find(task.id()).lastFinished()).isNotNull();
    }
    @Test @WithMockUser(authorities="SETTINGS_MANAGE")
    void explicitExecutionChecksCallerPermissions() {
        var task=tasks.save(null,input("Library",ScheduledTasks.Operation.CALIBRE_SYNC));
        assertThatThrownBy(()->tasks.claimNow(task.id(),Instant.now())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(tasks.find(task.id()).running()).isFalse();
    }
    @Test @WithMockUser(authorities="CALIBRE_SYNC")
    void runNowDispatchesAsynchronouslyAndRejectsADuplicate() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(calibre.sync()).thenAnswer(call->{
            entered.countDown();
            if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return new CalibreCatalogService.Snapshot("main",Instant.now(),12,10);
        });
        var task=tasks.save(null,input("Library",ScheduledTasks.Operation.CALIBRE_SYNC));
        var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        try {
            scheduler.start();
            var next=tasks.find(task.id()).nextRun();
            assertThat(scheduler.runNow(task.id()).running()).isTrue();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->scheduler.runNow(task.id())).hasMessageContaining("ejecutándose");
            assertThat(tasks.find(task.id()).nextRun()).isEqualTo(next);
        } finally {release.countDown();scheduler.close();}
        assertThat(tasks.find(task.id()).lastResult()).isEqualTo("SUCCEEDED");
        assertThat(tasks.find(task.id()).running()).isFalse();
        verify(calibre,times(1)).sync();
    }
    @Test @WithMockUser(authorities="CALIBRE_SYNC")
    void cronAndExplicitExecutionRacingForTheSameTaskOnlyClaimItOnce() throws Exception {
        var task=due("Race",ScheduledTasks.Operation.CALIBRE_SYNC);
        var start=new java.util.concurrent.CyclicBarrier(2);
        var auth=SecurityContextHolder.getContext().getAuthentication();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var cron=executor.submit(()->{start.await(5,java.util.concurrent.TimeUnit.SECONDS);return tasks.claim(Instant.now()).isPresent();});
            var manual=executor.submit(()->{
                var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);
                try {
                    start.await(5,java.util.concurrent.TimeUnit.SECONDS);
                    try {tasks.claimNow(task.id(),Instant.now());return true;}
                    catch(com.rlibanez.eplsync.exception.UserInputException ex) {assertThat(ex).hasMessageContaining("ejecutándose");return false;}
                } finally {SecurityContextHolder.clearContext();}
            });
            assertThat(java.util.List.of(cron.get(5,java.util.concurrent.TimeUnit.SECONDS),manual.get(5,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(tasks.find(task.id()).running()).isTrue();
    }
    @Test void editsActivationAndValidatesCronOwnerAndIntervals() {
        var task=tasks.save(null,input("Library",ScheduledTasks.Operation.CALIBRE_SYNC));
        assertThat(task.nextRun()).isAfter(Instant.now());
        assertThat(tasks.enable(task.id(),false).nextRun()).isNull();
        assertThat(tasks.enable(task.id(),true).enabled()).isTrue();
        assertThat(tasks.save(task.id(),new ScheduledTasks.Input("Renamed",task.operation(),"0 0 4 * * *",owner.id(),false)).name()).isEqualTo("Renamed");
        assertThatThrownBy(()->tasks.save(null,new ScheduledTasks.Input("Invalid",task.operation(),"bad",owner.id(),true))).hasMessageContaining("Cron");
        assertThatThrownBy(()->ScheduledTasks.next("0 0 4 * * *","Invalid/Zone",Instant.now())).hasMessageContaining("zona");
        assertThatThrownBy(()->tasks.save(null,new ScheduledTasks.Input("Invalid",task.operation(),"0 0 4 * * *","missing",true))).hasMessageContaining("usuario");
        assertThat(internal.save(InternalTaskSettings.CALIBRE,"1m").interval()).isEqualTo("60s");
        assertThat(internal.interval(InternalTaskSettings.CALIBRE)).isEqualTo(Duration.ofMinutes(1));
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='tasks.internal.calibre-download-check'",String.class)).isEqualTo("60s");
        assertThatThrownBy(()->internal.save(InternalTaskSettings.CALIBRE,"0s")).hasMessageContaining("10s");
        assertThatThrownBy(()->internal.save("other","30s")).hasMessageContaining("desconocido");
    }
    @Test void intervalsDefaultToSecondsAndRejectAnythingBelowTenSeconds() {
        assertThat(internal.save(InternalTaskSettings.CALIBRE,"30").interval()).isEqualTo("30s");
        assertThat(internal.save(InternalTaskSettings.CALIBRE,"10").interval()).isEqualTo("10s");
        for(String value:List.of("9","9s","9999ms"))
            assertThatThrownBy(()->internal.save(InternalTaskSettings.CALIBRE,value)).hasMessageContaining("10s");
        assertThat(internal.interval(InternalTaskSettings.CALIBRE)).isEqualTo(Duration.ofSeconds(10));
    }
    @Test void importsTheConfiguredCatalogWithoutReplacingIt() {
        when(catalog.updateCatalog(null)).thenReturn(new com.rlibanez.eplsync.dto.ImportResult(true,"ok",10,0,2,8,0));
        var task=due("Catalog",ScheduledTasks.Operation.CATALOG_IMPORT);
        assertThat(task.timezone()).isEqualTo(tasks.timezone());
        var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        try {
            scheduler.run(tasks.claim(Instant.now()).orElseThrow());
            assertThat(tasks.find(task.id()).lastResult()).isEqualTo("SUCCEEDED");
            verify(catalog).updateCatalog(null);verify(catalog,never()).importCatalog();
        } finally {scheduler.close();}
    }
    @Test void restoringUsesInstallationConfigurationAndRemovesOnlyThatOverride() {
        internal.save(InternalTaskSettings.CALIBRE,"1m");internal.save(InternalTaskSettings.CLEANUP,"2m");
        assertThat(internal.restore(InternalTaskSettings.CALIBRE).interval()).isEqualTo("30s");
        assertThat(internal.interval(InternalTaskSettings.CLEANUP)).isEqualTo(Duration.ofMinutes(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings WHERE setting_key='tasks.internal.calibre-download-check'",Long.class)).isZero();
        var configured=new InternalTaskSettings(jdbc,new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()),"45s","30s");
        configured.save(InternalTaskSettings.CALIBRE,"1m");
        assertThat(configured.restore(InternalTaskSettings.CALIBRE).interval()).isEqualTo("45s");
        assertThatThrownBy(()->configured.restore("unknown")).hasMessageContaining("desconocido");
    }
    @Test void editingDoesNotReassignTheCreator() {
        var task=tasks.save(null,input("Original",ScheduledTasks.Operation.CALIBRE_SYNC));
        var edited=tasks.save(task.id(),new ScheduledTasks.Input("Edited",task.operation(),"0 5 * * *","missing",true));
        assertThat(edited.ownerId()).isEqualTo(owner.id());
        assertThat(edited.ownerUsername()).isEqualTo(owner.username());
    }
    @Test void previewUsesTheExecutionParserForBothFormatsAndRejectsImpossibleDates() {
        var now=Instant.parse("2026-01-01T00:00:00Z");
        var five=tasks.preview("0 5 * * *",now);
        assertThat(five.executions()).hasSize(5);
        var expected=now;
        for(var actual:five.executions()) {expected=ScheduledTasks.next("0 5 * * *",tasks.timezone(),expected);assertThat(actual).isEqualTo(expected);}
        var six=tasks.preview("*/30 * * * * *",now);
        assertThat(six.executions()).containsExactly(now.plusSeconds(30),now.plusSeconds(60),now.plusSeconds(90),now.plusSeconds(120),now.plusSeconds(150));
        assertThatThrownBy(()->tasks.preview("bad",now)).hasMessageContaining("Cron");
        assertThatThrownBy(()->tasks.preview("0 0 31 2 *",now)).hasMessageContaining("Cron");
    }
    @Test void scheduledLoggingAndEventsOnlyExposeProblemsAtNormalLevels() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(TaskScheduler.class);
        var previousLevel=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        long cursor=journal.cursor();
        try {
            due("Success",ScheduledTasks.Operation.CALIBRE_SYNC);scheduler.run(tasks.claim(Instant.now()).orElseThrow());
            assertThat(journal.cursor()).isEqualTo(cursor);
            assertThat(appender.list).anyMatch(e -> e.getLevel()==ch.qos.logback.classic.Level.DEBUG && e.getFormattedMessage().contains("Inicio de tarea programada"));
            assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().contains("tarea programada")).allMatch(e -> e.getLevel()==ch.qos.logback.classic.Level.DEBUG);
            when(catalog.updateCatalog(null)).thenReturn(new com.rlibanez.eplsync.dto.ImportResult(true,"partial",10,2,0,8,0));
            var partial=due("Partial",ScheduledTasks.Operation.CATALOG_IMPORT);scheduler.run(tasks.claim(Instant.now()).orElseThrow());
            assertThat(tasks.find(partial.id()).lastResult()).isEqualTo("PARTIAL");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE id>? AND action='SCHEDULED_TASK'",Long.class,cursor)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT outcome FROM app_events WHERE id>? AND action='SCHEDULED_TASK'",String.class,cursor)).isEqualTo("PARTIAL");
            assertThat(appender.list).anyMatch(e -> e.getLevel()==ch.qos.logback.classic.Level.WARN && e.getFormattedMessage().contains("PARTIAL"));
        } finally {scheduler.close();logger.detachAppender(appender);appender.stop();logger.setLevel(previousLevel);}
    }
    @Test void scheduledUpdateSearchesUseTheCreatorAndNeverSendFiles() {
        when(searches.downloads(owner.id())).thenReturn(Map.of("updates",2));
        when(searches.calibre(owner.id())).thenReturn(Map.of("updates",3));
        var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        try {
            for(var operation:List.of(ScheduledTasks.Operation.TORRENT_UPDATE_SEARCH,ScheduledTasks.Operation.CALIBRE_UPDATE_SEARCH)) {
                var task=due("Search",operation);scheduler.run(tasks.claim(Instant.now()).orElseThrow());
                assertThat(tasks.find(task.id()).lastResult()).isEqualTo("SUCCEEDED");
            }
            verify(searches).downloads(owner.id());verify(searches).calibre(owner.id());
            verifyNoInteractions(torrent,catalog);verify(calibre,never()).sync();
        } finally {scheduler.close();}
    }
    @Test void updateSearchAndSyncForTheSameIntegrationDoNotOverlap() {
        var sync=due("Sync",ScheduledTasks.Operation.CALIBRE_SYNC);
        var first=tasks.claim(Instant.now()).orElseThrow();assertThat(first.id()).isEqualTo(sync.id());
        due("Search",ScheduledTasks.Operation.CALIBRE_UPDATE_SEARCH);
        assertThat(tasks.claim(Instant.now())).isEmpty();
        tasks.finish(first.id(),"SUCCEEDED",null);
        assertThat(tasks.claim(Instant.now()).orElseThrow().operation()).isEqualTo(ScheduledTasks.Operation.CALIBRE_UPDATE_SEARCH);
    }
    @Test void cleanupTimerUsesTheSavedPeriodWithoutRestart() {
        var queue=mock(com.rlibanez.eplsync.torrent.updates.CleanupQueue.class);
        var bulk=mock(com.rlibanez.eplsync.torrent.bulk.BulkStore.class);
        var timer=new com.rlibanez.eplsync.torrent.updates.AutomaticUpdateCleanup(queue,bulk);
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(timer,"internalSettings",internal);
            org.springframework.test.util.ReflectionTestUtils.setField(timer,"lastCycle",System.nanoTime()-Duration.ofSeconds(40).toNanos());
            internal.save(InternalTaskSettings.CLEANUP,"1m");timer.schedule();verify(queue,never()).runAutomatic(anyInt());
            internal.save(InternalTaskSettings.CLEANUP,"30s");timer.schedule();verify(queue,timeout(2000)).runAutomatic(100);
            timer.schedule();verify(queue,times(1)).runAutomatic(100);
        } finally {timer.close();}
    }
    @Test void scheduledTorrentRunDoesNotRequestDownloadDetails() {
        var result=mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.SyncResult.class);
        when(result.records()).thenReturn(new com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.RecordCounts(3,1,2,0));
        when(torrent.synchronize(false,false)).thenReturn(result);
        var task=due("Torrent",ScheduledTasks.Operation.TORRENT_SYNC);var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        try {scheduler.run(tasks.claim(Instant.now()).orElseThrow());assertThat(tasks.find(task.id()).lastResult()).isEqualTo("SUCCEEDED");verify(torrent).synchronize(false,false);verify(calibre,never()).sync();}
        finally {scheduler.close();}
    }
    @Test void fiveFieldCronRunsAtFiveAndRemainsEditableWithoutAddingAField() {
        assertThat(ScheduledTasks.next("0 5 * * *","Europe/Madrid",Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-01-01T04:00:00Z"));
        assertThat(ScheduledTasks.next("0 5 * * *","Europe/Madrid",Instant.parse("2026-07-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-07-01T03:00:00Z"));
        var task=tasks.save(null,new ScheduledTasks.Input("Daily",ScheduledTasks.Operation.CALIBRE_SYNC,"0 5 * * *",owner.id(),true));
        assertThat(task.cron()).isEqualTo("0 5 * * *");
        tasks.enable(task.id(),false);tasks.enable(task.id(),true);tasks.recover(Instant.now());
        assertThat(tasks.find(task.id()).nextRun()).isAfter(Instant.now());
        jdbc.update("UPDATE scheduled_tasks SET next_run=0 WHERE id=?",task.id());
        assertThat(tasks.claim(Instant.now())).isPresent();
    }
    @Test void cronHonorsZoneAndDaylightSaving() {
        assertThat(ScheduledTasks.next("0 0 3 * * *","Europe/Madrid",Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-01-01T02:00:00Z"));
        assertThat(ScheduledTasks.next("0 0 3 * * *","Europe/Madrid",Instant.parse("2026-07-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
        assertThat(ScheduledTasks.next("*/30 * * * * *","UTC",Instant.parse("2026-07-01T00:00:02Z"))).isEqualTo(Instant.parse("2026-07-01T00:00:30Z"));
        assertThatThrownBy(()->ScheduledTasks.next("0 0 0 31 2 *","UTC",Instant.now())).hasMessageContaining("Cron");
    }
    @Test void claimsPreventOverlapButAllowDifferentOperationsAndDisablingDoesNotCancelRunningWork() {
        due("One",ScheduledTasks.Operation.CALIBRE_SYNC);due("Two",ScheduledTasks.Operation.CALIBRE_SYNC);
        due("Torrent",ScheduledTasks.Operation.TORRENT_SYNC);
        var a=tasks.claim(Instant.now()).orElseThrow();var b=tasks.claim(Instant.now()).orElseThrow();
        assertThat(a.operation()).isNotEqualTo(b.operation());assertThat(tasks.claim(Instant.now())).isEmpty();
        assertThatThrownBy(()->tasks.exclusive(()->true)).hasMessageContaining("ejecución");
        var running=tasks.find(a.id());assertThatThrownBy(()->tasks.save(a.id(),input("Edited",running.operation()))).hasMessageContaining("ejecutándose");
        assertThat(tasks.enable(a.id(),false).running()).isTrue();tasks.finish(a.id(),"SUCCEEDED",null);
        assertThat(tasks.find(a.id()).enabled()).isFalse();
        tasks.finish(b.id(),"SUCCEEDED",null);
        assertThat(tasks.claim(Instant.now())).isPresent();
    }
    @Test void restartMarksInterruptedRunsAndDoesNotReplayMissedSchedules() {
        var task=due("Old",ScheduledTasks.Operation.CALIBRE_SYNC);tasks.claim(Instant.now()).orElseThrow();
        Instant now=Instant.now().plus(Duration.ofDays(2));tasks.recover(now);
        var recovered=tasks.find(task.id());assertThat(recovered.lastResult()).isEqualTo("INTERRUPTED");assertThat(recovered.running()).isFalse();assertThat(recovered.nextRun()).isAfter(now);
        assertThat(tasks.claim(now)).isEmpty();
    }
    @Test void revokedPermissionPreventsExecutionForAnAssociatedNonAdministrator() {
        var temporary=accounts.create("reader","reader@example.org","USER",owner.id());
        accounts.changePassword(accounts.find(temporary.user().id()),temporary.password(),"password123");
        String reader=temporary.user().id();
        accounts.update(reader,"USER","ACTIVE",Map.of("CALIBRE_SYNC","ALLOW"),owner.id());
        var task=tasks.save(null,new ScheduledTasks.Input("Reader sync",ScheduledTasks.Operation.CALIBRE_SYNC,"0 0 3 * * *",reader,true));
        jdbc.update("UPDATE scheduled_tasks SET next_run=0 WHERE id=?",task.id());
        accounts.update(reader,"USER","ACTIVE",Map.of("CALIBRE_SYNC","DENY"),owner.id());
        var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        try {
            scheduler.run(tasks.claim(Instant.now()).orElseThrow());
            assertThat(tasks.find(task.id()).lastResult()).isEqualTo("FAILED");verify(calibre,never()).sync();
            assertThat(jdbc.queryForObject("SELECT actor_id FROM app_events WHERE action='SCHEDULED_TASK' AND outcome='FAILED' ORDER BY id DESC LIMIT 1",String.class)).isEqualTo(reader);
        } finally {scheduler.close();}
    }
    @Test void runnerUsesCurrentUserPermissionsAndRecordsScheduledOrigin() {
        var task=due("Calibre",ScheduledTasks.Operation.CALIBRE_SYNC);var claimed=tasks.claim(Instant.now()).orElseThrow();
        var scheduler=new TaskScheduler(tasks,calibre,torrent,journal,catalog,searches);
        var previous=SecurityContextHolder.getContext().getAuthentication();
        try {
            doAnswer(call -> {
                assertThat(Permission.has(Permission.CALIBRE_SYNC)).isTrue();
                assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isInstanceOf(Account.class);
                return new CalibreCatalogService.Snapshot("main",Instant.now(),12,10);
            }).when(calibre).sync();
            scheduler.run(claimed);
            assertThat(tasks.find(task.id()).lastResult()).isEqualTo("SUCCEEDED");verify(calibre).sync();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE action='SCHEDULED_TASK' AND operation_id IS NOT NULL AND created_at>=?",Long.class,claimed.lastRun().toEpochMilli())).isZero();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(previous);
            var revoked=due("Revoked",ScheduledTasks.Operation.CALIBRE_SYNC);accounts.create("backup","backup@example.org","ADMIN",owner.id());accounts.update(owner.id(),"ADMIN","DISABLED",Map.of(),owner.id());
            scheduler.run(tasks.claim(Instant.now()).orElseThrow());
            assertThat(tasks.find(revoked.id()).lastResult()).isEqualTo("FAILED");verify(calibre,times(1)).sync();
        } finally {scheduler.close();}
    }
}
