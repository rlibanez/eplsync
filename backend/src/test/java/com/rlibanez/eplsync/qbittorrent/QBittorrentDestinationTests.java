package com.rlibanez.eplsync.qbittorrent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QBittorrentDestinationTests {
    @Test void equivalentUrlsShareAnIdentityButDifferentEndpointsDoNot() {
        assertThat(QBittorrentDestination.normalize("HTTPS://QBIT.EXAMPLE:443/qbit/"))
            .isEqualTo("https://qbit.example/qbit");
        assertThat(QBittorrentDestination.normalize("http://[::1]:80/")) .isEqualTo("http://[::1]");
        for (String url : new String[]{"http://qbit.example/qbit", "https://other.example/qbit", "https://qbit.example:8443/qbit", "https://qbit.example/other"})
            assertThat(QBittorrentDestination.normalize(url)).isNotEqualTo("https://qbit.example/qbit");
    }
    @Test void acceptsDockerRegistryHostsWithoutConfusingTheirPortsOrIdentity() {
        assertThat(QBittorrentDestination.normalize("http://qbittorrent_epl:8092")).isEqualTo("http://qbittorrent_epl:8092");
        assertThat(QBittorrentDestination.normalize("HTTPS://QBIT_NODE.INTERNAL_NET:443/proxy/" )).isEqualTo("https://qbit_node.internal_net/proxy");
        assertThat(QBittorrentDestination.normalize("http://qbit_epl:80/")).isEqualTo("http://qbit_epl");
        assertThat(QBittorrentDestination.normalize("http://qbit_epl:8092")).isNotEqualTo(QBittorrentDestination.normalize("http://qbit-epl:8092"));
        assertThat(QBittorrentDestination.normalize("http://qbit_epl:8092")).isNotEqualTo(QBittorrentDestination.normalize("http://qbit_epl:8093"));
    }
    @Test void dockerHostsCannotBypassAuthorityAndPortRestrictions() {
        for(String url:new String[]{"http://user:private-password@qbit_epl:8092","http://qbit_epl:0","http://qbit_epl:65536","http://qbit_epl:-1","http://qbit_epl:","http://qbit_epl:abc","http://qbit_epl:80:90","http://qbit_epl?key=private-password","http://qbit_epl/#foo","http://qbit%5fepl:80","http://qbit_epl..local:80","http://-qbit_epl:80","http://qbit_epl-:80","http://qbit_epl\\evil:80","//qbit_epl:8092","file://qbit_epl/data"})
            assertThatThrownBy(()->QBittorrentDestination.normalize(url)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-password");
    }
    @Test void invalidUrlsNeverExposeUserInfoInErrors() {
        for (String url : new String[]{"http://user:private-password@host", "file:///tmp/foo", "http://host:0", "http://host:65536", "http://host/path?secret=foo", "http://host/#foo"})
            assertThatThrownBy(() -> QBittorrentDestination.normalize(url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-password");
    }
}
