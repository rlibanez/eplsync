package com.rlibanez.eplsync.config;

import java.nio.file.Path;
import java.sql.*;
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

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=1","eplsync.tasks.worker-enabled=false","eplsync.updates.scheduler-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class SqliteTransactionRecoveryTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("recovery.db"));
    }
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore jobs;
    @BeforeEach void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS transaction_probe(id INTEGER PRIMARY KEY,value INTEGER)");
        jdbc.update("DELETE FROM transaction_probe");jdbc.update("INSERT INTO transaction_probe VALUES(1,0)");
        try(var connection=source.getConnection()) {connection.unwrap(org.sqlite.SQLiteConnection.class).setBusyTimeout(40);}
        catch(SQLException ex) {throw new IllegalStateException(ex);}
    }
    @Test void evictionOnlyTargetsKnownTransactionStateErrors() {
        var policy=new SqliteConnectionExceptionOverride();
        for(String message:java.util.List.of("cannot rollback - no transaction is active","cannot commit - no transaction is active","cannot start a transaction within a transaction"))
            assertThat(policy.adjudicate(new SQLException(message,"",1))).isEqualTo(com.zaxxer.hikari.SQLExceptionOverride.Override.MUST_EVICT);
        for(int code:new int[]{1,5,6,10,19})
            assertThat(policy.adjudicate(new SQLException("other error","",code))).isEqualTo(com.zaxxer.hikari.SQLExceptionOverride.Override.CONTINUE_EVICT);
        assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(new SQLException("cannot rollback - no transaction is active","",1))).isFalse();
    }
    @Test void writerReservationIsNotReopenedAfterCommit() {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) pooled->{
                var connection=pooled.unwrap(org.sqlite.SQLiteConnection.class);
                assertThat(connection.getCurrentTransactionMode()).isEqualTo(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
                assertThat(connection.getConnectionConfig().getTransactionMode()).isEqualTo(org.sqlite.SQLiteConfig.TransactionMode.DEFERRED);
                return null;
            });
            jdbc.update("UPDATE transaction_probe SET value=7");
        });
        assertThat(jdbc.queryForObject("SELECT value FROM transaction_probe",Integer.class)).isEqualTo(7);
    }
    @Test void failedBeginDoesNotPoisonThePoolOrLeakAutocommitWrites() throws Exception {
        try(var competitor=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("recovery.db"));var statement=competitor.createStatement()) {
            competitor.setAutoCommit(false);statement.executeUpdate("UPDATE transaction_probe SET value=1");
            assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->fail("Cannot enter while SQLite is held")))
                .satisfies(error->assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(error)).isTrue());
            competitor.rollback();
        }
        for(int n=0;n<3;n++) assertThatCode(()->jobs.next()).doesNotThrowAnyException();
        new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.update("UPDATE transaction_probe SET value=99");status.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT value FROM transaction_probe",Integer.class)).isZero();
    }
    @Test void committedButUnrestartedTransactionIsEvictedInsteadOfReusedForever() throws Exception {
        org.sqlite.SQLiteConnection broken;
        var pooled=source.getConnection();
        try {
            broken=pooled.unwrap(org.sqlite.SQLiteConnection.class);
            broken.getConnectionConfig().setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
            pooled.setAutoCommit(false);
            try(var statement=pooled.createStatement()) {statement.executeUpdate("UPDATE transaction_probe SET value=7");}
            // Reproduce the two native stages of SQLiteConnection.commit(): COMMIT succeeds,
            // another writer wins, then its implicit BEGIN IMMEDIATE fails with SQLITE_BUSY.
            broken.getDatabase().exec("COMMIT",false);
            try(var competitor=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("recovery.db"));var statement=competitor.createStatement()) {
                competitor.setAutoCommit(false);statement.executeUpdate("UPDATE transaction_probe SET value=value");
                assertThatThrownBy(()->broken.getDatabase().exec("BEGIN IMMEDIATE",false))
                    .isInstanceOf(SQLException.class).satisfies(error->assertThat(com.rlibanez.eplsync.ordering.SqliteContention.isBusy(error)).isTrue());
                competitor.rollback();
            }
            assertThatThrownBy(pooled::close).isInstanceOf(SQLException.class).hasMessageContaining("cannot rollback - no transaction is active");
        } finally {pooled.close();}
        try(var healthy=source.getConnection()) {
            assertThat(healthy.unwrap(org.sqlite.SQLiteConnection.class)).isNotSameAs(broken);
        }
        for(int n=0;n<5;n++) assertThatCode(()->jobs.next()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT value FROM transaction_probe",Integer.class)).isEqualTo(7);
    }
}
