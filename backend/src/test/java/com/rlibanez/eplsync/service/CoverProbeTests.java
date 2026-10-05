package com.rlibanez.eplsync.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverProbeTests {
    @Test void usesConfiguredRequestBudget() {
        var properties = new com.rlibanez.eplsync.config.CoverCheckProperties();
        properties.setRequestTimeout(Duration.ofSeconds(9));
        properties.setBatchTimeout(Duration.ofSeconds(10));
        var response = response(200, "image/jpeg", null);
        var probe = new CoverProbe(properties) {
            @Override protected Target validate(URI uri, Duration timeout) { return new Target(uri,new java.net.InetAddress[0]); }
            @Override protected Response request(Target target, Duration timeout) {
                assertThat(timeout).isGreaterThan(Duration.ofSeconds(8)).isLessThanOrEqualTo(Duration.ofSeconds(9));
                return response;
            }
        };
        try {
            assertThat(probe.check("https://example.org/cover.jpg").available()).isTrue();
        } finally {
            probe.shutdown();
        }
    }
    CoverProbe.Response response(int status,String type,String location) {
        return new CoverProbe.Response(status,type,location,spy(new ByteArrayInputStream(new byte[0])),() -> {});
    }

    CoverProbe returning(CoverProbe.Response response) {
        return new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            @Override protected Target validate(URI uri, Duration timeout) { return new Target(uri,new java.net.InetAddress[0]); }
            @Override protected Response request(Target target, Duration timeout) { return response; }
        };
    }

    @Test void distinguishesErrorImagesAndPreservesUnknownStatesWithoutDownloadingBodies() throws Exception {
        for (int status : List.of(200, 404, 410, 403, 429, 500)) {
            var response = response(status, "image/png", null);
            var result = returning(response).check("https://example.org/cover.jpg");
            assertThat(result.available()).isEqualTo(status == 200 ? Boolean.TRUE
                    : status == 404 || status == 410 ? Boolean.FALSE : null);
            assertThat(result.httpStatus()).isEqualTo(status);
            verify(response.body()).close();
            verify(response.body(), never()).read(any(byte[].class));
        }
        assertThat(returning(response(200, "text/html", null)).check("https://example.org/x").available()).isNull();
    }

    @Test void followsRelativeRedirectsAndRevalidatesEveryDestination() {
        var first = response(302, "text/plain", "/missing.jpg");
        var last = response(404, "image/png", null);
        var visited = new java.util.ArrayList<URI>();
        var probe = new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            @Override protected Target validate(URI uri, Duration timeout) { visited.add(uri); return new Target(uri,new java.net.InetAddress[0]); }
            @Override protected Response request(Target target, Duration timeout) {
                return target.uri().getPath().equals("/old.jpg") ? first : last;
            }
        };
        assertThat(probe.check("https://example.org/old.jpg").available()).isFalse();
        assertThat(visited).containsExactly(URI.create("https://example.org/old.jpg"), URI.create("https://example.org/missing.jpg"));
    }

    @Test void loopsAndTimeoutsAreInconclusive() {
        assertThat(returning(response(302, "text/plain", "/loop")).check("https://example.org/loop").reason())
                .isEqualTo("TOO_MANY_REDIRECTS");
        var probe = new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            @Override protected Target validate(URI uri, Duration timeout) { return new Target(uri,new java.net.InetAddress[0]); }
            @Override protected Response request(Target target, Duration timeout) throws java.io.IOException {
                throw new java.net.http.HttpTimeoutException("timeout");
            }
        };
        assertThat(probe.check("https://example.org/x").available()).isNull();
        assertThat(probe.check("https://example.org/x").reason()).isEqualTo("TIMEOUT");
    }

    @Test void rejectsNonHttpCredentialsAndLocalDestinationsBeforeRequesting() {
        var probe = new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties());
        for (String url : List.of("file:///etc/passwd", "http://127.0.0.1/x", "http://[::1]/x",
                "http://169.254.169.254/x", "http://10.0.0.1/x", "http://100.64.0.1/x", "http://198.18.0.1/x", "http://[fc00::1]/x", "https://user:pass@example.org/x")) {
            assertThat(probe.check(url).reason()).isEqualTo("INVALID_URL");
        }
    }
    @Test void rejectsMixedDnsAnswersAndInternalRedirectsBeforeConnecting() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var probe=new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            protected java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return host.equals("mixed.test") ? new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8"),java.net.InetAddress.getByName("100.64.0.1")}
                    : new java.net.InetAddress[]{java.net.InetAddress.getByName(host.equals("internal.test") ? "127.0.0.1" : "8.8.8.8")};
            }
            protected Response request(Target target,Duration timeout) {
                calls.incrementAndGet(); return response(302,"text/plain","http://internal.test/cover");
            }
        };
        assertThat(probe.check("https://mixed.test/cover").reason()).isEqualTo("INVALID_URL");
        assertThat(calls.get()).isZero();
        assertThat(probe.check("https://public.test/cover").reason()).isEqualTo("INVALID_URL");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void pinsValidatedDnsAnswersWithoutResolvingAgainAndPreservesHostname() throws Exception {
        var lookups=new java.util.concurrent.atomic.AtomicInteger();
        var probe=new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties()) {
            protected java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName(lookups.incrementAndGet()==1 ? "8.8.8.8" : "127.0.0.1")};
            }
            protected Response request(Target target,Duration timeout) throws java.io.IOException {
                var resolver=pinnedResolver(target);
                assertThat(resolver.resolve("public.test")[0].getHostAddress()).isEqualTo("8.8.8.8");
                assertThat(resolver.resolveCanonicalHostname("public.test")).isEqualTo("public.test");
                assertThatThrownBy(() -> resolver.resolve("other.test")).isInstanceOf(java.net.UnknownHostException.class);
                var copy=target.addresses(); copy[0]=java.net.InetAddress.getByName("127.0.0.1");
                assertThat(resolver.resolve("public.test")[0].getHostAddress()).isEqualTo("8.8.8.8");
                assertThat(target.uri().getHost()).isEqualTo("public.test");
                return response(200,"image/png",null);
            }
        };
        assertThat(probe.check("https://public.test/cover").available()).isTrue();
        assertThat(lookups.get()).isEqualTo(1);
    }
    @Test void dnsLookupSharesTheRequestDeadline() {
        var properties=new com.rlibanez.eplsync.config.CoverCheckProperties();
        properties.setRequestTimeout(Duration.ofMillis(30));
        var probe=new CoverProbe(properties) {
            protected java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                try { Thread.sleep(5000); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
                throw new java.net.UnknownHostException();
            }
        };
        assertThat(probe.check("http://slow.test/cover").reason()).isEqualTo("TIMEOUT");
    }

    @Test void realTransportConnectsToPinnedAddressAndSendsOriginalHostWithoutReadingBody() throws Exception {
        var host=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/cover",exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.getResponseHeaders().set("Content-Type","image/png");
            exchange.sendResponseHeaders(200,0); exchange.close();
        });
        server.start();
        try {
            int port=server.getAddress().getPort();
            var probe=new CoverProbe(new com.rlibanez.eplsync.config.CoverCheckProperties());
            var target=new CoverProbe.Target(URI.create("http://unresolvable.invalid:"+port+"/cover"),
                new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")});
            try(var response=probe.request(target,Duration.ofSeconds(2))) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.contentType()).isEqualTo("image/png");
            }
            assertThat(host.get()).isEqualTo("unresolvable.invalid:"+port);
        } finally { server.stop(0); }
    }

}
