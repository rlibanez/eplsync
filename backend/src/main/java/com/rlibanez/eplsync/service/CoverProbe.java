package com.rlibanez.eplsync.service;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import com.rlibanez.eplsync.network.PublicNetworkAddresses;

@Component
public class CoverProbe {
    public record Result(Boolean available, Integer httpStatus, String reason) {}
    protected record Target(URI uri, InetAddress[] addresses) {
        public Target { addresses = addresses.clone(); }
        @Override public InetAddress[] addresses() { return addresses.clone(); }
        String host() { String host=uri.getHost(); return host.startsWith("[") ? host.substring(1,host.length()-1) : host; }
    }
    protected record Response(int statusCode, String contentType, String location, InputStream body,
                              Runnable abort) implements AutoCloseable {
        @Override public void close() throws java.io.IOException { abort.run(); body.close(); }
    }
    // A stuck platform DNS lookup cannot create unbounded threads or a queue of pending probes.
    private static final ExecutorService DNS = new ThreadPoolExecutor(0,32,30,TimeUnit.SECONDS,
            new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("cover-dns-",0).factory());
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("cover-deadline-",0).factory());
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final Duration requestTimeout;
    private final Duration connectTimeout;

    public CoverProbe(com.rlibanez.eplsync.config.CoverCheckProperties properties) {
        requestTimeout=properties.getRequestTimeout(); connectTimeout=properties.getConnectTimeout();
    }
    @jakarta.annotation.PreDestroy void shutdown() { /* Each hop closes its own HTTP client. */ }

    public Result check(String url) {
        long deadline=System.nanoTime()+requestTimeout.toNanos();
        try {
            URI uri=URI.create(url);
            for(int redirects=0;redirects<=3;redirects++) {
                var target=validate(uri,remaining(deadline));
                try(var response=request(target,remaining(deadline))) {
                    int status=response.statusCode();
                    if(status==404 || status==410) return new Result(false,status,"NOT_FOUND");
                    if(status==301 || status==302 || status==303 || status==307 || status==308) {
                        if(response.location()==null) return new Result(null,status,"INVALID_REDIRECT");
                        uri=uri.resolve(response.location()); continue;
                    }
                    String type=response.contentType().toLowerCase(Locale.ROOT);
                    if(status==200 && type.startsWith("image/")) return new Result(true,status,"AVAILABLE");
                    return new Result(null,status,status==200 ? "NOT_AN_IMAGE" : "HTTP_ERROR");
                }
            }
            return new Result(null,null,"TOO_MANY_REDIRECTS");
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); return new Result(null,null,"INTERRUPTED");
        } catch(java.net.SocketTimeoutException | java.net.http.HttpTimeoutException ex) {
            return new Result(null,null,"TIMEOUT");
        } catch(IllegalArgumentException ex) {
            return new Result(null,null,"INVALID_URL");
        } catch(java.io.IOException ex) {
            return new Result(null,null,System.nanoTime()>=deadline ? "TIMEOUT" : "NETWORK_ERROR");
        }
    }
    private Duration remaining(long deadline) throws java.net.SocketTimeoutException {
        long nanos=deadline-System.nanoTime();
        if(nanos<=0) throw new java.net.SocketTimeoutException();
        return Duration.ofNanos(nanos);
    }
    protected InetAddress[] resolve(String host) throws UnknownHostException { return InetAddress.getAllByName(host); }
    protected Target validate(URI uri,Duration timeout) throws java.io.IOException,InterruptedException {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                || uri.getPort()==0 || uri.getPort()>65535 || uri.getHost().contains("%"))
            throw new IllegalArgumentException("Invalid cover URL");
        String host=uri.getHost(); if(host.startsWith("[")) host=host.substring(1,host.length()-1);
        final String name=host;
        Future<InetAddress[]> lookup;
        try { lookup=DNS.submit(() -> resolve(name)); }
        catch(RejectedExecutionException ex) { throw new java.io.IOException("DNS capacity exhausted"); }
        InetAddress[] addresses;
        try { addresses=lookup.get(timeout.toNanos(),TimeUnit.NANOSECONDS); }
        catch(TimeoutException ex) { throw new java.net.SocketTimeoutException(); }
        catch(ExecutionException ex) { throw new java.io.IOException("DNS lookup failed"); }
        finally { lookup.cancel(true); }
        if(addresses==null || addresses.length==0) throw new IllegalArgumentException("No cover addresses");
        for(var address:addresses) if(address==null || !PublicNetworkAddresses.isPublicAddress(address))
            throw new IllegalArgumentException("Cover URLs must use public addresses");
        return new Target(uri,addresses);
    }
    static DnsResolver pinnedResolver(Target target) {
        return new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if(!host.equalsIgnoreCase(target.host())) throw new UnknownHostException("Unexpected cover destination");
                return target.addresses();
            }
            public String resolveCanonicalHostname(String host) throws UnknownHostException { resolve(host); return target.host(); }
        };
    }
    protected Response request(Target target,Duration timeout) throws java.io.IOException,InterruptedException {
        var limit=Timeout.ofMilliseconds(Math.max(1,timeout.toMillis()));
        var connect=Timeout.ofMilliseconds(Math.max(1,Math.min(connectTimeout.toMillis(),timeout.toMillis())));
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinnedResolver(target))
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(connect).setSocketTimeout(limit).build()).build();
        var client=HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(limit).setConnectionRequestTimeout(limit)
                        .setAuthenticationEnabled(false).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
                .disableContentCompression().build();
        var request=new HttpGet(target.uri());
        request.setHeader("User-Agent","EplSync/1.0"); request.setHeader("Accept","image/*");
        var cancellation=DEADLINES.schedule(request::cancel,timeout.toNanos(),TimeUnit.NANOSECONDS);
        try {
            var response=client.executeOpen(null,request,null);
            var type=response.getFirstHeader("Content-Type"); var location=response.getFirstHeader("Location");
            return new Response(response.getCode(),type==null ? "" : type.getValue(),location==null ? null : location.getValue(),
                    response.getEntity()==null ? InputStream.nullInputStream() : response.getEntity().getContent(),
                    () -> { cancellation.cancel(false); client.close(CloseMode.IMMEDIATE); });
        } catch(java.io.IOException | RuntimeException ex) {
            cancellation.cancel(false); client.close(CloseMode.IMMEDIATE); throw ex;
        }
    }
}
