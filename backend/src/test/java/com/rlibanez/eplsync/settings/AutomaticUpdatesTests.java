package com.rlibanez.eplsync.settings;

import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.rlibanez.eplsync.tasks.*;
import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.updates.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.tasks.worker-enabled=false","eplsync.torrent.enabled=true","eplsync.torrent.qbittorrent.auth.api-key=automation-test-key","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@WithMockUser(authorities={"ROLE_ADMIN","SETTINGS_MANAGE","CATALOG_READ","CATALOG_IMPORT","TORRENT_SEND","TORRENT_SYNC","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CALIBRE_READ","CALIBRE_SYNC","CALIBRE_SEND","CALIBRE_JOBS_MANAGE","CALIBRE_DELETE","DOWNLOADS_FILES_READ"})
class AutomaticUpdatesTests {
    @Autowired AutomaticUpdates automations;
    @Autowired ScheduledTasks tasks;
    @Autowired AccountStore accounts;
    @Autowired CalibreSettings calibre;
    @Autowired DownloadTrackingService tracking;
    @Autowired BulkStore bulk;
    @Autowired CalibreTransfers transfers;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean UpdateSearches searches;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.rlibanez.eplsync.service.TorrentClientService torrentService;
    @MockitoBean SessionAccess sessions;
    @MockitoBean com.rlibanez.eplsync.service.CatalogImportService catalog;
    Account owner;
    JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM scheduled_tasks");jdbc.update("DELETE FROM job_retry_items");jdbc.update("DELETE FROM job_retry_jobs");jdbc.update("DELETE FROM job_retry_previews");
        jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM calibre_update_requests");jdbc.update("DELETE FROM torrent_update_cleanup");jdbc.update("DELETE FROM torrent_update_plans");jdbc.update("DELETE FROM torrent_bulk_items");jdbc.update("DELETE FROM torrent_bulk_jobs");jdbc.update("DELETE FROM torrent_downloads");jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM catalog_books");accounts.clear();
        doReturn(null).when(torrentService).exclusiveClient(any());
        owner=accounts.initialize("Owner","owner@example.org","password123","password123");when(sessions.current()).thenReturn(owner);
        calibre.save(new CalibreSettings.Input("http://calibre.test","","",true,"main"));
        when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main"));
        when(searches.searchDownloads(anyString(),any())).thenReturn(new UpdateSearches.Search(List.of(new RevisionUpdates.Row(1L,"Book",1.0,1.1,DownloadStatus.DOWNLOADED,null,false)),1,List.of(),EventContext.Origin.SCHEDULED,Map.of()));
        var rows=List.of(Map.of("calibreId",1L,"eplId",1L));
        when(searches.searchCalibre(anyString())).thenReturn(new UpdateSearches.CalibreSearch(new CalibreCatalogService.Snapshot("main",Instant.now(),1,1),new UpdateSearches.Search(rows,1,List.of(),EventContext.Origin.SCHEDULED,Map.of())));
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author,links) VALUES(1,1.1,'Book','Author',?)","A".repeat(40));
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at,completed_at) VALUES('old',1,1.0,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0,1)","B".repeat(40),tracking.instanceId());
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('book','generation',1,1,1,1.0,'Book','Author')");
    }
    AutomaticUpdates.Configuration config(Map<String,Object> changes) {
        Map<String,Object> fields=json.convertValue(automations.defaults(),json.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));fields.putAll(changes);return json.convertValue(fields,AutomaticUpdates.Configuration.class);
    }
    ScheduledTasks.Task task(ScheduledTasks.Operation op,Map<String,Object> changes) {
        var task=tasks.save(null,new ScheduledTasks.Input("Automation",op,"0 5 * * *",owner.id(),true));automations.save(task,config(changes));return task;
    }
    Map<String,Object> execute(ScheduledTasks.Task task) {return EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->EventContext.withOrigin(EventContext.Origin.SCHEDULED,()->automations.execute(task)));}
    @Test void idleAutomationWorkerDetectsNewCycleAndJpaCompletion() {
        automations.refresh();
        var probe=(com.rlibanez.eplsync.config.WorkerActivity.Probe)org.springframework.test.util.ReflectionTestUtils.getField(automations,"idleProbe");
        assertThat(probe.ready()).isFalse();
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of());execute(task);
        assertThat(probe.ready()).isTrue();
        String job=automations.latest(task.id()).jobs().getFirst().id();
        bulk.next();var item=bulk.claim(job,bulk.pending(job,1).getFirst());
        bulk.finish(item.getId(),BulkItem.State.ACCEPTED,null,false,false);
        org.springframework.test.util.ReflectionTestUtils.setField(automations,"workerEnabled",true);
        try {
            automations.tick();
            assertThat(automations.latest(task.id()).state()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isEqualTo(1);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(automations,"workerEnabled",false);}
    }
    @Test void torrentCyclePersistsFrozenOptionsLinksAndDoesNotStartAnotherWhilePending() {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of("torrentCleanup","removeTorrentAndFiles"));
        var result=execute(task);var cycle=automations.latest(task.id());
        assertThat(cycle.id()).isEqualTo(result.get("cycle"));assertThat(cycle.jobs()).hasSize(1);assertThat(cycle.state()).isEqualTo("WAITING");
        String job=cycle.jobs().getFirst().id();
        assertThat(jdbc.queryForObject("SELECT automatic_cleanup FROM torrent_update_plans WHERE job_id=?",Integer.class,job)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cleanup_timing FROM torrent_update_plans WHERE job_id=?",String.class,job)).isEqualTo("AFTER_DOWNLOAD");
        assertThat(execute(task).get("pending")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->automations.save(task,config(Map.of()))).hasMessageContaining("ciclo pendiente");
        bulk.next();var item=bulk.claim(job,bulk.pending(job,1).getFirst());bulk.finish(item.getId(),BulkItem.State.ACCEPTED,null,false,false);
        automations.refresh();assertThat(automations.latest(task.id()).state()).isEqualTo("WAITING");
        jdbc.update("UPDATE torrent_update_cleanup SET state='REMOVED' WHERE job_id=?",job);bulk.cleanupChanged(job);automations.refresh();
        assertThat(automations.latest(task.id()).state()).isEqualTo("COMPLETED");
    }
    @Test void calibreCycleReusesTorrentAutomationAndPreservesBothScopes() {
        var download=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of());execute(download);
        String dependency=automations.latest(download.id()).jobs().getFirst().id();
        var library=task(ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE,Map.of());execute(library);
        var cycle=automations.latest(library.id());assertThat(cycle.jobs()).hasSize(1);
        assertThat(cycle.jobs().getFirst().kind()).isEqualTo("calibre");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT download_job_id FROM calibre_job_items WHERE job_id=?",String.class,cycle.jobs().getFirst().id())).isEqualTo(dependency);
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_job_items WHERE job_id=?",String.class,cycle.jobs().getFirst().id())).isEqualTo("WAITING_DOWNLOAD");
        assertThat(execute(library).get("pending")).isEqualTo(1);
    }
    @Test void calibreCycleUsesReadyEpubAndNeverCreatesATorrentJob() throws Exception {
        var bytes=CalibreTransfersTests.epub(1,"1.1");
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('ready',1,1.1,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0)","A".repeat(40),tracking.instanceId());
        when(files.list("ready")).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var task=task(ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE,Map.of());execute(task);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isZero();
        var cycle=automations.latest(task.id());assertThat(cycle.jobs()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT record_id FROM calibre_job_items WHERE job_id=?",String.class,cycle.jobs().getFirst().id())).isEqualTo("ready");
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_job_items WHERE job_id=?",String.class,cycle.jobs().getFirst().id())).isEqualTo("PENDING");
    }
    @Test void savedLegacyTitleAndAuthorFiltersAreIgnoredForBothAutomations() {
        for(var operation:List.of(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE)) {
            var task=task(operation,Map.of());
            jdbc.update("UPDATE scheduled_automation_settings SET configuration=json_set(configuration,'$.title','Unmatched title','$.author','Unmatched author') WHERE task_id=?",task.id());
            var view=automations.view().get(task.id()).configuration();
            assertThat(json.writeValueAsString(view)).doesNotContain("\"title\":","\"author\":");
            execute(task);
            assertThat(automations.latest(task.id()).jobs()).isNotEmpty();
        }
    }
    @Test void noUpdatesAndCatalogFailuresCreateNoUnwantedJobs() {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of());
        when(searches.searchDownloads(anyString(),any())).thenReturn(new UpdateSearches.Search(List.of(),1,List.of(),EventContext.Origin.SCHEDULED,Map.of()));
        execute(task);assertThat(automations.latest(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isZero();
        automations.save(task,config(Map.of("updateCatalog",true)));
        when(catalog.updateCatalog(null)).thenThrow(new com.rlibanez.eplsync.exception.UserInputException("Catalog unavailable"));
        assertThatThrownBy(()->execute(task)).hasMessageContaining("Catalog unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isZero();
    }
    @Test void changedCalibreLibraryStopsAutomationUntilReviewed() {
        var task=task(ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE,Map.of());
        calibre.save(new CalibreSettings.Input("http://calibre.test","","",true,"other"));
        assertThatThrownBy(()->execute(task)).hasMessageContaining("biblioteca cambió");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Long.class)).isZero();
    }
    @Test void failedItemsAreRetriedWithinBudgetAndOriginalHistoryIsPreserved() {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of("maxRetries",1));execute(task);
        String original=automations.latest(task.id()).jobs().getFirst().id();
        bulk.next();var item=bulk.claim(original,bulk.pending(original,1).getFirst());bulk.finish(item.getId(),BulkItem.State.FAILED,"test",false,false);
        execute(task);var cycle=automations.latest(task.id());assertThat(cycle.attempts()).isEqualTo(1);
        String child=cycle.jobs().getFirst().id();assertThat(child).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_items WHERE job_id=?",String.class,original)).isEqualTo("PENDING");
        bulk.next();var retried=bulk.claim(child,bulk.pending(child,1).getFirst());bulk.finish(retried.getId(),BulkItem.State.FAILED,"test",false,false);
        execute(task);assertThat(automations.latest(task.id()).state()).isEqualTo("ATTENTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Long.class)).isEqualTo(1);
        automations.close(task.id());assertThat(automations.latest(task.id()).state()).isEqualTo("COMPLETED");
    }
    @Test @WithMockUser(authorities={"TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","CATALOG_READ"})
    void destructivePolicyRequiresTheCurrentCallersDeletePermissions() {
        var task=tasks.save(null,new ScheduledTasks.Input("Automation",ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,"0 5 * * *",owner.id(),true));
        assertThatThrownBy(()->automations.save(task,config(Map.of("torrentCleanup","removeTorrentAndFiles")))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_automation_settings",Long.class)).isZero();
    }
    @Test void calibreAutomationDownloadsBooksWithoutRequiringTorrentHistory() {
        jdbc.update("DELETE FROM torrent_downloads");
        var task=task(ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE,Map.of());execute(task);
        var cycle=automations.latest(task.id());assertThat(cycle.jobs()).hasSize(2);
        String download=cycle.jobs().stream().filter(j->j.kind().equals("downloads")).findFirst().orElseThrow().id();
        assertThat(jdbc.queryForObject("SELECT epl_id FROM torrent_bulk_items WHERE job_id=?",Long.class,download)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT download_job_id FROM calibre_job_items",String.class)).isEqualTo(download);
        assertThatThrownBy(()->automations.close(task.id())).hasMessageContaining("trabajos pendientes");
    }
    @Test void unfinishedPlanningIsRecoveredWithoutInventingOrRepeatingJobs() {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of());
        var c=automations.view().get(task.id()).configuration();
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,owner_id,state,created_at,configuration,context) VALUES('interrupted',?,?,'WAITING',0,?,'SCHEDULED')",task.id(),owner.id(),json.writeValueAsString(c));
        automations.refresh();assertThat(automations.latest(task.id()).state()).isEqualTo("COMPLETED");
        execute(task);assertThat(automations.latest(task.id()).jobs()).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=EventContext.Origin.class,names={"MANUAL","SCHEDULED"})
    void taskJobsOnlyRecordProblemsRegardlessOfExecutionOrigin(EventContext.Origin origin) {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of("maxRetries",0));
        EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->EventContext.withOrigin(origin,()->automations.execute(task)));
        String job=automations.latest(task.id()).jobs().getFirst().id();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=?",Long.class,job)).isZero();
        bulk.next();var item=bulk.claim(job,bulk.pending(job,1).getFirst());bulk.finish(item.getId(),BulkItem.State.FAILED,"test",false,false);
        assertThat(jdbc.queryForList("SELECT outcome FROM app_events WHERE operation_id=? ORDER BY id",String.class,job)).containsExactly("STARTED","PARTIAL");
        assertThat(jdbc.queryForList("SELECT DISTINCT origin FROM app_events WHERE operation_id=?",String.class,job)).containsExactly(origin.name());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=EventContext.Origin.class,names={"MANUAL","SCHEDULED"})
    void completedTaskDownloadJobsDoNotRecordSuccess(EventContext.Origin origin) {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of("torrentCleanup","keep"));
        EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->EventContext.withOrigin(origin,()->automations.execute(task)));
        String job=automations.latest(task.id()).jobs().getFirst().id();
        bulk.next();var item=bulk.claim(job,bulk.pending(job,1).getFirst());bulk.finish(item.getId(),BulkItem.State.ACCEPTED,"ok",false,false);
        assertThat(bulk.view(job).status()).isEqualTo(BulkJob.State.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=?",Long.class,job)).isZero();
    }
    @Test void pausedCyclesNotifyOnceAndRecoverWhenTheirJobsResume() {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of());execute(task);
        String run=automations.latest(task.id()).id(),job=automations.latest(task.id()).jobs().getFirst().id();
        bulk.control(job,"pause");automations.refresh();automations.refresh();
        assertThat(automations.latest(task.id()).state()).isEqualTo("ATTENTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=? AND outcome='PARTIAL'",Long.class,run)).isEqualTo(1);
        bulk.control(job,"resume");automations.refresh();
        assertThat(automations.latest(task.id()).state()).isEqualTo("WAITING");
    }
    @Test void missingPermissionsAndInvalidOptionsAreRejectedBeforeSaving() {
        var task=tasks.save(null,new ScheduledTasks.Input("Automation",ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,"0 5 * * *",owner.id(),true));
        assertThatThrownBy(()->automations.save(task,config(Map.of("limit",1001)))).hasMessageContaining("Configuración");
        assertThatThrownBy(()->automations.save(task,config(Map.of("interval","bad")))).hasMessageContaining("Intervalo");
    }

    @Test void failedReplacementReopensSameJobAndKeepsCleanupProtected() {
        var task=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of("torrentCleanup","removeTorrentAndFiles"));execute(task);
        String job=automations.latest(task.id()).jobs().getFirst().id();bulk.next();var item=bulk.claim(job,bulk.pending(job,1).getFirst());
        bulk.finish(item.getId(),BulkItem.State.FAILED,"Rejected",false,false);
        assertThat(bulk.view(job).status()).isEqualTo(BulkJob.State.PAUSED);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_update_cleanup WHERE job_id=?",String.class,job)).isEqualTo("WAITING");
        execute(task);
        assertThat(bulk.view(job).status()).isEqualTo(BulkJob.State.QUEUED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(1);
        assertThat(automations.latest(task.id()).attempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_update_cleanup WHERE job_id=?",String.class,job)).isEqualTo("WAITING");
    }

    @Test void calibreAutomationCreatesNewDownloadWhenEarlierAcceptedTorrentAndRecordAreGone() {
        var download=task(ScheduledTasks.Operation.TORRENT_AUTO_UPDATE,Map.of());execute(download);
        String old=automations.latest(download.id()).jobs().getFirst().id();
        jdbc.update("UPDATE torrent_bulk_items SET state='ACCEPTED' WHERE job_id=?",old);jdbc.update("UPDATE torrent_bulk_jobs SET state='COMPLETED' WHERE id=?",old);
        var library=task(ScheduledTasks.Operation.CALIBRE_AUTO_UPDATE,Map.of());execute(library);
        String calibreJob=automations.latest(library.id()).jobs().stream().filter(j->j.kind().equals("calibre")).findFirst().orElseThrow().id();
        String replacement=jdbc.queryForObject("SELECT download_job_id FROM calibre_job_items WHERE job_id=?",String.class,calibreJob);
        assertThat(replacement).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_items WHERE job_id=?",String.class,replacement)).isEqualTo("PENDING");
    }
}
