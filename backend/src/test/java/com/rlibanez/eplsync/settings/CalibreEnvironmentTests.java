package com.rlibanez.eplsync.settings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:",
    "eplsync.torrent.bulk.worker-enabled=false", "eplsync.updates.scheduler-enabled=false",
    "EPLSYNC_CALIBRE_URL=http://calibre:8080", "EPLSYNC_CALIBRE_BROWSER_URL=https://books.example",
    "EPLSYNC_CALIBRE_USERNAME=reader", "EPLSYNC_CALIBRE_PASSWORD=environment-secret", "EPLSYNC_CALIBRE_LIBRARY=main"})
class CalibreEnvironmentTests {
    @Autowired CalibreSettings settings;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void installationVariablesConfigureCalibreWithoutPersistingCredentials() {
        assertThat(settings.view()).isEqualTo(new CalibreSettings.View("http://calibre:8080", "reader", true, "main", "https://books.example"));
        assertThat(settings.connection().password()).isEqualTo("environment-secret");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings WHERE setting_key='calibre.connection'", Integer.class)).isZero();
    }
}
