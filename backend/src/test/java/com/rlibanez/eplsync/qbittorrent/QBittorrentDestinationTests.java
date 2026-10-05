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
    @Test void invalidUrlsNeverExposeUserInfoInErrors() {
        for (String url : new String[]{"http://user:private-password@host", "file:///tmp/foo", "http://host:0", "http://host:65536", "http://host/path?secret=foo", "http://host/#foo"})
            assertThatThrownBy(() -> QBittorrentDestination.normalize(url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-password");
    }
}
