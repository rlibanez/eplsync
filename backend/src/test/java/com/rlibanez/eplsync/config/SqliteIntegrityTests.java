package com.rlibanez.eplsync.config;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class SqliteIntegrityTests {
    @TempDir Path directory;
    @Test void startupRejectsDisabledEnforcementAndExistingOrphansWithoutDeletingData() throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("integrity.db");
        var disabled=new DriverManagerDataSource(url);
        var jdbc=new JdbcTemplate(disabled);
        jdbc.execute("CREATE TABLE parent(id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE child(parent_id INTEGER REFERENCES parent(id))");
        assertThatThrownBy(() -> SqliteIntegrityConfiguration.verify(disabled))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("foreign_keys=ON");
        jdbc.update("INSERT INTO child VALUES(123)");
        var enabled=new DriverManagerDataSource(url);
        var properties=new java.util.Properties(); properties.setProperty("foreign_keys","true");
        enabled.setConnectionProperties(properties);
        assertThatThrownBy(() -> SqliteIntegrityConfiguration.verify(enabled))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("referencias huérfanas");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM child",Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO parent VALUES(123)");
        SqliteIntegrityConfiguration.verify(enabled);
    }
}
