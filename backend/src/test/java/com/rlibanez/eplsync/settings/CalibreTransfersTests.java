package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(authorities={"CALIBRE_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","CALIBRE_JOBS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class CalibreTransfersTests {
    @Autowired CalibreTransfers transfers;
    @Autowired CalibreSettings settings;
    @Autowired CalibreCatalogService catalog;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @MockitoSpyBean AccountStore accounts;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    Account owner;String record;byte[] bytes;MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM app_settings WHERE setting_key IN ('calibre.connection','calibre.snapshot')");downloads.deleteAll();accounts.clear();
        owner=accounts.initialize("Owner","owner@example.com","password123","password123");
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        var row=new DownloadRecord();row.setEplId(1L);row.setRevision(1.1);row.setHash("A".repeat(40));row.setClient("qbittorrent");row.setClientInstanceId(tracking.instanceId());row.setStatus(DownloadStatus.DOWNLOADED);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());record=downloads.save(row).getId();
        bytes=epub(1,"1.10");
        when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main"),new CalibreClient.Library("other","Other")),"main"));
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(files.download(record,0)).thenAnswer(call -> new EpubFiles.Opened("book.epub",bytes.length,new java.io.ByteArrayInputStream(bytes)));
        when(client.findRevision(any(),anyLong(),any())).thenReturn(null);
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenReturn(9L);
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void jobsFilterByUsernameAndState() {
        String now=Instant.now().toString();
        for(String name:List.of("Roberto","Otra cuenta")) jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),now,now,owner.id(),name,"destination","main","source",name.equals("Roberto") ? "COMPLETED" : "QUEUED");
        assertThat(transfers.jobs(0,20,"COMPLETED"," rober ",List.of("id,asc")).items()).extracting(job -> job.username()).containsExactly("Roberto");
        assertThat(transfers.jobs(0,20,"QUEUED","Roberto",List.of("createdAt,desc")).meta().totalItems()).isZero();
    }
    @Test void jobsFilterByCreationDates() {
        String id=UUID.randomUUID().toString(),created="2026-10-13T12:00:00Z";
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,?)",id,created,created,owner.id(),"Owner","destination","main","source","COMPLETED");
        assertThat(transfers.jobs(0,20,"","",Instant.parse(created),Instant.parse("2026-10-14T00:00:00Z"),List.of("createdAt,desc")).meta().totalItems()).isEqualTo(1);
        assertThat(transfers.jobs(0,20,"","",null,Instant.parse(created),List.of("createdAt,desc")).meta().totalItems()).isZero();
        assertThatThrownBy(() -> transfers.jobs(0,20,"","",Instant.parse(created),Instant.parse(created),List.of("createdAt,desc"))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    static byte[] epub(long epl,String revision) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(output)) {
            for(var entry:Map.of("mimetype","application/epub+zip","META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>","content.opf","<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:identifier>epl:"+epl+"</dc:identifier><dc:identifier>revision:"+revision+"</dc:identifier></metadata></package>").entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        } return output.toByteArray();
    }
    static byte[] withoutIdentifiers(boolean missingId,boolean missingRevision) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        try(var input=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(epub(1,"1.10")));var zip=new java.util.zip.ZipOutputStream(output)) {
            java.util.zip.ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                byte[] content=input.readAllBytes();
                if(entry.getName().equals("content.opf")) {
                    String xml=new String(content,java.nio.charset.StandardCharsets.UTF_8);
                    if(missingId) xml=xml.replace("<dc:identifier>epl:1</dc:identifier>","");
                    if(missingRevision) xml=xml.replace("<dc:identifier>revision:1.10</dc:identifier>","");
                    content=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(content);zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,false,EPUB enviado sin EPL ID","false,true,EPUB enviado sin revisión","true,true,EPUB enviado sin EPL ID ni revisión"})
    void sendsWithoutOptionalIdentifiers(boolean missingId,boolean missingRevision,String message) throws Exception {
        bytes=withoutIdentifiers(missingId,missingRevision);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Libro",List.of("Autor"),missingId?null:1L,missingRevision?null:new java.math.BigDecimal("1.1"),false));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).isEqualTo(message);
        verify(client,never()).findRevision(any(),anyLong(),any());
        verify(client).uploadEpub(any(),any(),anyString(),anyString());
    }
    CalibreTransfers.Job create() {
        return EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),() -> transfers.create(new CalibreTransfers.Request("main",List.of(new CalibreTransfers.FileChoice(record,0)))));
    }
    @Test void emptyJobsCanBeReadWithDefaultAndExplicitOrdering() throws Exception {
        mvc.perform(get("/api/calibre/transfers")).andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/calibre/transfers").param("sort","createdAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(0));
    }
    @Test void apiPreservesCommaSeparatedAndRepeatedSortCriteria() throws Exception {
        var job=create();
        mvc.perform(get("/api/calibre/transfers").param("sort","createdAt,desc","username,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/calibre/transfers")).andExpect(status().isOk());
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items").param("sort","message,asc","position,asc","revision,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items")).andExpect(status().isOk());
        mvc.perform(get("/api/calibre/transfers").param("sort","invalid,asc"))
            .andExpect(status().isBadRequest());
    }
    @Test void sendsAndPublishesVerifiedBookWithPersistentOwnerAndEvent() {
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items()).singleElement().satisfies(item -> assertThat(item.calibreId()).isEqualTo(9));
        verify(client).uploadEpub(any(),any(),eq("book.epub"),anyString());
        assertThat(catalog.snapshot().complete()).isFalse();assertThat(catalog.snapshot().total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE action='CALIBRE_SEND_FILE'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT operation_id) FROM app_events WHERE action='CALIBRE_SEND' AND actor_id=?",Long.class,owner.id())).isEqualTo(1);
    }
    @Test void twentyFiveFilesHaveOneGeneralEventOperationAndNoIndividualEvents() {
        var choices=new java.util.ArrayList<CalibreTransfers.FileChoice>();
        var available=new java.util.ArrayList<EpubFiles.Item>();
        for(int index=0;index<25;index++) {
            choices.add(new CalibreTransfers.FileChoice(record,index));
            available.add(new EpubFiles.Item(index,"book.epub",bytes.length));
        }
        when(files.list(record)).thenReturn(available);
        when(files.download(eq(record),anyInt())).thenAnswer(call -> new EpubFiles.Opened("book.epub",bytes.length,new java.io.ByteArrayInputStream(bytes)));
        var job=EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),
            () -> transfers.create(new CalibreTransfers.Request("main",choices)));
        for(int index=0;index<25;index++) transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT operation_id) FROM app_events WHERE actor_id=? AND action='CALIBRE_SEND'",Long.class,owner.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE actor_id=? AND action='CALIBRE_SEND_FILE'",Long.class,owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=? AND outcome='SUCCEEDED'",Long.class,job.id())).isEqualTo(1);
    }
    @Test void preparationIdentifiesTheInaccessibleRecordInsteadOfAnAnonymousError() {
        when(files.list(record)).thenThrow(new com.rlibanez.eplsync.exception.TorrentOperationException(org.springframework.http.HttpStatus.CONFLICT,"No se puede leer el EPUB; revisa el montaje y los permisos de la carpeta"));
        assertThatThrownBy(() -> transfers.prepare(List.of(record)))
            .isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class)
            .hasMessageContaining("EPL ID 1").hasMessageContaining("revisión 1.1").hasMessageContaining("No se puede leer el EPUB");
        assertThat(transfers.jobs(0,20,"",List.of("createdAt,desc")).meta().totalItems()).isZero();
    }
    @Test void sizeWarningSurvivesTheSuccessfulTransferResult() {
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length,bytes.length+10)));
        assertThat(transfers.prepare(List.of(record)).files().getFirst().expectedSize()).isEqualTo(bytes.length+10);
        var job=create();
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("El tamaño del EPUB no coincide");
        transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("EPUB enviado y verificado","El tamaño del EPUB no coincide");
    }
    @Test void skipsLiveDuplicateWithoutUploading() {
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).skipped()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void refusesMismatchedEpubIdentity() throws Exception {
        bytes=epub(2,"1.10");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).failed()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void uncertainUploadWithoutIdentifiersCannotBeMatchedToAnotherRevisionRecord() throws Exception {
        bytes=withoutIdentifiers(true,true);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();
        when(client.findRevision(any(),anyLong(),any())).thenReturn(9L);
        transfers.control(job.id(),"resume");transfers.cycle();
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        verify(client,never()).findRevision(any(),anyLong(),any());
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void ambiguousUploadIsNeverAutomaticallyRepeatedAndCanBeVerified() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void pausesBeforeAnotherDestinationOrRevokedPermissions() {
        var job=create();settings.save(new CalibreSettings.Input("http://another:8080","","",true,"main"));
        transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        doReturn(new Account(owner.id(),owner.username(),owner.email(),"USER","ACTIVE",false,null,owner.securityVersion(),Set.of(Permission.CALIBRE_READ),null)).when(accounts).find(owner.id());
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void pauseAndCancelDoNotSendPendingFiles() {
        var job=create();transfers.control(job.id(),"pause");transfers.cycle();verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        transfers.control(job.id(),"cancel");transfers.cycle();assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("CANCELLED");
    }
    @Test void cancelAllowsTheAlreadyStartedUploadToFinishAndRecordsItsResult() {
        var job=create();
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenAnswer(invocation -> {
            transfers.control(job.id(),"cancel");
            return 9L;
        });
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("CANCELLED");
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        transfers.cycle();
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void cancelledUncertainUploadCanStillBeVerified() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();transfers.control(job.id(),"cancel");
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void rejectsInvalidSelectionBeforeCreatingJob() {
        assertThatThrownBy(() -> transfers.create(new CalibreTransfers.Request("main",List.of(new CalibreTransfers.FileChoice(record,0),new CalibreTransfers.FileChoice(record,0))))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(transfers.jobs(0,20,"",List.of("createdAt,desc")).meta().totalItems()).isZero();
    }
    @Test @WithMockUser(authorities="CALIBRE_READ") void readerCannotReadJobsOrSubmitOrControl() throws Exception {
        mvc.perform(get("/api/calibre/transfers")).andExpect(status().isForbidden());
        mvc.perform(post("/api/calibre/transfers/prepare").with(csrf()).contentType("application/json").content("{\"ids\":[\""+record+"\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/calibre/transfers/id/cancel").with(csrf())).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities="CALIBRE_SEND") void senderAlsoNeedsSourceFilesPermissionAndCsrf() throws Exception {
        mvc.perform(post("/api/calibre/transfers/prepare").with(csrf()).contentType("application/json").content("{\"ids\":[\""+record+"\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/calibre/transfers").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void databaseResetClearsJobsAndCascadeItems() {
        create();transfers.exclusive(() -> {jdbc.update("DELETE FROM calibre_jobs");return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Long.class)).isZero();
    }
    @Test void retentionPreservesUncertainCancelledTransfers() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();transfers.control(job.id(),"cancel");jdbc.update("UPDATE calibre_jobs SET updated_at=?",Instant.now().minus(java.time.Duration.ofDays(100)).toString());
        transfers.retention();assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
    }
}
