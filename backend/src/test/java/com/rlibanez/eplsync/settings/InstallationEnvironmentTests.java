package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.config.CoverCheckProperties;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class InstallationEnvironmentTests {
    private StandardEnvironment environment(Map<String,Object> values) {
        var yaml=new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", values));
        env.getPropertySources().addLast(new PropertiesPropertySource("application", yaml.getObject()));
        ConfigurationPropertySources.attach(env);
        return env;
    }
    @Test void requireHttpsEnvironmentControlsBothRequestPolicyAndCookie() {
        for(boolean required:new boolean[]{false,true}) {
            var env=environment(Map.of("EPLSYNC_SECURITY_REQUIRE_HTTPS",String.valueOf(required)));
            assertThat(env.getProperty("eplsync.security.require-https",Boolean.class)).isEqualTo(required);
            assertThat(Binder.get(env).bind("server.servlet.session.cookie.secure",Boolean.class).get()).isEqualTo(required);
        }
    }
    @Test void installationVariablesOverrideYamlIncludingListsDurationsAndCredentials() {
        var env=environment(Map.ofEntries(
            Map.entry("EPLSYNC_CATALOG_ZIPURL","https://example.org/books.zip"),
            Map.entry("EPLSYNC_CATALOG_COVERCHECK_CONNECTTIMEOUT","2s"),
            Map.entry("EPLSYNC_TORRENT_CONNECTTIMEOUT","7s"),
            Map.entry("EPLSYNC_TORRENT_BULK_BATCHSIZE","25"),
            Map.entry("EPLSYNC_TORRENT_DOWNLOAD_SAVEPATH","/downloads/books"),
            Map.entry("EPLSYNC_TORRENT_TRACKERS","udp://example.org:80/announce"),
            Map.entry("EPLSYNC_TORRENT_RENAME_PATTERN","{title} [{eplId}]"),
            Map.entry("EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE","session"),
            Map.entry("EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME","binding-user"),
            Map.entry("EPLSYNC_TORRENT_QBITTORRENT_AUTH_PASSWORD","literal$test"),
            Map.entry("EPLSYNC_TORRENT_QBITTORRENT_DOWNLOAD_TAGS","books,{language}"),
            Map.entry("EPLSYNC_TORRENT_QBITTORRENT_DOWNLOAD_AUTOMANAGEMENT","false")));
        var binder=Binder.get(env);
        var torrent=binder.bind("eplsync.torrent",TorrentProperties.class).get();
        var qbittorrent=binder.bind("eplsync.torrent.qbittorrent",QBittorrentProperties.class).get();
        var covers=binder.bind("eplsync.catalog.cover-check",CoverCheckProperties.class).get();
        assertThat(env.getRequiredProperty("eplsync.catalog.zip-url")).isEqualTo("https://example.org/books.zip");
        assertThat(covers.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(torrent.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(7));
        assertThat(torrent.getBulk().getBatchSize()).isEqualTo(25);
        assertThat(torrent.getDownload().getSavePath()).isEqualTo("/downloads/books");
        assertThat(torrent.getTrackers()).containsExactly("udp://example.org:80/announce");
        assertThat(torrent.getRename().getPattern()).isEqualTo("{title} [{eplId}]");
        assertThat(qbittorrent.getAuth().getUsername()).isEqualTo("binding-user");
        assertThat(qbittorrent.getAuth().getPassword()).isEqualTo("literal$test");
        assertThat(qbittorrent.getDownload().getTags()).containsExactly("books","{language}");
        assertThat(qbittorrent.getDownload().isAutoManagement()).isFalse();
    }
}
