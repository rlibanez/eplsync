package com.rlibanez.eplsync.migrations;

import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** SHA-256 of the exact migration files distributed in tag v1.4.1. Never rewrite published history. */
class PublishedMigrationTests {
    @Test void published150MigrationsMatchTheReleasedFiles() throws Exception {
        var resolver=new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var released=resolver.getResources("classpath:migrations/released-1.5.0/*.sql");
        assertEquals(14,released.length);
        for(var resource:released) {
            try(var old=resource.getInputStream();var current=Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+resource.getFilename()))) {
                assertArrayEquals(old.readAllBytes(),current.readAllBytes(),resource.getFilename());
            }
        }
    }
    @Test void published163AdditionalMigrationsRemainIntact() throws Exception {
        var resolver=new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var released=resolver.getResources("classpath:migrations/released-1.6.3/*.sql");
        assertEquals(7,released.length);
        for(var resource:released) {
            try(var old=resource.getInputStream();var current=Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+resource.getFilename()))) {
                assertArrayEquals(old.readAllBytes(),current.readAllBytes(),resource.getFilename());
            }
        }
    }
    @Test void published141MigrationNamesAndContentsRemainIntact() throws Exception {
        var published=Map.ofEntries(
            Map.entry("V1_0_0__initial_schema.sql","ca112f0826bdbad69478c347133f7c3ddfdb1f479cf34a40d338fa0d6e93b543"),
            Map.entry("V1_1_0__calibre_library.sql","07f6badff2eb1384607b1acbca073f3528e7346cbf8a320e553c22d68d6aca7c"),
            Map.entry("V1_2_0__calibre_transfers.sql","78fb27e2f1643b5c98f58ce38c3a78f08ea4f70d4827fac40d7817620b5c9d68"),
            Map.entry("V1_3_0__scheduled_tasks.sql","15ae0e666a75467c84b041f299703a4623adcccd8a76ed255de56d4ee4f2da12"),
            Map.entry("V1_4_0_1__update_automations.sql","25661a6094b29cfc3623ceedcc0dfd377b0501148c9a364e525d6e53f0e88663"),
            Map.entry("V1_4_0_2__cleanup_event_summaries.sql","2b5a3b7b0773af0d587311a60ed2846fdc556b47661645e8b703fcd8c3d1f09d"),
            Map.entry("V1_4_0__job_retries.sql","8db48da1ee0d78ff15be8d8553c13c77ef3e706bcb7e2aae8b36662cfa770f77"),
            Map.entry("V1_5_0_1__telegram_links.sql","302b27e719f809f62d3fcd0ee56ef2149fc21a8da9ac422a3f851f15d57333ea"),
            Map.entry("V1_5_0_2__task_telegram_preferences.sql","72e1a83017e93a6cbc189d41392c9c8a7b29871f05e1a072ac9f05b812f9c694"),
            Map.entry("V1_5_0__telegram_notifications.sql","1154e5c4344d325cd8ab0a0f55481556d1db863d5308769ace8e180acd136a3a"));
        for(var entry:published.entrySet()) {
            try(var stream=Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+entry.getKey()),entry.getKey())) {
                assertNotNull(stream,entry.getKey());
                assertEquals(entry.getValue(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())),entry.getKey());
            }
        }
    }
}
