package com.rlibanez.eplsync.migrations;

import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTests {
    @TempDir Path directory;
    private String url() { return "jdbc:sqlite:" + directory.resolve("database.db"); }
    private JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource(url())); }
    private Flyway flyway(String... locations) {
        return Flyway.configure().dataSource(url(), null, null).locations(locations)
                .ignoreMigrationPatterns(new String[0]).baselineOnMigrate(false).validateOnMigrate(true).cleanDisabled(true).load();
    }
    @Test void createsCompleteSchemaAndDoesNotRepeatMigrations() {
        var migration = flyway("classpath:db/migration");
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals("0.0.1", migration.info().current().getVersion().toString());
        var jdbc = jdbc();
        assertEquals(13, jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT IN ('sqlite_sequence','flyway_schema_history')", Integer.class));
        var indexes = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='index'", String.class);
        assertTrue(indexes.containsAll(java.util.List.of("idx_catalog_title", "idx_events_operation",
                "idx_bulk_item_queue", "idx_cleanup_state_checked", "uk_download_identity", "uq_cleanup_job_download")));
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('first',1,1,'hash','qbittorrent','client','SUBMITTED','EPLSYNC',0)");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('second',1,2,'hash','qbittorrent','client','SUBMITTED','EPLSYNC',0)"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('invalid','alice','alice','a@b.c','hash','INVALID','ACTIVE','now','now')"));
        jdbc.update("INSERT INTO app_settings VALUES('sample','preserved')");
        assertEquals(0, migration.migrate().migrationsExecuted);
        assertEquals("preserved", jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='sample'", String.class));
        assertThrows(FlywayException.class, migration::clean);
    }
    @Test void upgradesWithDataAndRejectsModifiedPublishedMigration() throws Exception {
        flyway("classpath:db/migration").migrate();
        jdbc().update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('user','alice','alice','alice@example.org','hash','USER','ACTIVE','now','now')");
        var scripts = Files.createDirectory(directory.resolve("migrations"));
        var next = scripts.resolve("V0_0_2__test_upgrade.sql");
        Files.writeString(next, "ALTER TABLE users ADD COLUMN migration_test TEXT; UPDATE users SET migration_test='retained';");
        var upgrade = flyway("classpath:db/migration", "filesystem:" + scripts);
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        assertEquals("alice", jdbc().queryForObject("SELECT username FROM users WHERE migration_test='retained'", String.class));
        assertEquals(0, upgrade.migrate().migrationsExecuted);
        Files.writeString(next, "ALTER TABLE users ADD COLUMN changed_test TEXT;");
        assertThrows(FlywayException.class, upgrade::migrate);
        assertEquals("alice", jdbc().queryForObject("SELECT username FROM users", String.class));
    }
    @Test void rejectsDowngradeToAnApplicationMissingAppliedMigrations() throws Exception {
        flyway("classpath:db/migration").migrate();
        var scripts = Files.createDirectory(directory.resolve("future"));
        Files.writeString(scripts.resolve("V0_0_2__future.sql"), "CREATE TABLE future_data(id INTEGER);");
        flyway("classpath:db/migration", "filesystem:" + scripts).migrate();
        assertThrows(FlywayException.class, () -> flyway("classpath:db/migration").migrate());
    }
    @Test void rejectsUnversionedNonEmptyDatabases() {
        jdbc().execute("CREATE TABLE experimental(id INTEGER)");
        assertThrows(FlywayException.class, () -> flyway("classpath:db/migration").migrate());
        assertEquals(0, jdbc().queryForObject("SELECT count(*) FROM experimental", Integer.class));
    }
    @Test void failedUpgradeRollsBackSchemaAndData() throws Exception {
        flyway("classpath:db/migration").migrate();
        jdbc().update("INSERT INTO app_settings VALUES('sample','original')");
        var scripts = Files.createDirectory(directory.resolve("broken"));
        Files.writeString(scripts.resolve("V0_0_2__broken.sql"), "CREATE TABLE test_partial(id INTEGER); UPDATE app_settings SET setting_value='changed'; INSERT INTO nonexistent VALUES(1);");
        assertThrows(FlywayException.class, () -> flyway("classpath:db/migration", "filesystem:" + scripts).migrate());
        assertEquals("original", jdbc().queryForObject("SELECT setting_value FROM app_settings", String.class));
        assertEquals(0, jdbc().queryForObject("SELECT count(*) FROM sqlite_master WHERE name='test_partial'", Integer.class));
        assertEquals("0.0.1", flyway("classpath:db/migration").info().current().getVersion().toString());
    }
}
