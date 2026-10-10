package com.rlibanez.eplsync.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enabling enforcement does not validate previously persisted references. */
@Configuration(proxyBeanMethods = false)
public class SqliteIntegrityConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    InitializingBean sqliteForeignKeyIntegrity(DataSource source) {
        return () -> verify(source);
    }

    static void verify(DataSource source) throws java.sql.SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var enabled = statement.executeQuery("PRAGMA foreign_keys")) {
                if (!enabled.next() || enabled.getInt(1) != 1)
                    throw new IllegalStateException("SQLite requiere foreign_keys=ON en cada conexión");
            }
            try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (violations.next())
                    throw new IllegalStateException("SQLite contiene referencias huérfanas; revisa la integridad de la base de datos antes de iniciar EPL Sync");
            }
        }
    }
}
