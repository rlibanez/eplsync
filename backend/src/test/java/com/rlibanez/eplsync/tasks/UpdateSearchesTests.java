package com.rlibanez.eplsync.tasks;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.settings.*;
import com.rlibanez.eplsync.torrent.updates.*;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import com.rlibanez.eplsync.dto.PageResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.tasks.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
@WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","CALIBRE_READ","CALIBRE_SYNC","TORRENT_SYNC","DOWNLOADS_FILES_READ"})
class UpdateSearchesTests {
    @Autowired UpdateSearches searches;
    @Autowired UpdatePreferences preferences;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RevisionUpdates updates;
    @MockitoBean UpdateCleanupService torrent;
    @MockitoBean CalibreCatalogService calibre;
    @MockitoBean CalibreUpdateFlow files;
    @BeforeEach void resetData() {
        jdbc.update("DELETE FROM update_search_results");
        jdbc.update("INSERT OR IGNORE INTO users(id,username,username_normalized,email,password_hash,role,status,created_at,updated_at) VALUES('owner','searchowner','searchowner','searchowner@example.org','hash','USER','ACTIVE','now','now')");
        jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM catalog_books");
        preferences.save(new UpdatePreferences.Preferences(List.of(DownloadStatus.DOWNLOADED)));
        when(calibre.sync()).thenReturn(new CalibreCatalogService.Snapshot("main",Instant.now(),4,3));
    }
    @Test void torrentSearchUsesSavedStatesAndPersistsOnlyACompletePerUserResult() {
        var input=preferences.get();
        var row=new RevisionUpdates.Row(1L,"Libro",1.0,2.0,DownloadStatus.DOWNLOADED,null,false);
        when(updates.search(input,0,1000,"eplId,asc")).thenReturn(new PageResponse<>(List.of(row),new PageResponse.PageMeta(0,1000,2,2,true,false,true,false)));
        when(updates.search(input,1,1000,"eplId,asc")).thenReturn(new PageResponse<>(List.of(row),new PageResponse.PageMeta(1,1000,2,2,false,true,false,true)));
        assertThat(searches.downloads("owner")).containsEntry("updates",2);
        verify(torrent).synchronize(false,false);
        var result=searches.latest("downloads","owner");assertThat((List<?>)result.get("rows")).hasSize(2);
        assertThat(result.get("states")).isEqualTo(List.of("DOWNLOADED"));
        assertThat(searches.latest("downloads","other")).isNull();
        when(updates.search(input,1,1000,"eplId,asc")).thenThrow(new IllegalStateException("fail"));
        assertThatThrownBy(()->searches.downloads("owner")).isInstanceOf(IllegalStateException.class);
        assertThat(searches.latest("downloads","owner")).isEqualTo(result);
    }
    @Test void manualAndScheduledSearchesShareTheResultAndKeepOriginAndTemporaryCriteria() {
        var scheduled=preferences.get();
        var manual=new UpdatePreferences.Preferences(List.of(DownloadStatus.NOT_FOUND));
        var empty=new PageResponse<RevisionUpdates.Row>(List.of(),new PageResponse.PageMeta(0,1000,0,0,true,true,false,false));
        when(updates.search(scheduled,0,1000,"eplId,asc")).thenReturn(empty);
        when(updates.search(manual,0,1000,"eplId,asc")).thenReturn(empty);
        com.rlibanez.eplsync.events.EventContext.withOrigin(com.rlibanez.eplsync.events.EventContext.Origin.SCHEDULED,()->searches.downloads("owner"));
        assertThat(searches.latest("downloads","owner")).containsEntry("origin","SCHEDULED");
        var result=searches.searchDownloads("owner",manual);
        assertThat(result.origin()).isEqualTo(com.rlibanez.eplsync.events.EventContext.Origin.MANUAL);
        var saved=searches.latest("downloads","owner");assertThat(saved).containsEntry("origin","MANUAL");
        assertThat(saved.get("states")).isEqualTo(List.of("NOT_FOUND"));
        assertThat(((Map<?,?>)saved.get("criteria")).get("states")).isEqualTo(List.of("NOT_FOUND"));
        assertThat(preferences.get()).isEqualTo(scheduled);
        assertThat(searches.latest("downloads","other")).isNull();
    }
    @Test void storedResultsSurviveServiceRecreationAndCascadeOnUserDeletion() {
        var input=preferences.get();
        var empty=new PageResponse<RevisionUpdates.Row>(List.of(),new PageResponse.PageMeta(0,1000,0,0,true,true,false,false));
        when(updates.search(input,0,1000,"eplId,asc")).thenReturn(empty);
        searches.searchDownloads("owner",input);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings WHERE setting_key LIKE 'tasks.update-search.%'",Long.class)).isZero();
        var restored=new UpdateSearches(jdbc,updates,preferences,torrent,calibre,files);
        assertThat(restored.latest("downloads","owner")).isEqualTo(searches.latest("downloads","owner"));
        jdbc.update("DELETE FROM users WHERE id='owner'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM update_search_results",Long.class)).isZero();
        assertThat(restored.latest("downloads","owner")).isNull();
    }
    @Test void calibreSearchUsesTheSameLatestRevisionRuleAndIncludesFileAvailability() {
        jdbc.update("INSERT INTO catalog_books(epl_id,title,author,revision) VALUES(1,'Libro','Autor',3)");
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('one','g',true,1,1,1,'Libro','Autor'),('two','g',true,2,1,2,'Libro','Autor'),('duplicate','g',true,3,1,2,'Libro','Autor'),('other','g',true,4,2,1,'Ausente','Autor')");
        when(files.availability(List.of(2L))).thenReturn(List.of(new CalibreUpdateFlow.Availability(2,"READY")));
        assertThat(searches.calibre("owner")).containsEntry("updates",1);
        var saved=searches.latest("calibre","owner");var rows=(List<?>)saved.get("rows");assertThat(rows).hasSize(1);
        var row=(Map<?,?>)rows.getFirst();assertThat(((Number)row.get("calibreId")).longValue()).isEqualTo(2);
        assertThat(row.get("downloadAvailability")).isEqualTo("READY");verify(calibre).sync();
        assertThat(searches.latest("calibre","other")).isNull();
        when(calibre.sync()).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(()->searches.calibre("owner")).isInstanceOf(IllegalStateException.class);
        assertThat(searches.latest("calibre","owner")).isEqualTo(saved);
    }
}
