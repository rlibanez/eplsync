package com.rlibanez.eplsync.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DatabaseMigrationConfiguration {
    @Bean
    FlywayConfigurationCustomizer rejectUnknownSchemaVersions() {
        // Do not silently run an older application against migrations it does not know.
        return configuration -> configuration.ignoreMigrationPatterns(new String[0]);
    }
}
