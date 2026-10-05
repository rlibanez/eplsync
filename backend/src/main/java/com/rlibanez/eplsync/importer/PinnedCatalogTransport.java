package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/** Host/SNI/certificate validation use the URL hostname; only its already-validated IPs can be connected. */
final class PinnedCatalogTransport implements CatalogDownloadTransport {
    private static final Timeout TIMEOUT = Timeout.ofMinutes(5);

    static DnsResolver pinnedResolver(CatalogDownloadPolicy.Target target) {
        return new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!host.equalsIgnoreCase(target.host())) throw new UnknownHostException("Destino de descarga inesperado");
                return target.addresses();
            }
            @Override public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return target.host(); // Never perform a canonical/reverse lookup with fresh DNS results.
            }
        };
    }
    @Override public Result fetch(CatalogDownloadPolicy.Target target, BodyWriter writer) throws IOException, InterruptedException {
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(pinnedResolver(target))
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(TIMEOUT).setSocketTimeout(TIMEOUT).build())
            .build();
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(TIMEOUT)
                    .setConnectionRequestTimeout(TIMEOUT).setAuthenticationEnabled(false).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
                .build()) {
            var request = new HttpGet(target.uri());
            request.setHeader(HttpHeaders.USER_AGENT,"EplSync/1.0");
            var response = client.executeOpen(null,request,null);
            try {
                int status = response.getCode();
                var type = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
                String contentType = type == null ? "" : type.getValue().toLowerCase(Locale.ROOT);
                var location = response.getFirstHeader(HttpHeaders.LOCATION);
                var file = writer.write(status,contentType,
                    response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent());
                return new Result(status, location == null ? null : location.getValue(), contentType, file);
            } finally {
                // Do not drain arbitrary redirect/error bodies to reuse a connection. Each hop has its own client.
                client.close(CloseMode.IMMEDIATE);
                response.close();
            }
        } catch (InterruptedIOException ex) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Descarga del catálogo interrumpida");
            throw ex;
        }
    }
}
