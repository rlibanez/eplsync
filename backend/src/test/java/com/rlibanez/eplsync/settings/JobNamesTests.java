package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate",
    "eplsync.torrent.enabled=false","eplsync.torrent.bulk.retention.enabled=false"})
class JobNamesTests {
    @Autowired JobNames names;
    @Autowired WorkListings listings;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM revision_cleanup_jobs");
        jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM torrent_bulk_jobs");
        for(String id:java.util.List.of("a","b","c")) {
            jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,type,state) VALUES(?,100,5,0,1,'DOWNLOAD','COMPLETED')",id);
            jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES(?,'2026-11-21T10:00:00Z','2026-11-21T10:00:00Z','owner','target','library','source','COMPLETED')",id);
        }
    }
    @Test @WithMockUser(authorities={"TORRENT_JOBS_MANAGE","CALIBRE_JOBS_MANAGE"})
    void renameClearAndValidateWithoutChangingAnyOtherJobData() {
        for(String source:java.util.List.of("downloads","calibre")) {
            String table=source.equals("downloads")?"torrent_bulk_jobs":"calibre_jobs";
            var before=jdbc.queryForMap("SELECT * FROM "+table+" WHERE id='a'");
            assertThat(names.get(source,"a").name()).isNull();
            assertThat(names.save(source,"a",new JobNames.Name("  Catálogo completo 20261120  ")).name()).isEqualTo("Catálogo completo 20261120");
            var after=jdbc.queryForMap("SELECT * FROM "+table+" WHERE id='a'");after.put("name",null);assertThat(after).isEqualTo(before);
            names.save(source,"b",new JobNames.Name("Catálogo completo 20261120"));
            assertThat(names.save(source,"a",new JobNames.Name("   ")).name()).isNull();
            assertThatThrownBy(()->names.save(source,"a",new JobNames.Name("a".repeat(151)))).isInstanceOf(UserInputException.class);
            assertThatThrownBy(()->names.save(source,"a",new JobNames.Name("a\nb"))).isInstanceOf(UserInputException.class);
            assertThatThrownBy(()->names.save(source,"missing",new JobNames.Name("title"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
    @Test @WithMockUser(authorities={"TORRENT_JOBS_MANAGE","CALIBRE_JOBS_MANAGE"})
    void searchesSortsPaginatesAndNavigatesBothJobTypes() {
        for(String source:java.util.List.of("downloads","calibre")) {
            names.save(source,"a",new JobNames.Name("Catálogo 10"));names.save(source,"b",new JobNames.Name("Catálogo 2"));
            var first=listings.list(source,0,1,null,"",null,null,null,"name,asc","eq","eq","catálogo");
            String id=source.equals("downloads")?"jobId":"id";
            assertThat(first.meta().totalItems()).isEqualTo(2);
            assertThat(first.items()).extracting(row->row.get(id)).containsExactly("b");
            assertThat(first.items().getFirst().get("name")).isEqualTo("Catálogo 2");
            assertThat(listings.list(source,1,1,null,"",null,null,null,"name,asc","eq","eq","catálogo").items()).extracting(row->row.get(id)).containsExactly("a");
            assertThat(listings.list(source,0,20,null,"",null,null,null,"name,desc","eq","eq","CATálogo").items()).extracting(row->row.get(id)).containsExactly("a","b");
            var neighbors=listings.neighbors(source,"b",null,"",null,null,null,"name,asc","eq","eq","catálogo");
            assertThat(neighbors.previous()).isNull();assertThat(neighbors.next().id()).isEqualTo("a");
        }
    }
    @Test @WithMockUser(authorities="CALIBRE_SEND")
    void calibreReadersCannotRenameOrAccessDownloadNames() {
        assertThat(names.get("calibre","a").name()).isNull();
        assertThatThrownBy(()->names.save("calibre","a",new JobNames.Name("forbidden"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->names.get("downloads","a")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    private void cleanupLogin(String id,java.util.Set<com.rlibanez.eplsync.security.Permission> permissions) {
        var account=new com.rlibanez.eplsync.security.Account(id,id,"owner@example.org","USER","ACTIVE",false,null,0,permissions,java.time.Instant.now());
        var authorities=permissions.stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account,null,authorities));
    }
    @AfterEach void logout() {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    private void cleanupSeed(String source) {
        jdbc.update("INSERT OR IGNORE INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('name-owner','name-owner','name-owner','name-owner@example.org','hash','USER','ACTIVE','now','now')");
        jdbc.update("INSERT INTO revision_cleanup_jobs(id,source,action,owner_id,owner_username,context,state,result,created_at,updated_at,expires_at) VALUES(?,?,?,'name-owner','name-owner','context','COMPLETED','PARTIAL',1,2,3)",source,source,source.equals("downloads")?"FILES":"BOOK");
    }
    @Test void cleanupNamesPreserveExecutionDataAndParticipateInBothWorkLists() {
        var permissions=java.util.Set.of(com.rlibanez.eplsync.security.Permission.TORRENT_SYNC,com.rlibanez.eplsync.security.Permission.DOWNLOADS_DELETE,com.rlibanez.eplsync.security.Permission.CALIBRE_READ,com.rlibanez.eplsync.security.Permission.CALIBRE_DELETE);
        cleanupLogin("name-owner",permissions);
        for(String source:java.util.List.of("downloads","calibre")) {
            cleanupSeed(source);
            var before=jdbc.queryForMap("SELECT * FROM revision_cleanup_jobs WHERE id=?",source);
            assertThat(names.save("cleanup",source,new JobNames.Name("  Limpieza revisiones  ")).name()).isEqualTo("Limpieza revisiones");
            assertThat(names.get("cleanup",source).name()).isEqualTo("Limpieza revisiones");
            var after=jdbc.queryForMap("SELECT * FROM revision_cleanup_jobs WHERE id=?",source);after.put("name",null);assertThat(after).isEqualTo(before);
            var page=listings.list(source,0,20,null,"CLEANUP",null,null,null,"name,asc","eq","eq","revisiones");
            assertThat(page.items()).singleElement().satisfies(row->assertThat(row).containsEntry("name","Limpieza revisiones"));
            assertThat(names.save("cleanup",source,new JobNames.Name("")).name()).isNull();
        }
        cleanupLogin("another-owner",permissions);
        assertThatThrownBy(()->names.get("cleanup","downloads")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->names.save("cleanup","calibre",new JobNames.Name("forbidden"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void cleanupReadPermissionDoesNotGrantRenamePermission() {
        for(String source:java.util.List.of("downloads","calibre")) cleanupSeed(source);
        cleanupLogin("name-owner",java.util.Set.of(com.rlibanez.eplsync.security.Permission.TORRENT_SYNC,com.rlibanez.eplsync.security.Permission.CALIBRE_READ));
        for(String source:java.util.List.of("downloads","calibre")) {
            assertThat(names.get("cleanup",source).name()).isNull();
            assertThatThrownBy(()->names.save("cleanup",source,new JobNames.Name("forbidden"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

}
