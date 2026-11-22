package com.rlibanez.eplsync.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class SavedSearchScopeMigrationTests {
    @Test void preservesExistingIdsFiltersNamesAndClassifiesDiscoveryThenAllowsIndependentNames() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var source=new SingleConnectionDataSource(connection,true);var jdbc=new JdbcTemplate(source);
            jdbc.execute("PRAGMA foreign_keys=ON");jdbc.execute("CREATE TABLE users(id TEXT PRIMARY KEY)");jdbc.update("INSERT INTO users VALUES('user')");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_7_0_1__catalog_saved_searches.sql")).execute(source);
            String discovery="{\"discoverySource\":[\"BOTH\"],\"discoveryCriteria\":[\"AUTHOR\",\"LANGUAGE\"],\"discoveryMatch\":[\"ANY\"]}";
            jdbc.update("INSERT INTO catalog_saved_searches VALUES('catalog','user','Catálogo','{}')");
            jdbc.update("INSERT INTO catalog_saved_searches VALUES('discovery','user','Descubrir',?)",discovery);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_8_8_1__saved_search_scopes.sql")).execute(source);
            assertThat(jdbc.queryForObject("SELECT scope FROM catalog_saved_searches WHERE id='catalog'",String.class)).isEqualTo("CATALOG");
            assertThat(jdbc.queryForObject("SELECT scope FROM catalog_saved_searches WHERE id='discovery'",String.class)).isEqualTo("DISCOVERY");
            assertThat(jdbc.queryForObject("SELECT filters FROM catalog_saved_searches WHERE id='discovery'",String.class)).isEqualTo(discovery);
            assertThat(jdbc.queryForObject("SELECT name FROM catalog_saved_searches WHERE id='discovery'",String.class)).isEqualTo("Descubrir");
            jdbc.update("INSERT INTO catalog_saved_searches VALUES('same-name','user','DISCOVERY','Catálogo',?)",discovery);
            assertThatThrownBy(()->jdbc.update("INSERT INTO catalog_saved_searches VALUES('duplicate','user','DISCOVERY','Catálogo',?)",discovery)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("SQLITE_CONSTRAINT_UNIQUE");
            jdbc.update("DELETE FROM users WHERE id='user'");assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_saved_searches",Long.class)).isZero();
        }
    }
}
