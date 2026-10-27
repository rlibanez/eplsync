package com.rlibanez.eplsync.qbittorrent;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

/** Request data independent of the JDK's stricter DNS-host syntax. */
record QBittorrentRequest(URI uri,String method,Map<String,String> headers,String body,Duration timeout) {
    @Override public String toString() {return "qBittorrent request [redacted]";}
    record Response<T>(int statusCode,T body) {}
    HttpRequest jdk() {
        var request=HttpRequest.newBuilder(uri).timeout(timeout);headers.forEach(request::header);
        return body==null && method.equals("GET")?request.GET().build():request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    static final class Builder {
        private final URI uri;
        private final Map<String,String> headers=new LinkedHashMap<>();
        private final HttpRequest.Builder validation=HttpRequest.newBuilder(URI.create("http://validation.invalid/"));
        private String method="GET",body;
        private Duration timeout;
        Builder(URI uri) {this.uri=uri;}
        Builder header(String name,String value) {validation.header(name,value);headers.put(name,value);return this;}
        Builder timeout(Duration value) {validation.timeout(value);timeout=value;return this;}
        Builder GET() {method="GET";body=null;return this;}
        Builder POST(String value) {method="POST";body=Objects.requireNonNull(value);return this;}
        QBittorrentRequest build() {return new QBittorrentRequest(uri,method,Map.copyOf(headers),body,timeout);}
    }
}
