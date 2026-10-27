package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import java.io.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import static com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.*;

/** Only Docker registry hosts use this transport. TLS verification stays enabled; writes are never retried. */
final class RegistryHostTransport implements AutoCloseable {
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"torrent-registry-timeout");t.setDaemon(true);return t;});
    private final BasicCookieStore cookies=new BasicCookieStore();
    private final CloseableHttpClient api,session;
    private final HttpHost target;
    RegistryHostTransport(TorrentProperties properties,DnsResolver resolver) {
        var endpoint=QBittorrentDestination.parse(properties.getBaseUrl());
        target=new HttpHost(endpoint.scheme(),endpoint.host(),endpoint.port());
        api=client(properties,resolver,false);session=client(properties,resolver,true);
    }
    private CloseableHttpClient client(TorrentProperties properties,DnsResolver resolver,boolean useCookies) {
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1,properties.getConnectTimeout().toMillis())))
            .setSocketTimeout(Timeout.ofMilliseconds(Math.max(1,properties.getRequestTimeout().toMillis()))).build());
        if(resolver!=null) manager.setDnsResolver(resolver);
        var builder=HttpClients.custom().setConnectionManager(manager.build()).disableRedirectHandling().disableAutomaticRetries().disableContentCompression();
        if(useCookies) builder.setDefaultCookieStore(cookies);else builder.disableCookieManagement();
        return builder.build();
    }
    boolean hasCookies() {return !cookies.getCookies().isEmpty();}
    void clearCookies() {cookies.clear();}
    QBittorrentRequest.Response<String> text(QBittorrentRequest request,boolean useSession,long maximum) throws IOException {
        var response=stream(request,useSession,maximum);
        try(var body=response.body()) {return new QBittorrentRequest.Response<>(response.statusCode(),new String(body.readAllBytes(),StandardCharsets.UTF_8));}
    }
    QBittorrentRequest.Response<InputStream> stream(QBittorrentRequest data,boolean useSession,long maximum) throws IOException {
        if(Thread.currentThread().isInterrupted()) throw new IOException(new QBittorrentConnectionException(INTERRUPTED));
        var request=new HttpUriRequestBase(data.method(),data.uri());data.headers().forEach(request::setHeader);
        if(data.body()!=null) request.setEntity(new StringEntity(data.body(),ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.max(1,data.timeout().toMillis())))
            .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1,data.timeout().toMillis()))).build());
        var expired=new AtomicBoolean();
        var timer=TIMER.schedule(()->{expired.set(true);request.cancel();},data.timeout().toNanos(),TimeUnit.NANOSECONDS);
        ClassicHttpResponse response=null;
        try {
            response=(useSession?session:api).executeOpen(target,request,null);
            var entity=response.getEntity();
            if(entity!=null && entity.getContentLength()>maximum) throw new QBittorrentConnectionException(RESPONSE_TOO_LARGE);
            var input=entity==null?InputStream.nullInputStream():entity.getContent();
            return new QBittorrentRequest.Response<>(response.getCode(),new LimitedStream(input,response,timer,expired,maximum));
        } catch(IOException | RuntimeException ex) {
            timer.cancel(false);if(response!=null) response.close();
            if(expired.get() || ex instanceof java.net.SocketTimeoutException) throw new HttpTimeoutException("qBittorrent request timed out");
            throw ex;
        }
    }
    private static final class LimitedStream extends InputStream {
        private final InputStream input;
        private final ClassicHttpResponse response;
        private final ScheduledFuture<?> timer;
        private final AtomicBoolean expired;
        private final long maximum;
        private long received;
        LimitedStream(InputStream input,ClassicHttpResponse response,ScheduledFuture<?> timer,AtomicBoolean expired,long maximum) {
            this.input=input;this.response=response;this.timer=timer;this.expired=expired;this.maximum=maximum;
        }
        private void received(int count) throws IOException {
            if(expired.get()) throw new IOException(new QBittorrentConnectionException(TIMEOUT));
            if(count<0) timer.cancel(false);
            else if((received+=count)>maximum) {close();throw new IOException(new QBittorrentConnectionException(RESPONSE_TOO_LARGE));}
        }
        private IOException failure(IOException ex) {return Thread.currentThread().isInterrupted()?new IOException(new QBittorrentConnectionException(INTERRUPTED)):expired.get()?new IOException(new QBittorrentConnectionException(TIMEOUT)):ex;}
        private void check() throws IOException {
            if(Thread.currentThread().isInterrupted()) throw new IOException(new QBittorrentConnectionException(INTERRUPTED));
            if(expired.get()) throw new IOException(new QBittorrentConnectionException(TIMEOUT));
        }
        @Override public int read() throws IOException {
            try {check();int value=input.read();received(value<0?-1:1);return value;}catch(IOException ex){throw failure(ex);}
        }
        @Override public int read(byte[] data,int offset,int length) throws IOException {
            try {check();int count=input.read(data,offset,length);received(count);return count;}catch(IOException ex){throw failure(ex);}
        }
        @Override public void close() throws IOException {timer.cancel(false);try {input.close();}finally {response.close();}}
    }
    @Override public void close() {api.close(CloseMode.IMMEDIATE);session.close(CloseMode.IMMEDIATE);cookies.clear();}
}
