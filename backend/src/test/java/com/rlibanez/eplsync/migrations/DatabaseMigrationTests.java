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
        return new JdbcTemplate(new com.rlibanez.eplsync.ordering.TextCollationDataSource(source));
    }
    private Flyway flyway(String... locations) {
        return Flyway.configure().dataSource(new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()))).locations(locations)
                .ignoreMigrationPatterns(new String[0]).baselineOnMigrate(false).validateOnMigrate(true).cleanDisabled(true).load();
    }
    @Test void telegramLinkMigrationPreservesCredentialsAndPreferences() {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()));
        var jdbc=jdbc();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("1.5.0").load().migrate();
        jdbc.update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('owner','alice','alice','a@b.c','hash','ADMIN','ACTIVE','now','now')");
        jdbc.update("INSERT INTO telegram_settings VALUES('owner',1,'encrypted-token','123456',0,1,1,'es')");
        var before=jdbc.queryForMap("SELECT * FROM telegram_settings");
        var columnsBefore=jdbc.queryForList("PRAGMA table_info(telegram_settings)");
        assertEquals(8,columnsBefore.size());
        assertEquals(6,flyway("classpath:db/migration").migrate().migrationsExecuted);
        assertEquals(8,jdbc.queryForList("PRAGMA table_info(telegram_settings)").size());
        var after=jdbc.queryForMap("SELECT * FROM telegram_settings");
        assertFalse(after.containsKey("base_url"));
        assertEquals(before,after);
    }
    @Test void upgrades141WithoutChangingExistingRowsAndCreatesPrivateCleanupJobs() {
        Flyway.configure().dataSource(new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()))).locations("classpath:db/migration").target("1.5.0.2").load().migrate();
        var jdbc=jdbc();jdbc.update("INSERT INTO app_settings VALUES('retained','value')");
        assertEquals(4,flyway("classpath:db/migration").migrate().migrationsExecuted);
        assertEquals("value",jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='retained'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM revision_cleanup_jobs",Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
    }
    @Test void pendingReplacementOptInDefaultsToFalseForExistingCleanupJobs() {
        Flyway.configure().dataSource(new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()))).locations("classpath:db/migration").target("1.5.0.3").load().migrate();
        var jdbc=jdbc();
        jdbc.update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('owner','alice','alice','a@b.c','hash','ADMIN','ACTIVE','now','now')");
        jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,created_at,updated_at,expires_at) VALUES('existing','downloads','FILES','owner','alice','context','PAUSED',1,2,3)");
        assertEquals(3,flyway("classpath:db/migration").migrate().migrationsExecuted);
        assertEquals(0,jdbc.queryForObject("SELECT allow_undownloaded FROM revision_cleanup_jobs WHERE id='existing'",Integer.class));
        assertEquals("PAUSED",jdbc.queryForObject("SELECT state FROM revision_cleanup_jobs WHERE id='existing'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT delete_history FROM revision_cleanup_jobs WHERE id='existing'",Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
    }
    @Test void createsCompleteSchemaAndDoesNotRepeatMigrations() {
        var migration = flyway("classpath:db/migration");
        assertEquals(14, migration.migrate().migrationsExecuted);
        assertEquals("1.5.0.6", migration.info().current().getVersion().toString());
        var jdbc = jdbc();
        assertEquals(30, jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT IN ('sqlite_sequence','flyway_schema_history')", Integer.class));
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
    @Test void upgradesRelease101To130WithoutLosingExistingData() {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()));
        Flyway.configure().dataSource(source).locations("classpath:migrations/released-1.0.1").load().migrate();
        // Fixture copied verbatim from tag v1.0.1; that release used schema 1.0.0.
        jdbc().update("INSERT INTO app_settings VALUES('preserved','value')");
        jdbc().update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(123,1.2,'Preserved book','Author')");
        jdbc().update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('user','alice','alice','alice@example.org','hash','USER','ACTIVE','now','now')");
        jdbc().update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('history',123,1,'hash','qbittorrent','client','DOWNLOADED','EPLSYNC',0)");
        var upgrade=flyway("classpath:db/migration");
        assertEquals(13,upgrade.migrate().migrationsExecuted);
        assertEquals("1.5.0.6",upgrade.info().current().getVersion().toString());
        assertEquals("value",jdbc().queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='preserved'",String.class));
        assertEquals(0,jdbc().queryForObject("SELECT count(*) FROM calibre_books",Integer.class));
        assertEquals("Preserved book",jdbc().queryForObject("SELECT title FROM catalog_books WHERE epl_id=123",String.class));
        assertEquals("alice",jdbc().queryForObject("SELECT username FROM users WHERE id='user'",String.class));
        assertEquals("DOWNLOADED",jdbc().queryForObject("SELECT status FROM torrent_downloads WHERE id='history'",String.class));
        assertTrue(jdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
        upgrade.validate();
        assertEquals(0,upgrade.migrate().migrationsExecuted);
    }
    @Test void upgrades110To130PreservingCalibreLibraryAndSettings() {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("1.1.0").load().migrate();
        var jdbc=jdbc();
        jdbc.update("INSERT INTO app_settings VALUES('calibre.connection','preserved-settings')");
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('book','generation',1,9,123,1.2,'Preserved Calibre book','Author')");
        var upgrade=flyway("classpath:db/migration");
        assertEquals(12,upgrade.migrate().migrationsExecuted);
        assertEquals("1.5.0.6",upgrade.info().current().getVersion().toString());
        assertEquals("Preserved Calibre book",jdbc.queryForObject("SELECT title FROM calibre_books WHERE calibre_id=9",String.class));
        assertEquals("preserved-settings",jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='calibre.connection'",String.class));
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('job','now','now','Owner','destination','main','source','QUEUED')");
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state) VALUES('item','job',0,'record',0,'book.epub',1,123,'1.2','PENDING')");
        assertEquals(0,jdbc.queryForObject("SELECT identity_complete FROM calibre_job_items WHERE id='item'",Integer.class));
        jdbc.update("DELETE FROM calibre_jobs WHERE id='job'");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
        upgrade.validate();assertEquals(0,upgrade.migrate().migrationsExecuted);
    }
    @Test void upgrades131To140PreservingJobsAndTheirCleanupOptions() {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("1.3.0").load().migrate();
        var jdbc=jdbc();
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('job','now','now','Owner','target','main','source','PAUSED')");
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,cleanup_policy,cleanup_ids) VALUES('item','job',0,'record',0,'book.epub',1,1,'1.1','CLEANUP_PENDING','EPUB','[8]')");
        // 1.3.1 uses schema 1.3.0. Preserve every original column, not just row counts.
        jdbc.update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('owner','alice','alice','alice@example.org','password-hash','ADMIN','ACTIVE','now','now')");
        jdbc.update("INSERT INTO app_settings VALUES('preserved.connection','{\"url\":\"http://calibre.test\"}')");
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(123,1.2,'Libro conservado','Autor')");
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('book','generation',1,9,123,1.1,'Libro conservado','Autor')");
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('record',123,1.1,'hash','qbittorrent','client','DOWNLOADED','EPLSYNC',0)");
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state) VALUES('download-job',1,1,0,1,'UPDATE','CLEANUP_WAIT')");
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('download-item','download-job',0,1,'ACCEPTED')");
        jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,created_at,snapshot) VALUES('download-job','client','REMOVE_TORRENT',0,'{}')");
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state) VALUES('cleanup','download-job','record',123,'hash','BLOCKED')");
        jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,next_run,created_at,updated_at,last_run,last_finished,last_result) VALUES('task','Diaria','CALIBRE_SYNC','0 5 * * *','UTC','owner','alice',1,1000,1,1,100,200,'SUCCEEDED')");
        jdbc.update("INSERT INTO update_search_results VALUES('owner','calibre',123,'MANUAL','{}','[{\"eplId\":123}]')");
        jdbc.update("INSERT INTO app_events(created_at,category,action,outcome,origin,operation_id,details,actor_id) VALUES(1,'JOB','SCHEDULED_TASK','FAILED','SCHEDULED','operation','{}','owner')");
        var before=new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for(var table:jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('flyway_schema_history','sqlite_sequence') ORDER BY name",String.class))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY rowid"));
        var upgrade=flyway("classpath:db/migration");assertEquals(10,upgrade.migrate().migrationsExecuted);
        assertEquals("EPUB",jdbc.queryForObject("SELECT requested_cleanup_policy FROM calibre_job_items WHERE id='item'",String.class));
        assertEquals("[8]",jdbc.queryForObject("SELECT requested_cleanup_ids FROM calibre_job_items WHERE id='item'",String.class));
        assertEquals("CLEANUP_PENDING",jdbc.queryForObject("SELECT state FROM calibre_job_items WHERE id='item'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM job_retry_jobs",Integer.class));
        for(var entry:before.entrySet()) {
            var columns=jdbc.queryForList("PRAGMA table_info("+entry.getKey()+")").stream().map(row->row.get("name").toString())
                .filter(column->!java.util.Set.of("requested_cleanup_policy","requested_cleanup_ids","cleanup_cancelled","event_signature","telegram_notifications","delete_history").contains(column)).toList();
            assertEquals(entry.getValue(),jdbc.queryForList("SELECT "+String.join(",",columns)+" FROM "+entry.getKey()+" ORDER BY rowid"),"Datos alterados en "+entry.getKey());
        }
        assertEquals("ok",jdbc.queryForObject("PRAGMA integrity_check",String.class));
        assertEquals(0,upgrade.migrate().migrationsExecuted);
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());upgrade.validate();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"1.4.0.2","1.5.0.2"})
    void upgrades140And141To142PreservingExistingDataAndPendingAutomations(String baseline) {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url()));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target(baseline).load().migrate();
        boolean release141=baseline.equals("1.5.0.2");
        var jdbc=jdbc();
        jdbc.update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at,notification_preferences) VALUES('owner','alice','alice','a@b.c','hash','ADMIN','ACTIVE','now','now',?)", "{\"floating\":false}");
        jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,next_run,created_at,updated_at) VALUES('auto','Automatic','TORRENT_AUTO_UPDATE','0 5 * * *','UTC','owner','alice',1,100,0,0)");
        if(release141) {
            jdbc.update("UPDATE scheduled_tasks SET telegram_notifications=0 WHERE id='auto'");
            jdbc.update("INSERT INTO telegram_settings VALUES('owner',1,'encrypted-test-token','123456',0,1,1,'es')");
            jdbc.update("INSERT INTO telegram_deliveries(id,user_id,operation_id,action,outcome,permission,configuration,message,state,created_at,next_attempt,task_id) VALUES('delivery','owner','pending-operation','SCHEDULED_TASK','SUCCEEDED','SETTINGS_MANAGE','fingerprint','Test message','PENDING',0,0,'auto')");
        }
        jdbc.update("INSERT INTO scheduled_automation_settings VALUES('auto','{}')");
        jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,owner_id,state,created_at,configuration,context) VALUES('cycle','auto','owner','WAITING',0,'{}','SCHEDULED')");
        jdbc.update("INSERT INTO app_settings VALUES('preserved.connection','{\"url\":\"http://calibre.test\"}')");
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(123,1.2,'Libro conservado','Autor')");
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('book','generation',1,9,123,1.1,'Libro conservado','Autor')");
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('record',123,1.1,'hash','qbittorrent','client','DOWNLOADED','EPLSYNC',0)");
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state) VALUES('download-job',1,1,0,1,'UPDATE','CLEANUP_WAIT')");
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('download-item','download-job',0,1,'ACCEPTED')");
        jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,created_at,snapshot) VALUES('download-job','client','REMOVE_TORRENT',0,'{}')");
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state) VALUES('cleanup','download-job','record',123,'hash','BLOCKED')");
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES('job','now','now','owner','alice','target','main','source','PAUSED')");
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state) VALUES('item','job',0,'record',0,'book.epub',1,123,'1.1','PENDING')");
        jdbc.update("INSERT INTO scheduled_automation_jobs VALUES('cycle','downloads','download-job')");
        jdbc.update("INSERT INTO update_search_results VALUES('owner','calibre',123,'MANUAL','{}','[{\"eplId\":123}]')");
        jdbc.update("INSERT INTO app_events(created_at,category,action,outcome,origin,operation_id,details,actor_id) VALUES(1,'JOB','SCHEDULED_TASK','FAILED','SCHEDULED','operation','{}','owner')");
        var before=new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for(var table:jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('flyway_schema_history','sqlite_sequence') ORDER BY name",String.class))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY rowid"));
        var upgrade=flyway("classpath:db/migration");assertEquals(release141?4:7,upgrade.migrate().migrationsExecuted);
        for(var entry:before.entrySet()) {
            var after=jdbc.queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY rowid");
            if(!release141 && entry.getKey().equals("scheduled_tasks")) for(var row:after) assertEquals(1,row.remove("telegram_notifications"));
            if(entry.getKey().equals("torrent_update_plans") || entry.getKey().equals("torrent_update_cleanup")) for(var row:after) assertEquals(0,row.remove("delete_history"));
            assertEquals(entry.getValue(),after);
        }
        assertEquals(release141?1:0,jdbc.queryForObject("SELECT count(*) FROM telegram_settings",Integer.class));
        assertEquals(release141?1:0,jdbc.queryForObject("SELECT count(*) FROM telegram_deliveries",Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
        assertEquals("ok",jdbc.queryForObject("PRAGMA integrity_check",String.class));
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
    @Test void upgradesUpdateJobsWithChildReferencesAndPendingCleanup() {
        var source=new DriverManagerDataSource(url());
        var properties=new java.util.Properties();properties.setProperty("foreign_keys","true");
        source.setConnectionProperties(properties);
        var collated=new com.rlibanez.eplsync.ordering.TextCollationDataSource(source);
        Flyway.configure().dataSource(collated).locations("classpath:db/migration").target("1.2.0").load().migrate();
        var jdbc=jdbc();
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state) VALUES('job',1,1,0,1,'UPDATE','COMPLETED')");
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,attempts,state) VALUES('item','job',0,0,'ACCEPTED')");
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('record',1,1,'hash','qbittorrent','client','DOWNLOADED','EPLSYNC',0)");
        jdbc.update("INSERT INTO torrent_update_plans(job_id,client_instance_id,previous_versions,created_at,snapshot) VALUES('job','client','REMOVE_TORRENT',0,'{}')");
        jdbc.update("INSERT INTO torrent_update_cleanup(id,job_id,download_id,epl_id,hash,state) VALUES('cleanup','job','record',1,'hash','BLOCKED')");
        Flyway.configure().dataSource(collated).locations("classpath:db/migration").load().migrate();
        assertEquals("CLEANUP_WAIT",jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id='job'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE job_id='job'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM torrent_update_cleanup WHERE job_id='job'",Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE torrent_bulk_jobs SET state='INVALID'"));
    }
    @Test void movesSavedSearchJsonWithoutMixingUsersOrDeletingUnmigratableSettings() {
        Flyway.configure().dataSource(new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url())))
            .locations("classpath:db/migration").target("1.2.0").load().migrate();
        var jdbc=jdbc();
        for(String id:java.util.List.of("alice","bob")) jdbc.update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES(?,?,?,?,'hash','USER','ACTIVE','now','now')",id,id,id,id+"@example.org");
        String downloads="{\"rows\":[{\"eplId\":77182,\"title\":\"Árbol\",\"availableRevision\":3.4}],\"searchedAt\":123,\"origin\":\"MANUAL\",\"states\":[\"DOWNLOADED\"],\"criteria\":{\"states\":[\"DOWNLOADED\"],\"synchronize\":true}}";
        String calibre="{\"rows\":[{\"calibreId\":7,\"eplId\":77182,\"revision\":1.1,\"downloadAvailability\":\"READY\"}],\"searchedAt\":456,\"origin\":\"SCHEDULED\",\"states\":[],\"criteria\":{\"library\":\"main\",\"updatesOnly\":true}}";
        jdbc.update("INSERT INTO app_settings VALUES(?,?)","tasks.update-search.downloads.alice",downloads);
        jdbc.update("INSERT INTO app_settings VALUES(?,?)","tasks.update-search.calibre.alice",calibre);
        jdbc.update("INSERT INTO app_settings VALUES(?,?)","tasks.update-search.downloads.bob","{\"rows\":[],\"searchedAt\":789,\"states\":[\"NOT_FOUND\"]}");
        jdbc.update("INSERT INTO app_settings VALUES(?,?)","tasks.update-search.calibre.bob","broken JSON");
        jdbc.update("INSERT INTO app_settings VALUES(?,?)","tasks.update-search.downloads.deleted",downloads);
        jdbc.update("INSERT INTO app_settings VALUES('tasks.internal.torrent-cleanup','60s')");
        var migration=flyway("classpath:db/migration");assertEquals(11,migration.migrate().migrationsExecuted);
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM update_search_results",Integer.class));
        assertEquals(123L,jdbc.queryForObject("SELECT searched_at FROM update_search_results WHERE user_id='alice' AND search_type='downloads'",Long.class));
        assertEquals("MANUAL",jdbc.queryForObject("SELECT origin FROM update_search_results WHERE user_id='alice' AND search_type='downloads'",String.class));
        assertEquals("Árbol",jdbc.queryForObject("SELECT json_extract(results_json,'$[0].title') FROM update_search_results WHERE user_id='alice' AND search_type='downloads'",String.class));
        assertEquals(3.4,jdbc.queryForObject("SELECT json_extract(results_json,'$[0].availableRevision') FROM update_search_results WHERE user_id='alice' AND search_type='downloads'",Double.class));
        assertEquals("main",jdbc.queryForObject("SELECT json_extract(criteria_json,'$.library') FROM update_search_results WHERE user_id='alice' AND search_type='calibre'",String.class));
        assertEquals("SCHEDULED",jdbc.queryForObject("SELECT origin FROM update_search_results WHERE user_id='bob'",String.class));
        assertEquals("NOT_FOUND",jdbc.queryForObject("SELECT json_extract(criteria_json,'$.states[0]') FROM update_search_results WHERE user_id='bob'",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM app_settings WHERE setting_key LIKE 'tasks.update-search.%'",Integer.class));
        assertEquals("broken JSON",jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='tasks.update-search.calibre.bob'",String.class));
        assertEquals("60s",jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='tasks.internal.torrent-cleanup'",String.class));
        migration.validate();assertEquals(0,migration.migrate().migrationsExecuted);
        jdbc.update("DELETE FROM users WHERE id='alice'");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM update_search_results",Integer.class));
        assertTrue(jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO update_search_results VALUES('missing','downloads',1,'MANUAL','{}','[]')"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE update_search_results SET results_json='{}'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE update_search_results SET criteria_json='[]'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE update_search_results SET origin='unknown'"));
    }
    @Test void upgradesRelease121SchemaTo130WithCalibreJobsAndCreatesEmptyTaskManager() {
        Flyway.configure().dataSource(new com.rlibanez.eplsync.ordering.TextCollationDataSource(new DriverManagerDataSource(url())))
            .locations("classpath:db/migration").target("1.2.0").load().migrate();
        jdbc().update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('owner','alice','alice','a@b.c','hash','ADMIN','ACTIVE','now','now')");
        jdbc().update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES('job','now','now','owner','alice','target','main','DOWNLOADS','PAUSED')");
        jdbc().update("INSERT INTO app_settings VALUES('tasks.internal.calibre-download-check','60s')");
        assertEquals(11,flyway("classpath:db/migration").migrate().migrationsExecuted);
        assertEquals("PAUSED",jdbc().queryForObject("SELECT state FROM calibre_jobs WHERE id='job'",String.class));
        assertEquals("60s",jdbc().queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='tasks.internal.calibre-download-check'",String.class));
        assertEquals(0,jdbc().queryForObject("SELECT count(*) FROM scheduled_tasks",Integer.class));
        assertNull(jdbc().queryForObject("SELECT notification_preferences FROM users WHERE id='owner'",String.class));
        assertEquals(0,jdbc().queryForObject("SELECT count(*) FROM update_search_results",Integer.class));
        jdbc().update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,next_run,created_at,updated_at) VALUES('task','Nightly','CALIBRE_SYNC','0 0 3 * * *','UTC','owner','alice',1,1,1,1)");
        jdbc().update("DELETE FROM users WHERE id='owner'");
        assertNull(jdbc().queryForObject("SELECT owner_id FROM scheduled_tasks WHERE id='task'",String.class));
        assertTrue(jdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
    }
    @Test void upgradesWithDataAndRejectsModifiedPublishedMigration() throws Exception {
        flyway("classpath:db/migration").migrate();
        jdbc().update("INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('user','alice','alice','alice@example.org','hash','USER','ACTIVE','now','now')");
        var scripts = Files.createDirectory(directory.resolve("migrations"));
        var next = scripts.resolve("V9_0_0__test_upgrade.sql");
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
        Files.writeString(scripts.resolve("V9_0_0__future.sql"), "CREATE TABLE future_data(id INTEGER);");
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
        Files.writeString(scripts.resolve("V9_0_0__broken.sql"), "CREATE TABLE test_partial(id INTEGER); UPDATE app_settings SET setting_value='changed'; INSERT INTO nonexistent VALUES(1);");
        assertThrows(FlywayException.class, () -> flyway("classpath:db/migration", "filesystem:" + scripts).migrate());
        assertEquals("original", jdbc().queryForObject("SELECT setting_value FROM app_settings", String.class));
        assertEquals(0, jdbc().queryForObject("SELECT count(*) FROM sqlite_master WHERE name='test_partial'", Integer.class));
        assertEquals("1.5.0.6", flyway("classpath:db/migration").info().current().getVersion().toString());
    }
}
