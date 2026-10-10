package com.rlibanez.eplsync.ordering;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TextCollationConnectionTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("ordering.db"));
        registry.add("spring.datasource.hikari.maximum-pool-size",()->2);
    }
    @Autowired DataSource source;
    private void verify(java.sql.Connection connection) throws Exception {
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT 'Árbol' COLLATE EPL_TEXT = 'arbol', 'Nube' COLLATE EPL_TEXT < 'Ñandú'")) {
            assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1);assertThat(result.getInt(2)).isEqualTo(1);
        }
    }
    @Test void registersOnBothPoolConnectionsAndOnReplacementConnections() throws Exception {
        try(var first=source.getConnection();var second=source.getConnection()) {verify(first);verify(second);}
        source.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        try(var first=source.getConnection();var second=source.getConnection()) {verify(first);verify(second);}
    }
}
