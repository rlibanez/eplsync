package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MagnetLinkBuilderTests {
    @Test
    void normalizesBase32AndHexAndRejectsMalformedWholeTokens() {
        var builder = new MagnetLinkBuilder(new TorrentProperties());
        assertThat(builder.hashes("A".repeat(40) + ", " + "a".repeat(40)
                + "; 5IFYXMJPRHWKM4OBRE5ZJKLDSYADZ4MD\n" + "A".repeat(32)
                + " bad " + "B".repeat(41)))
                .containsExactly("A".repeat(40), "EA0B8BB12F89ECA671C1893B94A96396003CF183", "0".repeat(40));
        assertThat(builder.hashes(null)).isEmpty();
        assertThat(builder.hashes("  ")).isEmpty();
    }

    @Test
    void encodesNamesAndTrackerQueryStringsWithoutAddingParameters() {
        var properties = new TorrentProperties();
        properties.setTrackers(List.of(" https://example.com/announce?key=a&v=1 ",
                "https://example.com/announce?key=a&v=1", "udp://example.org:1337/announce"));
        var builder = new MagnetLinkBuilder(properties);
        assertThat(builder.build("A".repeat(40), 3L, "Bóvedas & acero + #1"))
                .isEqualTo("magnet:?xt=urn:btih:" + "A".repeat(40)
                        + "&dn=EPL_3_B%C3%B3vedas%20%26%20acero%20%2B%20%231"
                        + "&tr=https://example.com/announce%3Fkey%3Da%26v%3D1"
                        + "&tr=udp://example.org:1337/announce");
        properties.setTrackers(List.of());
        assertThat(builder.build("A".repeat(40), 3L, "Título")).doesNotContain("&tr=");
    }
    @Test
    void readableUrlsPreserveTheOriginalParameterValues() {
        var properties = new TorrentProperties();
        String tracker = "https://example.org:443/announce?token=a%2Fb&name=x+y";
        String title = "Título: uno/dos & tres = 100% + #fin";
        properties.setTrackers(List.of(tracker));
        String magnet = new MagnetLinkBuilder(properties).build("A".repeat(40), 3L, title);
        var uri = java.net.URI.create(magnet);
        String[] parameters = uri.getRawSchemeSpecificPart().substring(1).split("&");
        assertThat(parameters).hasSize(3);
        assertThat(java.net.URLDecoder.decode(parameters[1].substring(3),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("EPL_3_" + title);
        assertThat(java.net.URLDecoder.decode(parameters[2].substring(3),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(tracker);
        assertThat(uri.getRawFragment()).isNull();
    }

}
