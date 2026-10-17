package com.rlibanez.eplsync.tasks;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SavedUpdateResultsTests {
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    SavedUpdateResults results;
    JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() throws Exception {
        source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(source);
        com.rlibanez.eplsync.ordering.TextOrdering.register(source.getConnection().unwrap(org.sqlite.SQLiteConnection.class));
        jdbc.execute("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY,setting_value TEXT)");
        jdbc.execute("CREATE TABLE update_search_results(user_id TEXT,search_type TEXT,searched_at INTEGER,origin TEXT,criteria_json TEXT,results_json TEXT,PRIMARY KEY(user_id,search_type))");
        jdbc.update("INSERT INTO app_settings VALUES('updates.search-context.downloads','d'),('updates.search-context.calibre','c')");
        results=new SavedUpdateResults(jdbc);
    }
    @AfterEach void close() {source.destroy();}
    void save(String type,List<?> rows) {
        jdbc.update("INSERT OR REPLACE INTO update_search_results VALUES('owner',?,123,'MANUAL',?,?)",type,
            json.writeValueAsString(Map.of("context",type.equals("downloads")?"d":"c")),json.writeValueAsString(rows));
    }
    @Test void metadataHasNoRowsAndPagesOnlyTheSavedSnapshot() {
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<1205;i++) rows.add(Map.of("eplId",i+1,"title","Libro "+(i+1),"status",i%2==0?"DOWNLOADED":"NOT_FOUND"));
        save("downloads",rows);
        assertThat(results.latest("downloads","owner")).doesNotContainKey("rows").containsEntry("searchedAt",123L);
        var first=results.page("downloads","owner",123,0,1000,"eplId,asc",Map.of());
        var last=results.page("downloads","owner",123,1,1000,"eplId,asc",Map.of());
        assertThat(first.items()).hasSize(1000);assertThat(last.items()).hasSize(205);
        assertThat(first.meta().totalItems()).isEqualTo(1205);assertThat(first.meta().hasNext()).isTrue();
        assertThat(last.meta().hasNext()).isFalse();assertThat(last.items().getFirst().get("eplId")).isEqualTo(1001);
        assertThat(results.latest("downloads","other")).isNull();
        assertThatThrownBy(()->results.page("downloads","other",123,0,20,"title,asc",Map.of())).hasMessageContaining("ha cambiado");
    }
    @Test void filtersMultipleStatusesAndSortsNumericallyBeforePaging() {
        save("downloads",List.of(Map.of("eplId",3,"status","DOWNLOADED","registeredRevision",1.2),Map.of("eplId",1,"status","ERROR","registeredRevision",2.0),Map.of("eplId",2,"status","NOT_FOUND","registeredRevision",1.3)));
        var page=results.page("downloads","owner",123,0,1,"registeredRevision,desc",Map.of("status","DOWNLOADED,NOT_FOUND"));
        assertThat(page.meta().totalItems()).isEqualTo(2);assertThat(page.items().getFirst().get("eplId")).isEqualTo(2);
        var excluded=results.page("downloads","owner",123,0,1,"eplId,asc",Map.of("status","DOWNLOADED,NOT_FOUND","statusOperator","ne"));
        assertThat(excluded.meta().totalItems()).isEqualTo(1);assertThat(excluded.items().getFirst().get("eplId")).isEqualTo(1);
        assertThatThrownBy(()->results.page("downloads","owner",123,0,20,"eplId,asc",Map.of("statusOperator","invalid"))).hasMessageContaining("Operador");
    }
    @Test void calibreFiltersPreserveUnicodeCaseAndLiteralSearchCharacters() {
        save("calibre",List.of(Map.of("calibreId",1,"eplId",5,"title","ÁRBOL 100%_","authors","Íñigo","downloadAvailability","READY"),Map.of("calibreId",2,"eplId",6,"title","Barco","authors","Autor","downloadAvailability","MISSING")));
        var page=results.page("calibre","owner",123,0,20,"title,asc",Map.of("title","árbol 100%_","author","ÍÑIGO","eplId","5","availability","READY"));
        assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().get("calibreId")).isEqualTo(1);
        assertThat(results.page("calibre","owner",123,0,20,"title,asc",Map.of("title","' OR 1=1 --")).items()).isEmpty();
        var negative=results.page("calibre","owner",123,0,1,"title,asc",Map.of("title","árbol 100%_","titleOperator","ne","author","ÍÑIGO","authorOperator","ne"));
        assertThat(negative.meta().totalItems()).isEqualTo(1);assertThat(negative.items().getFirst().get("eplId")).isEqualTo(6);
    }
    @Test void rejectsInvalidPagingOrderingFiltersAndChangedGenerations() {
        save("downloads",List.of());
        assertThatThrownBy(()->results.page("downloads","owner",123,-1,20,"title,asc",Map.of())).hasMessageContaining("page");
        assertThatThrownBy(()->results.page("downloads","owner",123,0,1001,"title,asc",Map.of())).hasMessageContaining("size");
        assertThatThrownBy(()->results.page("downloads","owner",123,0,20,"title;DROP TABLE users,asc",Map.of())).hasMessageContaining("Ordenación");
        assertThatThrownBy(()->results.page("downloads","owner",123,0,20,"title,asc",Map.of("status","INVALID"))).hasMessageContaining("Estado");
        assertThatThrownBy(()->results.page("downloads","owner",122,0,20,"title,asc",Map.of())).hasMessageContaining("ha cambiado");
        jdbc.update("UPDATE app_settings SET setting_value='other' WHERE setting_key='updates.search-context.downloads'");
        assertThat(results.latest("downloads","owner")).isNull();
        assertThatThrownBy(()->results.page("downloads","owner",123,0,20,"title,asc",Map.of())).hasMessageContaining("ha cambiado");
    }
    @Test void serializationCountsUtf8BytesAndStopsBeforeOversizedSnapshotsAreBuilt() {
        assertThat(results.encode(List.of("Árbol"),100)).isEqualTo("[\"Árbol\"]");
        assertThatThrownBy(()->results.encode("é".repeat(100),150)).hasMessageContaining("16 MiB");
        var budget=results.budget();
        String text="é".repeat(1024*1024);
        assertThatThrownBy(()->{for(int i=0;i<9;i++) budget.add(Map.of("title",text));}).hasMessageContaining("16 MiB");
    }
    @Test void oversizedLegacyResultsAreNotLoadedOrReturned() {
        save("downloads",List.of());
        jdbc.update("UPDATE update_search_results SET results_json=?", "[\""+"a".repeat(SavedUpdateResults.MAX_BYTES)+"\"]");
        assertThat(results.latest("downloads","owner")).isNull();
        assertThatThrownBy(()->results.page("downloads","owner",123,0,20,"title,asc",Map.of())).hasMessageContaining("ha cambiado");
    }
}
