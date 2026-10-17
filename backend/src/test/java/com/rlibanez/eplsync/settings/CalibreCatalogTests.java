package com.rlibanez.eplsync.settings;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.security.require-https=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class CalibreCatalogTests {
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired CalibreSettings settings;
    @Autowired CalibreCatalogService snapshots;
    @Autowired CalibreTransfers transfers;
    @Autowired com.rlibanez.eplsync.maintenance.DatabaseResetService reset;
    @MockitoBean CalibreClient client;
    MockMvc mvc;
    @BeforeEach void initialize() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM app_settings WHERE setting_key IN ('calibre.connection','calibre.snapshot')");
        jdbc.update("DELETE FROM calibre_books");
        jdbc.update("DELETE FROM catalog_books");
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
    }
    @Test void syncsWaitForTransfersAndDoNotOverlapOrPauseThem() throws Exception {
        var active=new java.util.concurrent.atomic.AtomicInteger();var maximum=new java.util.concurrent.atomic.AtomicInteger();
        when(client.books(any(),eq(0),eq(100))).thenAnswer(call->{maximum.accumulateAndGet(active.incrementAndGet(),Math::max);try {Thread.sleep(20);return new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100);}finally {active.decrementAndGet();}});
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var transfer=executor.submit(()->transfers.preparing(()->{entered.countDown();try {assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}return null;}));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var first=executor.submit(()->snapshots.sync());var second=executor.submit(()->snapshots.sync());
            try {assertThatThrownBy(()->first.get(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);verify(client,never()).books(any(),anyInt(),anyInt());}
            finally {release.countDown();}
            transfer.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).total()).isEqualTo(1);
            assertThat(second.get(5,java.util.concurrent.TimeUnit.SECONDS).total()).isEqualTo(1);
            assertThat(maximum.get()).isEqualTo(1);
        }
    }
    @Test void syncFinalSummaryUsesInfoForBothOriginsAndDoesNotAnnounceFailures() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CalibreCatalogService.class);
        var previous=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100));
            for(var origin:List.of(com.rlibanez.eplsync.events.EventContext.Origin.MANUAL,com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED)) {
                capture.list.clear();
                com.rlibanez.eplsync.events.EventContext.withOrigin(origin,snapshots::sync);
                assertThat(capture.list).singleElement().satisfies(e->{
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                    assertThat(e.getFormattedMessage()).contains(origin==com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED?"programada":"manual").contains("biblioteca=main", "total=1", "identificados=1", "duraciónMs=");
                });
            }
            capture.list.clear();
            when(client.books(any(),eq(0),eq(100))).thenThrow(new com.rlibanez.eplsync.exception.UserInputException("Sin conexión"));
            assertThatThrownBy(snapshots::sync).hasMessage("Sin conexión");
            assertThat(capture.list).isEmpty();
        } finally {logger.detachAppender(capture);capture.stop();logger.setLevel(previous);}
    }
    @Test @WithMockUser(authorities={"ROLE_ADMIN", "CALIBRE_READ", "CALIBRE_SYNC"}) void missingSnapshotReturnsJsonInsteadOfAnEmptyBody() throws Exception {
        mvc.perform(get("/api/settings/calibre/snapshot")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(content().json("{\"snapshot\":null}"));
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100));
        snapshots.sync();
        mvc.perform(get("/api/settings/calibre/snapshot")).andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot.total").value(1)).andExpect(jsonPath("$.snapshot.library").value("main"));
    }
    @Test @WithMockUser(authorities={"CATALOG_READ", "ROLE_ADMIN", "CALIBRE_READ", "CALIBRE_SYNC"}) void publishesCompleteLibraryForIndividualBooks() throws Exception {
        for(int id=1;id<=5;id++) jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(?,2,?,'Author')",id,"Book "+id);
        var items=List.of(book(1,1L,1d),book(2,1L,1.5),book(3,2L,2d),book(4,3L,3d),book(5,4L,null),book(6,null,null));
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(items,6,0,100));
        mvc.perform(post("/api/settings/calibre/sync").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/calibre/sync").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(6)).andExpect(jsonPath("$.identified").value(5));
        mvc.perform(get("/api/catalog/books/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.calibre.totalItems").value(2)).andExpect(jsonPath("$.calibre.revision").value(1.5))
            .andExpect(jsonPath("$.calibre.items[0].calibreId").value(2)).andExpect(jsonPath("$.calibre.url").doesNotExist())
            .andExpect(jsonPath("$.calibre.username").doesNotExist());
        mvc.perform(get("/api/catalog/books").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(5))
            .andExpect(jsonPath("$.items[0].calibre").isEmpty());
        verify(client,times(1)).books(any(),eq(0),eq(100));
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"other"));
        assertThat(snapshots.snapshot()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Integer.class)).isZero();
    }
    @Test void failureRetainsPublishedSnapshotAndCleansStaging() {
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100));
        var previous=snapshots.sync();
        var first=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(id -> book(id,(long)id,1d)).toList();
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(first,101,0,100));
        when(client.books(any(),eq(1),eq(100))).thenThrow(new com.rlibanez.eplsync.exception.UserInputException("Connection failed"));
        assertThatThrownBy(snapshots::sync).hasMessage("Connection failed");
        assertThat(snapshots.snapshot()).isEqualTo(previous);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books",Integer.class)).isEqualTo(1);
        when(client.books(any(),eq(1),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(101,1L,2d)),102,1,100));
        assertThatThrownBy(snapshots::sync).hasMessageContaining("cambió");
        assertThat(snapshots.snapshot()).isEqualTo(previous);
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(),0,0,100));
        assertThat(snapshots.sync().total()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books",Integer.class)).isZero();
    }
    @Test @WithMockUser(authorities={"CATALOG_READ", "CALIBRE_READ"}) void limitsCopiesWithoutLosingTheLatestRevision() throws Exception {
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(1,30,'Book','Author')");
        var books=java.util.stream.IntStream.rangeClosed(1,25).mapToObj(id -> book(id,1L,(double)id)).toList();
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(books,25,0,100));
        snapshots.sync();
        mvc.perform(get("/api/catalog/books/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.calibre.totalItems").value(25)).andExpect(jsonPath("$.calibre.revision").value(25))
            .andExpect(jsonPath("$.calibre.items.length()").value(20)).andExpect(jsonPath("$.calibre.items[0].calibreId").value(25));
    }
    @Test void changedConnectionCannotPublishDataFromThePreviousLibrary() {
        when(client.books(any(),eq(0),eq(100))).thenAnswer(invocation -> {
            settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"different"));
            return new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100);
        });
        assertThatThrownBy(snapshots::sync).hasMessageContaining("conexión de Calibre cambió");
        assertThat(snapshots.snapshot()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books",Integer.class)).isZero();
    }
    @Test void rejectsDuplicateAndOversizedLibraries() {
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d),book(1,2L,2d)),2,0,100));
        assertThatThrownBy(snapshots::sync).hasMessageContaining("duplicados");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books",Integer.class)).isZero();
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(),500001,0,100));
        assertThatThrownBy(snapshots::sync).hasMessageContaining("500000");
    }
    @Test @WithMockUser(authorities={"CATALOG_READ", "CALIBRE_READ"}) void readersSeeLocalMetadataWithoutAccessToTheConnection() throws Exception {
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(1,2,'Book','Author')");
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100));
        snapshots.sync();
        mvc.perform(get("/api/catalog/books/1")).andExpect(status().isOk()).andExpect(jsonPath("$.calibre.revision").value(1));
        mvc.perform(get("/api/settings/calibre")).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/calibre/sync").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void publishesMultiplePagesTogetherAndRejectsConcurrentSync() {
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100));
        var previous=snapshots.sync();
        var first=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(id -> book(id,(long)id,1d)).toList();
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(first,101,0,100));
        when(client.books(any(),eq(1),eq(100))).thenAnswer(invocation -> {
            assertThat(snapshots.snapshot()).isEqualTo(previous);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Integer.class)).isEqualTo(1);
            assertThatThrownBy(snapshots::sync).hasMessageContaining("en curso");
            return new CalibreClient.Books(List.of(book(101,1L,2d)),101,1,100);
        });
        assertThat(snapshots.sync().total()).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Integer.class)).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=false",Integer.class)).isZero();
    }
    @Test void resettingTheDatabaseClearsTheSnapshotButPreservesTheConnection() {
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d)),1,0,100));
        snapshots.sync();
        reset.reset(false);
        assertThat(snapshots.snapshot()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books",Integer.class)).isZero();
        assertThat(settings.view().library()).isEqualTo("main");
    }
    private static CalibreClient.Book book(long id, Long eplId, Double revision) {
        return new CalibreClient.Book(id,"Book",List.of("Author"),eplId,revision==null?null:java.math.BigDecimal.valueOf(revision),eplId!=null&&revision!=null);
    }

    @Test @WithMockUser(authorities={"CALIBRE_READ"}) void updatesUseHighestRevisionPerEplAndIgnoreAlreadyCurrentBooks() throws Exception {
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(1,3,'One','Author'),(2,2,'Two','Author'),(3,2,'Three','Author')");
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d),book(2,1L,2d),book(3,1L,2d),book(4,2L,1d),book(5,2L,2d),book(6,3L,null),book(7,null,1d),book(8,99L,1d)),8,0,100));
        snapshots.sync();clearInvocations(client);
        mvc.perform(get("/api/calibre/books").param("updatesOnly","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].calibreId").value(2)).andExpect(jsonPath("$.items[0].revision").value(2)).andExpect(jsonPath("$.items[0].catalogRevision").value(3));
        mvc.perform(get("/api/calibre/books").param("updatesOnly","true").param("eplId","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(0));
        verifyNoInteractions(client);
    }
    @Test @WithMockUser(authorities={"CALIBRE_READ"}) void libraryFiltersSortsAndPaginatesWithoutCatalogPermission() throws Exception {
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(1,2,'Catalog title','Author')");
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(1,1L,1d),book(2,1L,2d),book(3,99L,1d),book(4,null,null)),4,0,100));
        snapshots.sync();
        jdbc.update("UPDATE calibre_books SET title=CASE WHEN calibre_id=1 THEN '100%_ Libro' ELSE 'Otro' END,authors=CASE WHEN calibre_id=2 THEN 'Asimov' ELSE 'Herbert' END");
        mvc.perform(get("/api/calibre/books").param("title","100%_").param("titleOperator","ne").param("author","Asimov").param("authorOperator","ne").param("size","1").param("page","1").param("sort","calibreId,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2)).andExpect(jsonPath("$.items[0].calibreId").value(4));
        mvc.perform(get("/api/calibre/books").param("authorOperator","invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/calibre/books").param("size","1").param("sort","calibreId,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(4)).andExpect(jsonPath("$.items[0].calibreId").value(4));
        mvc.perform(get("/api/calibre/books").param("comparison","OUTDATED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].catalogRevision").value(2));
        mvc.perform(get("/api/calibre/books").param("comparison","OUTDATED,CURRENT").param("presence","PRESENT,MISSING"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
        mvc.perform(get("/api/calibre/books").param("presence","MISSING,UNIDENTIFIED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
        mvc.perform(get("/api/calibre/books").param("eplId","1").param("eplIdOperator","ne").param("size","1").param("page","1").param("sort","calibreId,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2)).andExpect(jsonPath("$.items[0].calibreId").value(4));
        mvc.perform(get("/api/calibre/books").param("comparison","OUTDATED,CURRENT").param("comparisonOperator","ne"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].calibreId",org.hamcrest.Matchers.containsInAnyOrder(3,4)));
        mvc.perform(get("/api/calibre/books").param("presence","MISSING,UNIDENTIFIED").param("presenceOperator","ne"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].calibreId",org.hamcrest.Matchers.containsInAnyOrder(1,2)));
        mvc.perform(get("/api/calibre/books").param("eplId","99").param("eplIdOperator","ne").param("comparison","CURRENT").param("comparisonOperator","ne").param("presence","PRESENT").param("presenceOperator","ne"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].calibreId").value(4));
        mvc.perform(get("/api/calibre/books").param("eplIdOperator","ne").param("comparisonOperator","ne").param("presenceOperator","ne"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(4));
        for(String operator:java.util.List.of("eplIdOperator","comparisonOperator","presenceOperator"))
            mvc.perform(get("/api/calibre/books").param(operator,"invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/calibre/books").param("comparison","CURRENT,invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/calibre/books").param("presence","PRESENT,")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/calibre/books").param("presence","MISSING"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eplId").value(99));
        mvc.perform(get("/api/calibre/books").param("eplId","1").param("size","1000").param("sort","authors,asc","title,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
        mvc.perform(get("/api/calibre/books").param("author","missing author"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/calibre/books").param("title","not present"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/calibre/books").param("sort","title; DROP TABLE calibre_books,asc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/calibre/books").param("size","1001")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/calibre/snapshot")).andExpect(status().isOk());
        mvc.perform(post("/api/settings/calibre/sync").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/books/1")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ"}) void catalogReadersCannotSeeCalibreWithoutItsPermission() throws Exception {
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(1,2,'Book','Author')");
        mvc.perform(get("/api/calibre/books")).andExpect(status().isForbidden());
        mvc.perform(get("/api/calibre/books/1/open")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/books/1")).andExpect(status().isOk()).andExpect(jsonPath("$.calibre").isEmpty());
    }
    @Test @WithMockUser(authorities={"CALIBRE_READ","CALIBRE_SYNC"}) void synchronizersCannotReadCredentials() throws Exception {
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(),0,0,100));
        mvc.perform(post("/api/settings/calibre/sync")).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/calibre/sync").with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/settings/calibre")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(authorities={"CALIBRE_READ"}) void opensTheExactCopyWithoutCredentialsAndPreservesSnapshotWhenBrowserUrlChanges() throws Exception {
        when(client.books(any(),eq(0),eq(100))).thenReturn(new CalibreClient.Books(List.of(book(3,1L,1d)),1,0,100));
        snapshots.sync();
        mvc.perform(get("/api/calibre/books/3/open")).andExpect(status().isFound())
            .andExpect(header().string("Location","http://localhost:8080/#book_id=3&library_id=main&panel=book_details"));
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main","https://books.example/calibre/"));
        assertThat(snapshots.snapshot()).isNotNull();
        mvc.perform(get("/api/calibre/books/3/open")).andExpect(status().isFound())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Location","https://books.example/calibre/#book_id=3&library_id=main&panel=book_details"));
        mvc.perform(get("/api/calibre/books/99/open")).andExpect(status().isNotFound());
        assertThatThrownBy(()->settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main","https://user:secret@books.example")))
            .isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
}
