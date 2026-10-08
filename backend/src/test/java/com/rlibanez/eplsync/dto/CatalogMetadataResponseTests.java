package com.rlibanez.eplsync.dto;

import com.rlibanez.eplsync.model.CatalogMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogMetadataResponseTests {
    @Test void stripsPrivateUrlComponentsWithoutChangingStoredMetadata() {
        var metadata = new CatalogMetadata();
        var url = "https://user:password@example.org:8443/catalog%20full.zip?token=secret&dl=1#private";
        metadata.setSourceUrl(url);
        assertThat(CatalogMetadataResponse.from(metadata,false).sourceUrl())
                .isEqualTo("https://example.org:8443/catalog%20full.zip");
        assertThat(CatalogMetadataResponse.from(metadata,true).sourceUrl()).isEqualTo(url);
        assertThat(metadata.getSourceUrl()).isEqualTo(url);
    }
    @Test void handlesIpv6MissingAndInvalidLegacyUrlsSafely() {
        var metadata = new CatalogMetadata();
        metadata.setSourceUrl("http://[::1]:8080/data/catalog.zip?secret=x");
        assertThat(CatalogMetadataResponse.from(metadata,false).sourceUrl())
                .isEqualTo("http://[::1]:8080/data/catalog.zip");
        for (String url : new String[]{null,"not a valid URL?secret=x","file:///tmp/private"}) {
            metadata.setSourceUrl(url);
            assertThat(CatalogMetadataResponse.from(metadata,false).sourceUrl()).isNull();
        }
    }
}
