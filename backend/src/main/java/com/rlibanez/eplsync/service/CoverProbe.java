package com.rlibanez.eplsync.service;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CoverProbe {
    public record Result(Boolean available, Integer httpStatus, String reason) {}
    private final HttpClient client;
    private final Duration requestTimeout;

    public CoverProbe(com.rlibanez.eplsync.config.CoverCheckProperties properties) {
        requestTimeout = properties.getRequestTimeout();
        client = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @jakarta.annotation.PreDestroy
    void shutdown() { client.shutdownNow(); }

    public Result check(String url) {
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        try {
            URI uri = URI.create(url);
            for (int redirects = 0; redirects <= 3; redirects++) {
                validate(uri);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return new Result(null, null, "TIMEOUT");
                var response = request(uri, Duration.ofNanos(remaining));
                var body = response.body();
                try (body) {
                    int status = response.statusCode();
                    if (status == 404 || status == 410) return new Result(false, status, "NOT_FOUND");
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        var location = response.headers().firstValue("Location");
                        if (location.isEmpty()) return new Result(null, status, "INVALID_REDIRECT");
                        uri = uri.resolve(location.get());
                        continue;
                    }
                    String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    if (status == 200 && type.startsWith("image/")) return new Result(true, status, "AVAILABLE");
                    return new Result(null, status, status == 200 ? "NOT_AN_IMAGE" : "HTTP_ERROR");
                }
            }
            return new Result(null, null, "TOO_MANY_REDIRECTS");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(null, null, "INTERRUPTED");
        } catch (java.net.http.HttpTimeoutException ex) {
            return new Result(null, null, "TIMEOUT");
        } catch (IllegalArgumentException ex) {
            return new Result(null, null, "INVALID_URL");
        } catch (java.io.IOException ex) {
            return new Result(null, null, "NETWORK_ERROR");
        }
    }

    protected HttpResponse<InputStream> request(URI uri, Duration timeout) throws java.io.IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "EplSync/1.0").header("Accept", "image/*").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    protected void validate(URI uri) throws java.io.IOException {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid cover URL");
        }
        for (var address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                throw new IllegalArgumentException("Cover URLs must use public addresses");
            }
        }
    }
}
