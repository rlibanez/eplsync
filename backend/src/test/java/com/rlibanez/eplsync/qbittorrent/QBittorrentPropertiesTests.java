package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QBittorrentPropertiesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({TorrentProperties.class, QBittorrentProperties.class})
    @Import({QBittorrentClient.class, TorrentClientService.class})
    static class Config {}

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner().withUserConfiguration(Config.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            try {
                for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
                    sources.addLast(source);
                }
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
            // Aislar las pruebas de los valores locales que el usuario edite en application.yaml.
            var testEnvironment = new java.util.HashMap<String, Object>();
            testEnvironment.put("EPLSYNC_TORRENT_ENABLED", "false");
            testEnvironment.put("EPLSYNC_TORRENT_CLIENT", "qbittorrent");
            testEnvironment.put("EPLSYNC_TORRENT_BASEURL", "http://localhost:8080");
            testEnvironment.put("EPLSYNC_TORRENT_RENAME_ENABLED", "true");
            testEnvironment.put("EPLSYNC_TORRENT_RENAME_PATTERN", "EPL_{eplId}_{title}");
            testEnvironment.put("EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE", "auto");
            testEnvironment.put("EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY", "");
            testEnvironment.put("EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME", "");
            testEnvironment.put("EPLSYNC_TORRENT_QBITTORRENT_AUTH_PASSWORD", "");
            testEnvironment.putAll(environment);
            sources.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", testEnvironment));
        });
    }

    @Test
    void defaultsDisableConnectionAndSetRenamePattern() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(TorrentProperties.class);
            assertThat(p.isEnabled()).isFalse();
            assertThat(context.getBean(QBittorrentProperties.class).getAuth().getMode()).isEqualTo(QBittorrentProperties.AuthMode.AUTO);
            assertThat(p.getRename().isEnabled()).isTrue();
            assertThat(p.getRename().getPattern()).isEqualTo("EPL_{eplId}_{title}");
        });
    }

    @Test
    void bindsDockerEnvironmentIncludingPasswordAndPatternLiterally() {
        runner(Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_BASEURL", "https://example.com:8443/qbit/",
                "EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE", "session", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME", "user", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_PASSWORD", " p&+=ss ",
                "EPLSYNC_TORRENT_RENAME_PATTERN", "{author} - {title} [{eplId}]", "EPLSYNC_TORRENT_REQUESTTIMEOUT", "3s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var p = context.getBean(TorrentProperties.class);
                    assertThat(p.getBaseUrl()).isEqualTo("https://example.com:8443/qbit/");
                    assertThat(context.getBean(QBittorrentProperties.class).getAuth().getPassword()).isEqualTo(" p&+=ss ");
                    assertThat(context.getBean(QBittorrentProperties.class).getAuth().getMode()).isEqualTo(QBittorrentProperties.AuthMode.SESSION);
                    assertThat(p.getRename().getPattern()).isEqualTo("{author} - {title} [{eplId}]");
                    assertThat(p.getRequestTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
                });
    }

    @Test
    void missingCredentialsAllowStartupAndFailOnlyWhenCheckingConnection() {
        for (var environment : java.util.List.<Map<String, Object>>of(
                Map.of("EPLSYNC_TORRENT_ENABLED", "true"),
                Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME", "only-user"),
                Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE", "api-key"),
                Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY", "key",
                        "EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE", "session"))) {
            runner(environment).run(context -> {
                assertThat(context).hasNotFailed();
                assertThatThrownBy(context.getBean(TorrentClientService.class)::checkConnection)
                        .isInstanceOfSatisfying(com.rlibanez.eplsync.exception.TorrentOperationException.class,
                                ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
            });
        }
        runner(Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY", "key"))
                .run(context -> assertThat(context).hasNotFailed());
        runner(Map.of("EPLSYNC_TORRENT_ENABLED", "false", "EPLSYNC_TORRENT_BASEURL", "not-a-url"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void onlyInstantiatesSelectedAdapterAndRejectsUnsupportedSelection() {
        runner(Map.of("EPLSYNC_TORRENT_CLIENT", "future-client"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(QBittorrentClient.class);
                    assertThat(context.getBean(TorrentClientService.class).checkConnection().connected()).isFalse();
                });
        runner(Map.of("EPLSYNC_TORRENT_CLIENT", "future-client", "EPLSYNC_TORRENT_ENABLED", "true"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidUrlTimeoutAndRenameTokens() {
        for (String url : new String[] {"ftp://example.com", "https://user:secret@example.com", "https://example.com?key=secret", "https://example.com:99999", "bad"}) {
            runner(Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY", "key", "EPLSYNC_TORRENT_BASEURL", url))
                    .run(context -> assertThat(context).hasFailed());
        }
        for (String pattern : new String[] {"", "{unknown}", "EPL_{title", "{{title}}"}) {
            runner(Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY", "key", "EPLSYNC_TORRENT_RENAME_PATTERN", pattern))
                    .run(context -> assertThat(context).hasFailed());
        }
        runner(Map.of("EPLSYNC_TORRENT_ENABLED", "true", "EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY", "key", "EPLSYNC_TORRENT_REQUESTTIMEOUT", "0s"))
                .run(context -> assertThat(context).hasFailed());
    }
}
