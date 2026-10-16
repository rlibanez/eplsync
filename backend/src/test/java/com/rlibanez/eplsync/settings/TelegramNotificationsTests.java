package com.rlibanez.eplsync.settings;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.telegram.worker-enabled=false","eplsync.tasks.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false","EPLSYNC_SECRET_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@WithMockUser(roles="ADMIN")
class TelegramNotificationsTests {
    static final String TOKEN="123456789:abcdefghijklmnopqrstuvwxyz_123456789";
    @Autowired TelegramNotifications telegram;
    @Autowired AccountStore accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventJournal journal;
    @Autowired com.rlibanez.eplsync.tasks.ScheduledTasks tasks;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean TelegramClient client;
    Account owner;
    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(telegram,"lastMaintenance",0L);
        jdbc.update("DELETE FROM scheduled_tasks");
        accounts.clear();owner=accounts.initialize("owner","owner@example.org","password123","password123");
        jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,running,created_at,updated_at) VALUES('default-task','Actualizar libros','TORRENT_SYNC','0 0 * * * *','UTC',?,'owner',0,0,0,0)",owner.id());
        when(client.send(anyString(),anyString(),anyString())).thenReturn(new TelegramClient.Result(true,false,0));
    }
    TelegramNotifications.Input input(String token,boolean success) {return new TelegramNotifications.Input(true,token,false,"123456",success,true,true,"es");}
    void save() {telegram.save(owner.id(),input(TOKEN,true));}
    EventContext.Actor actor() {return new EventContext.Actor(owner.id(),owner.username(),"USER");}
    void notify(String id,EventJournal.Outcome outcome) {journal.recordAs(actor(),EventJournal.Category.JOB,"DOWNLOAD",outcome,EventContext.Origin.SCHEDULED,id,Map.of("taskId","default-task","accepted",5,"reason",TOKEN));}
    long queued() {return jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries",Long.class);}
    @Test void asynchronousJobResolvesItsTaskNameFromPersistentLinks() {
        save();
        jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,running,created_at,updated_at) VALUES('task','Actualización nocturna','TORRENT_SYNC','0 0 * * * *','UTC',?,'owner',0,0,0,0)",owner.id());
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,owner_id,state,created_at,configuration,context) VALUES('run','task',?,'WAITING',0,'{}','SCHEDULED')",owner.id());
        jdbc.update("INSERT INTO scheduled_automation_jobs VALUES('run','calibre','async-job')");
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"CALIBRE_SEND",EventJournal.Outcome.PARTIAL,EventContext.Origin.SCHEDULED,"async-job",Map.of("reason","No se pudo verificar el EPUB"));
        assertThat(jdbc.queryForObject("SELECT message FROM telegram_deliveries",String.class)).contains("Actualización nocturna","No se pudo verificar el EPUB");
        jdbc.update("INSERT INTO scheduled_automation_jobs VALUES('run','calibre','manual-job')");
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"CALIBRE_SEND",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,"manual-job",Map.of());
        assertThat(queued()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT message FROM telegram_deliveries WHERE operation_id='manual-job'",String.class)).contains("Actualización nocturna","Completado").doesNotContain("http://","https://");
    }
    @Test void manualAndSystemOperationsNeverNotify() {
        save();
        for(var origin:List.of(EventContext.Origin.MANUAL,EventContext.Origin.SYSTEM))
            journal.notifyCompletion(actor(),EventJournal.Category.JOB,"DOWNLOAD",EventJournal.Outcome.FAILED,origin,"manual",Map.of("reason","Error"));
        assertThat(queued()).isZero();
    }
    @Test void scheduledMessagesIncludeTaskAndReasonWithoutLinksOrCredentials() {
        telegram.save(owner.id(),new TelegramNotifications.Input(true,TOKEN,false,"123456",true,true,true,"es"));
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"DOWNLOAD",EventJournal.Outcome.FAILED,EventContext.Origin.SCHEDULED,"job-123",Map.of("taskId","default-task","reason","Conexión rechazada token="+TOKEN));
        String message=jdbc.queryForObject("SELECT message FROM telegram_deliveries",String.class);
        assertThat(message).contains("Actualizar libros","Motivo: Conexión rechazada").doesNotContain(TOKEN,"http://","https://");
    }
    @Test void runNowNotifiesButDirectOperationsDoNotAndTaskOptOutIsRespected() {
        save();
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,"run-now",Map.of("taskId","default-task"));
        assertThat(queued()).isEqualTo(1);
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"DOWNLOAD",EventJournal.Outcome.FAILED,EventContext.Origin.MANUAL,"direct",Map.of());
        assertThat(queued()).isEqualTo(1);
        jdbc.update("UPDATE scheduled_tasks SET telegram_notifications=0 WHERE id='default-task'");
        notify("disabled",EventJournal.Outcome.FAILED);assertThat(queued()).isEqualTo(1);
        telegram.deliver();verifyNoInteractions(client);
    }
    @Test void formatsTaskOriginIdentifierAndResultBeforeBulletedCounters() {
        save();
        for(var outcome:List.of(EventJournal.Outcome.SUCCEEDED,EventJournal.Outcome.PARTIAL,EventJournal.Outcome.FAILED)) {
            String id=outcome.name();
            journal.notifyCompletion(actor(),EventJournal.Category.JOB,"SCHEDULED_TASK",outcome,EventContext.Origin.MANUAL,id,Map.of("taskId","default-task","accepted",18,"failed",2,"reason","No se pudo conectar"));
            String message=jdbc.queryForObject("SELECT message FROM telegram_deliveries WHERE operation_id=?",String.class,id);
            String icon=outcome==EventJournal.Outcome.SUCCEEDED?"✅":outcome==EventJournal.Outcome.PARTIAL?"⚠️":"❌";
            assertThat(message).startsWith(icon+" Actualizar libros\nOrigen: ejecución manual\nID: "+id+"\n\nResultado: ").contains("\n• Aceptados: 18\n• Fallidos: 2","Origen: ejecución manual").endsWith("• Fallidos: 2").doesNotContain("EPL Sync ·", "Tarea programada");
            if(outcome!=EventJournal.Outcome.SUCCEEDED) assertThat(message).contains("Motivo: No se pudo conectar");
        }
    }
    @Test void completedEmptyCyclesNotifyForScheduleAndRunNowButPendingCyclesDoNot() {
        save();
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,owner_id,state,created_at,configuration,context) VALUES('empty-cycle','default-task',?,'COMPLETED',0,'{}','MANUAL')",owner.id());
        for(var origin:List.of(EventContext.Origin.MANUAL,EventContext.Origin.SCHEDULED))
            journal.notifyCompletion(actor(),EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.SUCCEEDED,origin,"empty-"+origin,Map.of("taskId","default-task","cycle","empty-cycle","selected",0,"errors",0));
        assertThat(queued()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT message FROM telegram_deliveries WHERE operation_id='empty-MANUAL'",String.class)).contains("✅ Actualizar libros","Seleccionados: 0","Origen: ejecución manual");
        jdbc.update("UPDATE scheduled_automation_runs SET state='WAITING' WHERE id='empty-cycle'");
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,"pending-cycle",Map.of("taskId","default-task","cycle","empty-cycle","selected",1));
        assertThat(queued()).isEqualTo(2);
        telegram.save(owner.id(),input("",false));
        jdbc.update("UPDATE scheduled_automation_runs SET state='COMPLETED' WHERE id='empty-cycle'");
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,"muted-success",Map.of("taskId","default-task","cycle","empty-cycle","selected",0));
        assertThat(queued()).isZero();
    }
    @Test @WithMockUser(authorities={"ROLE_ADMIN","CALIBRE_SYNC"}) void actualSchedulerAndRunNowBothCreateAndDeliverNotifications() throws Exception {
        save();jdbc.update("UPDATE scheduled_tasks SET operation='CALIBRE_SYNC' WHERE id='default-task'");
        var calibre=mock(CalibreCatalogService.class);
        when(calibre.sync()).thenReturn(new CalibreCatalogService.Snapshot("main",Instant.now(),0,0));
        var scheduler=new com.rlibanez.eplsync.tasks.TaskScheduler(tasks,calibre,mock(com.rlibanez.eplsync.torrent.updates.UpdateCleanupService.class),journal,mock(com.rlibanez.eplsync.service.CatalogImportService.class),mock(com.rlibanez.eplsync.tasks.UpdateSearches.class));
        var starts=new java.util.concurrent.CopyOnWriteArrayList<EventJournal.Entry>();
        long before=journal.cursor();
        try(var subscription=journal.listenNotices(starts::add)) {
            scheduler.start();
            jdbc.update("UPDATE scheduled_tasks SET enabled=1,next_run=0 WHERE id='default-task'");
            scheduler.tick();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->{
                assertThat(queued()).isEqualTo(1);
                assertThat(tasks.find("default-task").running()).isFalse();
            });
            assertThat(jdbc.queryForObject("SELECT message FROM telegram_deliveries",String.class)).contains("Origen: ejecución programada");
            scheduler.runNow("default-task");
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->{
                assertThat(queued()).isEqualTo(2);
                assertThat(tasks.find("default-task").running()).isFalse();
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries WHERE message LIKE '%Origen: ejecución manual%'",Long.class)).isEqualTo(1);
            assertThat(starts).hasSize(4);
            assertThat(starts).extracting(EventJournal.Entry::outcome).containsExactly(EventJournal.Outcome.STARTED,EventJournal.Outcome.SUCCEEDED,EventJournal.Outcome.STARTED,EventJournal.Outcome.SUCCEEDED);
            assertThat(starts).extracting(EventJournal.Entry::origin).containsExactly(EventContext.Origin.SCHEDULED,EventContext.Origin.SCHEDULED,EventContext.Origin.MANUAL,EventContext.Origin.MANUAL);
            assertThat(starts).allSatisfy(entry->{assertThat(entry.id()).isZero();assertThat(entry.details()).containsEntry("taskName","Actualizar libros");});
            assertThat(journal.cursor()).isEqualTo(before);
            telegram.deliver();telegram.deliver();verify(client,times(2)).send(eq(TOKEN),eq("123456"),anyString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries WHERE state='SENT'",Long.class)).isEqualTo(2);
        } finally {org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduler,"close");}
    }
    @Test void intermediateCatalogueSuccessDoesNotAnnounceAnAutomaticCycleAsCompleted() {
        save();
        journal.notifyCompletion(actor(),EventJournal.Category.CATALOG,"UPDATE",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.SCHEDULED,"catalogue-step",Map.of("taskId","default-task","taskOperation","TORRENT_AUTO_UPDATE"));
        assertThat(queued()).isZero();
        journal.notifyCompletion(actor(),EventJournal.Category.CATALOG,"UPDATE",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.SCHEDULED,"catalogue-task",Map.of("taskId","default-task","taskOperation","CATALOG_IMPORT"));
        assertThat(queued()).isEqualTo(1);
    }
    @Test void draftCanBeTestedWithoutSavingOrAnEncryptionKey() {
        var ephemeral=new TelegramNotifications(jdbc,accounts,client,manager,"");
        try {
            ephemeral.test(owner.id(),new TelegramNotifications.TestInput(TOKEN,false,"654321"));
            verify(client).send(TOKEN,"654321","EPL Sync · Telegram OK");
            assertThat(telegram.view(owner.id()).tokenConfigured()).isFalse();
            assertThat(queued()).isZero();
        } finally {ephemeral.close();}
    }
    @Test void draftUsesOwnSavedTokenWithoutChangingSettingsOrPendingDeliveries() {
        save();notify("draft-test",EventJournal.Outcome.SUCCEEDED);
        telegram.test(owner.id(),new TelegramNotifications.TestInput("",false,"654321"));
        verify(client).send(TOKEN,"654321","EPL Sync · Telegram OK");
        assertThat(telegram.view(owner.id()).chatId()).isEqualTo("123456");
        assertThat(queued()).isEqualTo(1);
        assertThatThrownBy(()->telegram.test(owner.id(),new TelegramNotifications.TestInput("",true,"654321"))).hasMessageContaining("Token");
        assertThatThrownBy(()->telegram.test(owner.id(),new TelegramNotifications.TestInput(TOKEN,false,""))).hasMessageContaining("numérico");
    }
    @Test void settingsAreOffByDefaultEncryptedAndNeverExposeTheToken() {
        assertThat(telegram.view(owner.id()).enabled()).isFalse();save();
        var stored=jdbc.queryForObject("SELECT token FROM telegram_settings WHERE user_id=?",String.class,owner.id());
        assertThat(stored).startsWith("encrypted:v1:").doesNotContain(TOKEN);
        assertThat(telegram.view(owner.id()).toString()).doesNotContain(TOKEN,stored);
        telegram.save(owner.id(),input("",false));telegram.test(owner.id());
        verify(client).send(eq(TOKEN),eq("123456"),anyString());
        assertThat(telegram.view(owner.id()).success()).isFalse();
    }
    @Test void validatesInputAndCanRemoveTheStoredToken() {
        assertThatThrownBy(()->telegram.save(owner.id(),input("invalid",true))).hasMessageContaining("Token");
        assertThatThrownBy(()->telegram.save(owner.id(),input("",true))).hasMessageContaining("token");save();
        assertThatThrownBy(()->telegram.save(owner.id(),new TelegramNotifications.Input(true,"",false,"https://example.org",true,true,true,"es"))).hasMessageContaining("numérico");
        telegram.save(owner.id(),new TelegramNotifications.Input(false,"",true,"123456",false,true,true,"es"));
        assertThat(telegram.view(owner.id()).tokenConfigured()).isFalse();
        assertThatThrownBy(()->telegram.test(owner.id())).hasMessageContaining("Guarda primero");
    }
    @Test void botIdIsRejectedAsThePrivateChatIdBeforeSaving() {
        assertThatThrownBy(()->telegram.save(owner.id(),new TelegramNotifications.Input(true,TOKEN,false,"123456789",true,true,true,"es")))
            .hasMessageContaining("propio bot").hasMessageNotContaining(TOKEN);
        assertThat(telegram.view(owner.id()).tokenConfigured()).isFalse();
    }
    @Test void queuedMessagesAreGroupedSanitizedAndOnlySentByTheWorker() {
        save();notify("job",EventJournal.Outcome.STARTED);assertThat(queued()).isZero();
        notify("job",EventJournal.Outcome.PARTIAL);notify("job",EventJournal.Outcome.PARTIAL);
        assertThat(queued()).isEqualTo(1);verifyNoInteractions(client);
        telegram.deliver();verify(client).send(eq(TOKEN),eq("123456"),argThat(message->message.contains("5") && !message.contains(TOKEN)));
        assertThat(jdbc.queryForObject("SELECT state FROM telegram_deliveries",String.class)).isEqualTo("SENT");
        telegram.deliver();verify(client,times(1)).send(anyString(),anyString(),anyString());
    }
    @Test void eventsAndNotificationsRollbackTogether() {
        save();var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(status->{notify("rollback",EventJournal.Outcome.FAILED);status.setRollbackOnly();});
        assertThat(queued()).isZero();telegram.deliver();verifyNoInteractions(client);
    }
    @Test void scheduledSuccessCanNotifyWithoutAddingSuccessfulEvents() {
        save();long before=journal.cursor();
        journal.notifyCompletion(actor(),EventJournal.Category.JOB,"DOWNLOAD",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.SCHEDULED,"silent-job",Map.of("taskId","default-task","accepted",2));
        assertThat(journal.cursor()).isEqualTo(before);assertThat(queued()).isEqualTo(1);
    }
    @Test void preferencesAndOwnersAreIsolatedAndDeletionCascades() {
        save();var other=accounts.create("other","other@example.org","ADMIN",owner.id());
        assertThat(telegram.view(other.user().id()).tokenConfigured()).isFalse();
        journal.recordAs(new EventContext.Actor(other.user().id(),"other","USER"),EventJournal.Category.JOB,"DOWNLOAD",EventJournal.Outcome.FAILED,EventContext.Origin.MANUAL,"other-job",Map.of());
        assertThat(queued()).isZero();notify("owner-job",EventJournal.Outcome.FAILED);
        jdbc.update("DELETE FROM users WHERE id=?",owner.id());assertThat(queued()).isZero();
    }
    @Test void retriesKnownProviderFailuresAtMostThreeTimesAndNeverReplaysUncertainSends() {
        save();notify("retry",EventJournal.Outcome.FAILED);
        when(client.send(anyString(),anyString(),anyString())).thenReturn(new TelegramClient.Result(false,true,1));
        for(int i=0;i<3;i++) {jdbc.update("UPDATE telegram_deliveries SET next_attempt=0");telegram.deliver();}
        assertThat(jdbc.queryForObject("SELECT state FROM telegram_deliveries",String.class)).isEqualTo("FAILED");
        verify(client,times(3)).send(anyString(),anyString(),anyString());
        telegram.deliver();verifyNoMoreInteractions(client);
    }
    @Test void disablingOrChangingDestinationCancelsQueuedMessages() {
        save();notify("old",EventJournal.Outcome.FAILED);
        telegram.save(owner.id(),new TelegramNotifications.Input(false,"",false,"987654",true,true,true,"es"));
        assertThat(queued()).isZero();telegram.deliver();verifyNoInteractions(client);
    }
    @Test void revokedAccountCannotReceiveQueuedNotifications() {
        save();notify("permission",EventJournal.Outcome.FAILED);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?",owner.id());telegram.deliver();
        verifyNoInteractions(client);assertThat(telegram.view(owner.id()).failed()).isEqualTo(1);
    }
    @Test void pendingQueueIsBoundedAndOldMessagesExpire() {
        save();for(int i=0;i<105;i++) notify("job-"+i,EventJournal.Outcome.FAILED);
        assertThat(queued()).isEqualTo(100);
        jdbc.update("UPDATE telegram_deliveries SET created_at=?",Instant.now().minus(java.time.Duration.ofDays(2)).toEpochMilli());
        telegram.deliver();assertThat(telegram.view(owner.id()).pending()).isZero();verifyNoInteractions(client);
    }
    @Test void testDeliveryReportsOnlyASafeError() {
        save();when(client.send(anyString(),anyString(),anyString())).thenReturn(new TelegramClient.Result(false,false,0));
        assertThatThrownBy(()->telegram.test(owner.id())).hasMessageContaining("No se pudo enviar").hasMessageNotContaining(TOKEN);
    }
    @Test void restartDoesNotResendAnInterruptedRequest() {
        save();notify("interrupted",EventJournal.Outcome.FAILED);
        jdbc.update("UPDATE telegram_deliveries SET state='SENDING'");
        var restarted=new TelegramNotifications(jdbc,accounts,client,manager,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"workerEnabled",true);
        try {restarted.start();assertThat(telegram.view(owner.id()).failed()).isEqualTo(1);}
        finally {restarted.close();}
        verifyNoInteractions(client);
    }
    @Test void aChangedDestinationCannotReceiveAnAlreadyClaimedMessage() {
        save();notify("claimed",EventJournal.Outcome.FAILED);
        jdbc.update("UPDATE telegram_deliveries SET state='SENDING'");
        telegram.save(owner.id(),new TelegramNotifications.Input(true,"",false,"987654",true,true,true,"es"));
        jdbc.update("UPDATE telegram_deliveries SET state='PENDING'");telegram.deliver();
        verifyNoInteractions(client);assertThat(telegram.view(owner.id()).failed()).isEqualTo(1);
    }
    @Test void anotherAccountCannotReadOrSendUsingTheseCredentials() throws Exception {
        save();var other=accounts.create("reader","reader@example.org","USER",owner.id());
        accounts.changePassword(accounts.find(other.user().id()),other.password(),"password123");
        var auth=UsernamePasswordAuthenticationToken.authenticated(accounts.find(other.user().id()).authenticatedNow(),null,List.of());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/telegram?userId="+owner.id()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.tokenConfigured").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/telegram/test?userId="+owner.id()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verifyNoInteractions(client);
    }
    @Test void suppressedScheduledSuccessNotifiesOnlyWhenWorkHasActuallyFinished() {
        save();long before=journal.cursor();
        EventContext.withActor(actor(),()->EventContext.withOrigin(EventContext.Origin.SCHEDULED,()->journal.runFailuresOnly(EventJournal.Category.JOB,"SCHEDULED_TASK",Map.of("taskId","default-task"),()->Map.<String,Object>of("checked",1),value->value)));
        assertThat(journal.cursor()).isEqualTo(before);assertThat(queued()).isEqualTo(1);
        EventContext.withActor(actor(),()->journal.runFailuresOnly(EventJournal.Category.JOB,"SCHEDULED_TASK",Map.of("taskId","default-task"),()->Map.<String,Object>of("cycle","new-cycle","pending",1),value->value));
        assertThat(queued()).isEqualTo(1);
    }
    @Test void lostEncryptionKeyCannotSilentlyEnableNotifications() {
        save();var lost=new TelegramNotifications(jdbc,accounts,client,manager,"");
        try {assertThatThrownBy(()->lost.save(owner.id(),input("",true))).hasMessageContaining("EPLSYNC_SECRET_KEY");}
        finally {lost.close();}
    }
    @Test void apiUsesTheSessionOwnerAndDoesNotReturnTokens() throws Exception {
        var auth=UsernamePasswordAuthenticationToken.authenticated(owner,null,List.of());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        save();mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/telegram").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.tokenConfigured").value(true))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.token").doesNotExist());
    }
}
