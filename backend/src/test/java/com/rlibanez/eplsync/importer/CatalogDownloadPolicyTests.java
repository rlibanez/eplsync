package com.rlibanez.eplsync.importer;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogDownloadPolicyTests {
    @Test void acceptsHttpHttpsAnyHostAndCustomPortsWithoutChangingSignedQueryStrings() {
        assertThat(FileDownloader.validateUrl("HTTP://192.168.2.2:6065/data/epublibre_csv.zip").toString())
            .isEqualTo("http://192.168.2.2:6065/data/epublibre_csv.zip");
        assertThat(FileDownloader.validateUrl("https://FILES.example:443/a%2Fb.zip?token=a%2Fb%2Bc&other=%252F").toString())
            .isEqualTo("https://files.example/a%2Fb.zip?token=a%2Fb%2Bc&other=%252F");
        assertThat(FileDownloader.validateUrl("https://[::1]:8443/catalog.zip").getHost()).isEqualTo("[::1]");
    }
    @Test void invalidUrlsAndEmbeddedCredentialsAreRejectedWithoutDisclosingThem() {
        for (String value : new String[]{"file:///tmp/secret", "ftp://host/archive", "http://user:private-password@host/file",
                "http://host:0", "http://host:65536", "http://host/zip#private-token", "http://", "http://host/invalid path"})
            assertThatThrownBy(() -> FileDownloader.validateUrl(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-password").hasMessageNotContaining("private-token");
    }
    @Test void classifiesIpv4Ipv6AndMappedSpecialPurposeAddresses() throws Exception {
        for (String value : new String[]{"0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
                "192.168.2.2", "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
                "::", "::1", "fc00::1", "fd00::1", "fe80::1", "fec0::1", "ff02::1", "2001:db8::1", "2002:7f00:1::", "64:ff9b::7f00:1", "3fff::1"})
            assertThat(CatalogDownloadPolicy.isPublicAddress(InetAddress.getByName(value))).as(value).isFalse();
        for (String value : new String[]{"8.8.8.8", "93.184.216.34", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
            assertThat(CatalogDownloadPolicy.isPublicAddress(InetAddress.getByName(value))).as(value).isTrue();
        byte[] mapped = new byte[16]; mapped[10] = (byte)255; mapped[11] = (byte)255; mapped[12] = 127; mapped[15] = 1;
        assertThat(CatalogDownloadPolicy.isPublicAddress(Inet6Address.getByAddress(null,mapped,-1))).isFalse();
        mapped[12] = 8; mapped[13] = 8; mapped[14] = 8; mapped[15] = 8;
        assertThat(CatalogDownloadPolicy.isPublicAddress(Inet6Address.getByAddress(null,mapped,-1))).isTrue();
    }
    @Test void directLocalTargetsAreAllowedButMixedOrInternalPublicRedirectTargetsAreNot() throws Exception {
        var uri = FileDownloader.validateUrl("http://any-domain.example/catalog.zip");
        var local = new CatalogDownloadPolicy(host -> new InetAddress[]{InetAddress.getByName("192.168.2.2")});
        assertThat(local.resolve(uri,false).publicOnly()).isFalse();
        assertThatThrownBy(() -> local.resolve(uri,true)).hasMessageContaining("destino público");
        var mixed = new CatalogDownloadPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")});
        assertThatThrownBy(() -> mixed.resolve(uri,true)).hasMessageContaining("destino público");
        var publicTarget = new CatalogDownloadPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        assertThat(publicTarget.resolve(uri,true).publicOnly()).isTrue();
    }
    @Test void dnsFailuresDoNotExposeResolverDiagnostics() {
        var policy = new CatalogDownloadPolicy(host -> { throw new UnknownHostException("private diagnostic"); });
        assertThatThrownBy(() -> policy.resolve(FileDownloader.validateUrl("http://host.example/catalog.zip"),false))
            .hasMessage("No se pudo resolver el servidor del catálogo").hasNoCause();
    }
}
