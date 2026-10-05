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
        return fetch(target, writer, new DownloadBudget(CatalogImportLimits.DOWNLOAD_TIME));
    }
    @Override public Result fetch(CatalogDownloadPolicy.Target target, BodyWriter writer, DownloadBudget budget)
            throws IOException, InterruptedException {
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(pinnedResolver(target))
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(TIMEOUT).setSocketTimeout(TIMEOUT).build())
            .build();
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(TIMEOUT)
                    .setConnectionRequestTimeout(TIMEOUT).setAuthenticationEnabled(false).build())
                .disableContentCompression().disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
                .build()) {
            var request = new HttpGet(target.uri());
            request.setHeader(HttpHeaders.USER_AGENT,"EplSync/1.0");
            var cancellation = budget.cancelAtDeadline(request::cancel);
            try {
                var response = client.executeOpen(null,request,null);
                boolean bodyCompleted = false;
                try {
                    int status = response.getCode();
                    if (status >= 200 && status < 300 && response.getEntity() != null
                            && response.getEntity().getContentLength() > CatalogImportLimits.ZIP_BYTES)
                        throw new com.rlibanez.eplsync.exception.CatalogDownloadException("El ZIP descargado supera el tamaño máximo permitido (128 MiB)");
                    var type = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
                    String contentType = type == null ? "" : type.getValue().toLowerCase(Locale.ROOT);
                    var location = response.getFirstHeader(HttpHeaders.LOCATION);
                    var file = writer.write(status,contentType,
                        response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent());
                    bodyCompleted = status >= 200 && status < 300;
                    return new Result(status, location == null ? null : location.getValue(), contentType, file);
                } finally {
                    // Do not drain arbitrary redirect/error bodies to reuse a connection. Each hop has its own client.
                    client.close(CloseMode.IMMEDIATE);
                    try { response.close(); }
                    catch (IOException cleanup) {
                        // A failed response must retain its rejection reason, even if closing a partial body fails.
                        if (bodyCompleted) throw cleanup;
                    }
                }
            } finally { cancellation.cancel(false); }
        } catch (InterruptedIOException ex) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Descarga del catálogo interrumpida");
            throw ex;
        }
    }
}
