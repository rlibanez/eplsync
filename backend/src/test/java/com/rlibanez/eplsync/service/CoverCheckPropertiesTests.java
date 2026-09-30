package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CoverCheckProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class CoverCheckPropertiesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CoverCheckProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void defaultsPreserveOriginalLimits() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(CoverCheckProperties.class);
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getBatchTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(properties.getConcurrency()).isEqualTo(4);
        });
    }

    @Test void bindsCustomDurationUnitsAndConcurrency() {
        runner.withPropertyValues("eplsync.catalog.cover-check.connect-timeout=1500ms",
                "eplsync.catalog.cover-check.request-timeout=10s", "eplsync.catalog.cover-check.batch-timeout=11s",
                "eplsync.catalog.cover-check.concurrency=8").run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(CoverCheckProperties.class);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(1500));
                    assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.getBatchTimeout()).isEqualTo(Duration.ofSeconds(11));
                    assertThat(properties.getConcurrency()).isEqualTo(8);
                });
    }

    @Test void rejectsInvalidAndContradictoryLimits() {
        for (String property : java.util.List.of("connect-timeout=0s", "connect-timeout=-1s",
                "connect-timeout=4s", "request-timeout=5s", "request-timeout=2s",
                "batch-timeout=2s", "batch-timeout=6m", "concurrency=0", "concurrency=33")) {
            runner.withPropertyValues("eplsync.catalog.cover-check." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
