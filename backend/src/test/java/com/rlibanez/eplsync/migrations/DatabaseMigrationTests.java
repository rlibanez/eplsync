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
    private JdbcTemplate jdbc() {
        var source=new DriverManagerDataSource(url());
        var properties=new java.util.Properties();properties.setProperty("foreign_keys","true");
        source.setConnectionProperties(properties);
        return new JdbcTemplate(source);
    }
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
    @Test void catalogueOrderingAndRevisionLookupsUseTheirIndexes() {
        flyway("classpath:db/migration").migrate();
        var jdbc=jdbc();
        for (String sql : java.util.List.of(
                "SELECT epl_id FROM catalog_books WHERE publication_status='PUBLISHED' ORDER BY publication_date DESC,epl_id DESC LIMIT 50",
                "SELECT epl_id FROM catalog_books ORDER BY cast((insert_date - ((insert_date % 60000 + 60000) % 60000)) / 60000 as integer) DESC,epl_id ASC LIMIT 50")) {
            String plan=jdbc.queryForList("EXPLAIN QUERY PLAN "+sql).toString();
            assertTrue(plan.contains("idx_catalog_"), plan);
            assertFalse(plan.contains("TEMP B-TREE"), plan);
        }
        String plan=jdbc.queryForList("EXPLAIN QUERY PLAN SELECT id FROM torrent_bulk_items WHERE epl_id=1 AND state IN ('PENDING','IN_FLIGHT')").toString();
        assertTrue(plan.contains("SEARCH torrent_bulk_items USING INDEX idx_bulk_item_update_book"), plan);
    }

    @Test void jobItemsAndPlansRequireExistingJobsAndUniquePositionsWithoutCascades() {
        flyway("classpath:db/migration").migrate();
        var jdbc=jdbc();
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,state) VALUES('job',1,1,0,0,'QUEUED')");
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('item','job',0,0,'PENDING')");
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('duplicate','job',0,0,'PENDING')"));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('orphan','missing',1,0,'PENDING')"));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("INSERT INTO torrent_bulk_items(id,position,attempts,state) VALUES('no-job',1,0,'PENDING')"));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('negative','job',-1,0,'PENDING')"));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE torrent_bulk_items SET attempts=-1 WHERE id='item'"));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,created_at,snapshot) VALUES('missing','client','KEEP',0,'{}')"));
        jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,created_at,snapshot) VALUES('job','client','KEEP',0,'{}')");
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("DELETE FROM torrent_bulk_jobs WHERE id='job'"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items",Integer.class));
        jdbc.update("DELETE FROM torrent_bulk_items");
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("DELETE FROM torrent_bulk_jobs WHERE id='job'"));
        jdbc.update("DELETE FROM torrent_update_plans");jdbc.update("DELETE FROM torrent_bulk_jobs");
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
    }
    @Test void catalogRejectsInvalidIdsRevisionsAndTextButAcceptsLongAuthorsAndNegativeYears() {
        flyway("classpath:db/migration").migrate();
        var jdbc=jdbc();
        String insert="INSERT INTO catalog_books(epl_id,revision,title,author,publication_year) VALUES(?,?,?,?,?)";
        for(Object id: new Object[]{0,-1,1.5}) assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,id,1,"Title","Author",2026));
        for(double revision: new double[]{0,-1,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NaN})
            assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,1,revision,"Title","Author",2026));
        for(String blank:new String[]{"", " ", "\t\n", "\u00a0\u2003"}) {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,1,1,blank,"Author",2026));
            assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,1,1,"Title",blank,2026));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,1,1,"x".repeat(4097),"Author",2026));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,1,1,"Title","x".repeat(16385),2026));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(insert,1,1,"Title\0hidden","Author",2026));
        jdbc.update(insert,1,1,"Title","x".repeat(16384),-500);
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE catalog_books SET synopsis=?","prefix\0hidden"));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE catalog_books SET genres=?","x".repeat(4097)));
        assertEquals(16384,jdbc.queryForObject("SELECT length(author) FROM catalog_books",Integer.class));
        // Historical download references survive removal of their catalog book.
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('history',1,1,'hash','qbittorrent','client','SUBMITTED','EPLSYNC',0)");
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE torrent_downloads SET revision=?",Double.POSITIVE_INFINITY));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE torrent_downloads SET epl_id=0"));
        jdbc.update("DELETE FROM catalog_books");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM torrent_downloads",Integer.class));
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
