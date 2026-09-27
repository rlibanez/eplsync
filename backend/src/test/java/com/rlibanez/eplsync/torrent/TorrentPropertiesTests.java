package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TorrentPropertiesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TorrentProperties.class)
    static class Config {}

    private ApplicationContextRunner runner(String value) {
        return new ApplicationContextRunner().withUserConfiguration(Config.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            try {
                for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
                    sources.addLast(source);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            if (value != null) sources.addFirst(new SystemEnvironmentPropertySource("testEnvironment",
                    Map.of("EPLSYNC_TORRENT_TRACKERS", value)));
        });
    }

    @Test
    void loadsYamlAndReplacesListFromDockerEnvironmentIncludingEmptyValue() {
        runner(null).run(context -> assertThat(context.getBean(TorrentProperties.class).getTrackers())
                .containsExactly("udp://tracker.opentrackr.org:1337/announce"));
        runner("https://example.com/announce,udp://example.org:80/announce").run(context ->
                assertThat(context.getBean(TorrentProperties.class).getTrackers())
                        .containsExactly("https://example.com/announce", "udp://example.org:80/announce"));
        runner("").run(context -> assertThat(context.getBean(TorrentProperties.class).getTrackers()).isEmpty());
    }

    @Test
    void rejectsInvalidConfigurationAtStartup() {
        for (String tracker : new String[] {"ftp://example.com/file", "not-a-url", "udp://example.com", "https://example.com:99999/announce"}) {
            runner(tracker).run(context -> assertThat(context).hasFailed());
        }
    }
}
