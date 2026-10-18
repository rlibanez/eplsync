package com.rlibanez.eplsync.config;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2", "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false"})
class SqliteConcurrencyTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"+directory.resolve("concurrent.db"));
    }
    @Autowired DataSource source;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.rlibanez.eplsync.settings.ServerSettings settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.rlibanez.eplsync.importer.CatalogBookCsvImporter importer;
    @Autowired com.rlibanez.eplsync.repository.CatalogBookRepository books;
    @Autowired com.rlibanez.eplsync.events.EventJournal events;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore jobs;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.rlibanez.eplsync.service.TorrentClientService client;
    @BeforeEach void table() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS concurrency_probe(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
        jdbc.update("DELETE FROM concurrency_probe");jdbc.update("INSERT INTO concurrency_probe VALUES(1,0)");
    }
    private TransactionTemplate transaction(boolean readOnly) {
        var tx=new TransactionTemplate(manager);tx.setReadOnly(readOnly);return tx;
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    }
    @Test void idleQueueWakesOnlyForCommittedChanges() {
        var probe=new WorkerActivity.Probe();probe.empty(probe.snapshot());
        jdbc.update("UPDATE concurrency_probe SET value=2 WHERE id=99");
        assertThat(probe.ready()).isFalse();
        transaction(false).executeWithoutResult(status->{
            jdbc.update("UPDATE concurrency_probe SET value=2 WHERE id=1");
            assertThat(probe.ready()).isFalse();status.setRollbackOnly();
        });
        assertThat(probe.ready()).isFalse();
        transaction(false).executeWithoutResult(status->{
            jdbc.update("UPDATE concurrency_probe SET value=3 WHERE id=1");
            assertThat(probe.ready()).isFalse();
        });
        assertThat(probe.ready()).isTrue();
        probe.empty(probe.snapshot());
        jdbc.update("UPDATE concurrency_probe SET value=4 WHERE id=1");
        assertThat(probe.ready()).isTrue();
    }
    @Test void flushedJpaChangesWakeQueuesAfterCommitAndNotAfterRollback() {
        var probe=new WorkerActivity.Probe();probe.empty(probe.snapshot());
        var record=new com.rlibanez.eplsync.torrent.downloads.DownloadRecord();
        record.setHash("ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD");
        record.setEplId(123L);record.setRevision(1.0);record.setClient("qbittorrent");record.setClientInstanceId("probe");
        record.setStatus(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.DOWNLOADING);
        record.setOrigin(com.rlibanez.eplsync.torrent.downloads.DownloadRecord.Origin.EPLSYNC);record.setCreatedAt(java.time.Instant.now());
        transaction(false).executeWithoutResult(status->{
            var em=org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            em.persist(record);em.flush();assertThat(probe.ready()).isFalse();
        });
        assertThat(probe.ready()).isTrue();probe.empty(probe.snapshot());
        transaction(false).executeWithoutResult(status->{
            var em=org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            var managed=em.find(com.rlibanez.eplsync.torrent.downloads.DownloadRecord.class,record.getId());
            managed.setRevision(2.0);em.flush();assertThat(probe.ready()).isFalse();status.setRollbackOnly();
        });
        assertThat(probe.ready()).isFalse();
        transaction(false).executeWithoutResult(status->{
            var em=org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            em.remove(em.find(com.rlibanez.eplsync.torrent.downloads.DownloadRecord.class,record.getId()));em.flush();
        });
        assertThat(probe.ready()).isTrue();
    }
    @Test void applicationDebugDoesNotEnableSqlTracing() {
        var app=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.rlibanez.eplsync");
        var sql=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CoordinatedJdbcTemplate.class);
        var before=app.getLevel();
        try {
            app.setLevel(ch.qos.logback.classic.Level.DEBUG);
            assertThat(sql.isDebugEnabled()).isFalse();
            sql.setLevel(ch.qos.logback.classic.Level.DEBUG);
            assertThat(sql.isDebugEnabled()).isTrue();
        } finally {app.setLevel(before);sql.setLevel(ch.qos.logback.classic.Level.INFO);}
    }
    @Test void onlyLockContentionIsDeferred() {
        for(int code:new int[]{5,6,517,262}) assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(
                new IllegalStateException(new java.sql.SQLException("test","",code)))).isTrue();
        for(int code:new int[]{10,19,13}) assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(
                new java.sql.SQLException("test","",code))).isFalse();
        assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(new com.rlibanez.eplsync.ordering.SqliteContention.WriterBusy())).isTrue();
        assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(new IllegalStateException("other"))).isFalse();
    }
    @Test void everyConnectionUsesWalFullBusyTimeoutAndForeignKeys() throws Exception {
        try(var first=source.getConnection();var second=source.getConnection()) {
            for(var connection:java.util.List.of(first,second)) try(var statement=connection.createStatement()) {
                try(var result=statement.executeQuery("PRAGMA journal_mode")) {assertThat(result.getString(1)).isEqualTo("wal");}
                try(var result=statement.executeQuery("PRAGMA synchronous")) {assertThat(result.getInt(1)).isEqualTo(2);}
                try(var result=statement.executeQuery("PRAGMA busy_timeout")) {assertThat(result.getInt(1)).isEqualTo(10000);}
                try(var result=statement.executeQuery("PRAGMA foreign_keys")) {assertThat(result.getInt(1)).isEqualTo(1);}
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO user_permission_overrides(user_id,permission,effect) "
                    + "VALUES('nonexistent-fk-user','CATALOG_READ','ALLOW')"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("FOREIGN KEY");
            }
        }
    }
    @Test void readerSeesCommittedDataWhileWriterHasUncommittedChanges() throws Exception {
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var writer=executor.submit(() -> transaction(false).executeWithoutResult(status -> {
                jdbc.update("UPDATE concurrency_probe SET value=1 WHERE id=1");written.countDown();await(release);
            }));
            try {await(written);Integer visible=transaction(true).execute(status -> jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class));assertThat(visible).isZero();}
            finally {release.countDown();}
            writer.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class)).isEqualTo(1);
        }
    }
    @Test void concurrentReadModifyWriteTransactionsDoNotLoseUpdates() throws Exception {
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> transaction(false).executeWithoutResult(status -> {
                int value=jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class);
                read.countDown();await(release);jdbc.update("UPDATE concurrency_probe SET value=?",value+1);
            }));
            await(read);
            var second=executor.submit(() -> {secondStarted.countDown();transaction(false).executeWithoutResult(status -> {
                int value=jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class);
                jdbc.update("UPDATE concurrency_probe SET value=?",value+1);
            });});
            try {await(secondStarted);Thread.sleep(100);} finally {release.countDown();}
            first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class)).isEqualTo(2);
        }
    }
    @Test void autocommitCalibreWriteCannotInvalidateTorrentReadModifyWrite() throws Exception {
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var torrent=executor.submit(() -> transaction(false).executeWithoutResult(status -> {
                int value=jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class);
                read.countDown();await(release);
                jdbc.update("UPDATE concurrency_probe SET value=?",value+1);
            }));
            await(read);
            var calibre=executor.submit(() -> {
                attempted.countDown();jdbc.update("UPDATE concurrency_probe SET value=value+1");
            });
            try {
                await(attempted);
                // The old DEFERRED transaction let this commit invalidate its snapshot.
                assertThatThrownBy(() -> calibre.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {release.countDown();}
            torrent.get(5,TimeUnit.SECONDS);calibre.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class)).isEqualTo(2);
        }
    }
    @Test void queuedAutocommitWritesDoNotTakeTheReadersConnection() throws Exception {
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var writer=executor.submit(()->transaction(false).executeWithoutResult(status->{jdbc.update("UPDATE concurrency_probe SET value=1");written.countDown();await(release);}));
            await(written);
            var waiting=new java.util.ArrayList<Future<?>>();
            try {
                for(int n=0;n<6;n++) waiting.add(executor.submit(()->jdbc.update("UPDATE concurrency_probe SET value=value+1")));
                Thread.sleep(50);
                for(int n=0;n<4;n++) {
                    var reader=executor.submit(()->transaction(true).execute(status->jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class)));
                    assertThat(reader.get(1,TimeUnit.SECONDS)).isZero();
                }
                assertThat(waiting).noneMatch(pending -> pending.isDone());
            } finally {release.countDown();}
            writer.get(5,TimeUnit.SECONDS);for(var pending:waiting) pending.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class)).isEqualTo(7);
        }
    }
    @Test void settingsWaitForTheWriterBeforeTakingTheirMonitor() throws Exception {
        var entered=new CountDownLatch(1);var queued=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->transaction(false).executeWithoutResult(status->{
                entered.countDown();await(queued);
                try {Thread.sleep(50);}catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                assertThat(settings.updateSearchContext()).isNotBlank();
            }));
            await(entered);
            var second=executor.submit(()->{queued.countDown();return settings.updateSearchContext();});
            first.get(3,TimeUnit.SECONDS);assertThat(second.get(3,TimeUnit.SECONDS)).isNotBlank();
        }
    }
    @Test void committedDataSurvivesReopeningAndRollbackDiscardsChanges() throws Exception {
        transaction(false).executeWithoutResult(status -> jdbc.update("UPDATE concurrency_probe SET value=7"));
        transaction(false).executeWithoutResult(status -> {jdbc.update("UPDATE concurrency_probe SET value=99");status.setRollbackOnly();});
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("concurrent.db"));var statement=connection.createStatement();var result=statement.executeQuery("SELECT value FROM concurrency_probe")) {
            assertThat(result.getInt(1)).isEqualTo(7);
        }
        assertThat(jdbc.queryForObject("PRAGMA integrity_check",String.class)).isEqualTo("ok");
    }
    @Test void importQueriesEventsAndJobWritesCanOverlap() throws Exception {
        var csv=directory.resolve("catalog.csv");
        try(var output=Files.newBufferedWriter(csv)) {
            output.write("EPL Id,Revisión,Autor,Título\n");
            for(int id=1;id<=2000;id++) output.write(id+",1,Autor,Título "+id+"\n");
        }
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var imported=executor.submit(() -> {await(start);return importer.importFile(csv,true);});
            var journal=executor.submit(() -> {await(start);for(int i=0;i<10;i++) events.record(
                com.rlibanez.eplsync.events.EventJournal.Category.JOB,"DOWNLOAD",
                com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED,
                com.rlibanez.eplsync.events.EventContext.Origin.MANUAL,"wal-"+i,java.util.Map.of());});
            var submitted=executor.submit(() -> {await(start);return jobs.createPrepared(java.util.List.of(),
                new com.rlibanez.eplsync.torrent.bulk.BulkRequest(null,10,1,"0ms"));});
            var reads=executor.submit(() -> {await(start);for(int i=0;i<20;i++) {
                long count=books.count();assertThat(count).isBetween(0L,2000L);
                jobs.list(0,20,null);events.retention();
            }});
            start.countDown();assertThat(imported.get(20,TimeUnit.SECONDS).processed()).isEqualTo(2000);
            journal.get(20,TimeUnit.SECONDS);submitted.get(20,TimeUnit.SECONDS);reads.get(20,TimeUnit.SECONDS);
        }
        assertThat(books.count()).isEqualTo(2000);
    }
    @Test void abruptProcessExitPreservesCommitAndRollsBackPendingWrite() throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("concurrent.db");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
            CrashWriter.class.getName(),url).redirectErrorStream(true).redirectOutput(directory.resolve("crash.log").toFile()).start();
        try {assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();}
        finally {if(process.isAlive()) process.destroyForcibly();}
        assertThat(jdbc.queryForObject("SELECT value FROM concurrency_probe",Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("PRAGMA integrity_check",String.class)).isEqualTo("ok");
    }
    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            try(var connection=DriverManager.getConnection(args[0]);var statement=connection.createStatement()) {
                statement.execute("PRAGMA synchronous=FULL");statement.execute("PRAGMA busy_timeout=10000");
                connection.setAutoCommit(false);statement.executeUpdate("UPDATE concurrency_probe SET value=11");connection.commit();
                statement.executeUpdate("UPDATE concurrency_probe SET value=99");Runtime.getRuntime().halt(0);
            }
        }
    }

}
