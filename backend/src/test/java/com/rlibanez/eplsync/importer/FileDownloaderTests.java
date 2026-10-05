package com.rlibanez.eplsync.importer;

import com.sun.net.httpserver.HttpServer;
import com.rlibanez.eplsync.exception.CatalogDownloadException;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class FileDownloaderTests {
    private static final byte[] ZIP = new byte[]{'P','K',3,4};
    private static String url(HttpServer server,String path) { return "http://127.0.0.1:"+server.getAddress().getPort()+path; }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,String type,byte[] content) throws java.io.IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(status,content.length);
            exchange.getResponseBody().write(content);
        }
    }
    @Test void follows302RedirectWithEmptyFragmentAndDownloadsLocalContent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/redirect",exchange -> {
            exchange.getResponseHeaders().add("Location","/catalog.zip#");
            respond(exchange,302,"text/html",new byte[16_384]);
        });
        server.createContext("/catalog.zip",exchange -> respond(exchange,200,"application/zip",ZIP));
        server.start();
        try {
            var file = new FileDownloader().download(url(server,"/redirect"),"test-catalog-",".zip");
            try { assertThat(Files.readAllBytes(file)).isEqualTo(ZIP); }
            finally { Files.deleteIfExists(file); }
        } finally { server.stop(0); }
    }
    @Test void reportsTextResponsesWithoutReturningTheirContentOrUrlTokens() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/catalog.zip",exchange -> respond(exchange,200,"text/html","private upstream response".getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            assertThatThrownBy(() -> new FileDownloader().download(url(server,"/catalog.zip?token=private-token"),"test-catalog-",".zip"))
                .isInstanceOf(CatalogDownloadException.class).hasMessageContaining("texto en lugar del ZIP")
                .hasMessageNotContaining("private upstream").hasMessageNotContaining("private-token").hasNoCause();
        } finally { server.stop(0); }
    }
    @Test void transportUsesTheValidatedIpWithoutResolvingAgainAndKeepsTheOriginalHostHeader() throws Exception {
        var dnsCalls = new AtomicInteger();
        var hostHeader = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/catalog.zip",exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange,200,"application/zip",ZIP);
        });
        server.start();
        try {
            var downloader = new FileDownloader(host -> {
                int call = dnsCalls.incrementAndGet();
                return new InetAddress[]{InetAddress.getByName(call == 1 ? "127.0.0.1" : "127.0.0.2")};
            },new PinnedCatalogTransport());
            var file = downloader.download("http://catalog.invalid:"+server.getAddress().getPort()+"/catalog.zip?token=a%2Fb%2Bc","test-pinned-",".zip");
            try { assertThat(Files.readAllBytes(file)).isEqualTo(ZIP); }
            finally { Files.deleteIfExists(file); }
            assertThat(dnsCalls.get()).isEqualTo(1);
            assertThat(query.get()).isEqualTo("token=a%2Fb%2Bc");
            assertThat(hostHeader.get()).isEqualTo("catalog.invalid:"+server.getAddress().getPort());
        } finally { server.stop(0); }
    }
    @Test void blocksPublicToInternalRedirectsBeforeSendingTheSecondRequest() throws Exception {
        var sends = new AtomicInteger();
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName(host.equals("public.example") ? "93.184.216.34" : "127.0.0.1")},
            (target,writer) -> {
                sends.incrementAndGet();
                return new CatalogDownloadTransport.Result(302,"http://internal.example:6065/data/catalog.zip","",null);
            });
        assertThatThrownBy(() -> downloader.download("http://public.example/catalog.zip","test-",".zip"))
            .hasMessageContaining("destino público");
        assertThat(sends.get()).isEqualTo(1);
    }
    @Test void blocksDnsRebindingOnASameHostRedirect() throws Exception {
        var resolutions = new AtomicInteger();
        var sends = new AtomicInteger();
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName(resolutions.incrementAndGet() == 1 ? "93.184.216.34" : "169.254.169.254")},
            (target,writer) -> { sends.incrementAndGet(); return new CatalogDownloadTransport.Result(302,"/next.zip","",null); });
        assertThatThrownBy(() -> downloader.download("https://public.example/catalog.zip","test-",".zip"))
            .hasMessageContaining("destino público");
        assertThat(resolutions.get()).isEqualTo(2);
        assertThat(sends.get()).isEqualTo(1);
    }
    @Test void allowsRedirectsAcrossUnrelatedPublicDomainsAndPreservesQueryTokens() throws Exception {
        var sends = new AtomicInteger();
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},(target,writer) -> {
            if (sends.incrementAndGet() == 1)
                return new CatalogDownloadTransport.Result(307,"http://another.example:6065/a%2Fb.zip?token=a%2Fb","",null);
            assertThat(target.uri().toString()).isEqualTo("http://another.example:6065/a%2Fb.zip?token=a%2Fb");
            var file = writer.write(200,"application/zip",new ByteArrayInputStream(ZIP));
            return new CatalogDownloadTransport.Result(200,null,"application/zip",file);
        });
        var file = downloader.download("https://source.example/catalog.zip","test-public-",".zip");
        try { assertThat(Files.readAllBytes(file)).isEqualTo(ZIP); }
        finally { Files.deleteIfExists(file); }
        assertThat(sends.get()).isEqualTo(2);
    }
    @Test void stripsOnlyLiteralRedirectFragmentsAndPreservesSignedQueriesAndEncodedHashes() throws Exception {
        var cases=java.util.Map.of(
            "https://downloads.example/a%2Fb.zip?token=a%2Fb%2Bc&other=%252F#", "https://downloads.example/a%2Fb.zip?token=a%2Fb%2Bc&other=%252F",
            "https://downloads.example/archive%23name.zip?token=a%23b#section", "https://downloads.example/archive%23name.zip?token=a%23b",
            "../archive%23name.zip?token=a%23b#section", "https://source.example/archive%23name.zip?token=a%23b",
            "/archive.zip?token=a%2Bb#section", "https://source.example/archive.zip?token=a%2Bb",
            "//downloads.example/archive.zip?token=%23signed#", "https://downloads.example/archive.zip?token=%23signed"
        );
        for(var entry:cases.entrySet()) {
            var sends=new AtomicInteger();
            var downloader=new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},(target,writer) -> {
                if(sends.incrementAndGet()==1) return new CatalogDownloadTransport.Result(302,entry.getKey(),"",null);
                assertThat(target.uri().toString()).isEqualTo(entry.getValue());
                assertThat(target.uri().getRawFragment()).isNull();
                return new CatalogDownloadTransport.Result(200,null,"application/zip",writer.write(200,"application/zip",new ByteArrayInputStream(ZIP)));
            });
            var file=downloader.download("https://source.example/folder/catalog.zip?original=a%23b","test-fragment-",".zip");
            try { assertThat(Files.readAllBytes(file)).isEqualTo(ZIP); }
            finally { Files.deleteIfExists(file); }
            assertThat(sends.get()).isEqualTo(2);
        }
    }
    @Test void fragmentOnlyRedirectsAreLoopsWithoutChangingTheResourceOrQuery() {
        for(String location:java.util.List.of("#","#section")) {
            var sends=new AtomicInteger();
            var downloader=new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},(target,writer) -> {
                sends.incrementAndGet();return new CatalogDownloadTransport.Result(302,location,"",null);
            });
            assertThatThrownBy(() -> downloader.download("https://source.example/folder/catalog.zip?token=a%23b","test-loop-",".zip"))
                .hasMessageContaining("bucle de redirecciones");
            assertThat(sends.get()).isEqualTo(1);
        }
    }
    @Test void removingFragmentsDoesNotPermitRedirectsToInternalAddressesOrUnsafeProtocols() {
        var sends=new AtomicInteger();
        var downloader=new FileDownloader(host -> new InetAddress[]{InetAddress.getByName(host.equals("source.example") ? "93.184.216.34" : "127.0.0.1")},(target,writer) -> {
            sends.incrementAndGet();return new CatalogDownloadTransport.Result(302,"http://internal.example/archive.zip#","",null);
        });
        assertThatThrownBy(() -> downloader.download("https://source.example/catalog.zip","test-private-",".zip"))
            .hasMessageContaining("destino público");
        assertThat(sends.get()).isEqualTo(1);
        var invalid=new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},
            (target,writer) -> new CatalogDownloadTransport.Result(302,"file:///tmp/private#","",null));
        assertThatThrownBy(() -> invalid.download("https://source.example/catalog.zip","test-protocol-",".zip"))
            .hasMessageContaining("URL no permitida");
    }

    @Test void rejectsLoopsTooManyRedirectsMissingLocationsAndInvalidRedirectProtocols() {
        for (String location : new String[]{"http://public.example/catalog.zip", "file:///tmp/private", "http://user:private-token@public.example/zip", "", "http://public.example:0/zip"}) {
            var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},
                (target,writer) -> new CatalogDownloadTransport.Result(302,location,"",null));
            assertThatThrownBy(() -> downloader.download("http://public.example/catalog.zip","test-",".zip"))
                .isInstanceOf(CatalogDownloadException.class).hasMessageNotContaining("private-token");
        }
        var sends = new AtomicInteger();
        var endless = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},
            (target,writer) -> new CatalogDownloadTransport.Result(302,"/hop-"+sends.incrementAndGet(),"",null));
        assertThatThrownBy(() -> endless.download("http://public.example/catalog.zip","test-",".zip"))
            .hasMessageContaining("límite de redirecciones");
        assertThat(sends.get()).isEqualTo(6);
    }
    @Test void networkExceptionsAreSanitizedAndInterruptionIsPreserved() {
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},
            (target,writer) -> { throw new java.io.IOException("https://server/path?private-token"); });
        assertThatThrownBy(() -> downloader.download("http://public.example/catalog.zip","test-",".zip"))
            .hasMessageNotContaining("private-token").hasNoCause();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> downloader.download("http://public.example/catalog.zip","test-",".zip"))
                .isInstanceOf(InterruptedException.class);
        } finally { Thread.interrupted(); }
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void httpsStillRejectsAnUntrustedServerCertificate() throws Exception {
        var store = directory.resolve("server.p12");
        var generator = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
            "-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
            "-keystore",store.toString(),"-storepass","test-password","-dname","CN=localhost","-validity","1",
            "-ext","SAN=dns:localhost,ip:127.0.0.1","-noprompt").redirectErrorStream(true).start();
        assertThat(generator.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(generator.exitValue()).isZero();
        var keys = java.security.KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(store)) { keys.load(stream,"test-password".toCharArray()); }
        var managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys,"test-password".toCharArray());
        var context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(),null,null);
        var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(context));
        var requests = new AtomicInteger();
        server.createContext("/catalog.zip",exchange -> { requests.incrementAndGet(); respond(exchange,200,"application/zip",ZIP); });
        server.start();
        try {
            assertThatThrownBy(() -> new FileDownloader().download("https://127.0.0.1:"+server.getAddress().getPort()+"/catalog.zip?token=private-token","test-tls-",".zip"))
                .isInstanceOf(CatalogDownloadException.class).hasMessageContaining("certificado")
                .hasMessageNotContaining("private-token").hasNoCause();
            assertThat(requests.get()).isZero();
        } finally { server.stop(0); }
    }
    @Test void httpFailuresDoNotLogSignedUrlsLocationHeadersOrResponseBodies() throws Exception {
        var logger = (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(FileDownloader.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/catalog.zip", exchange -> {
            exchange.getResponseHeaders().set("Location","http://other.example/?token=private-location");
            respond(exchange,500,"text/plain","private-response-body".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        try {
            assertThatThrownBy(() -> new FileDownloader().download(url(server,"/catalog.zip?token=private-token"),"test-log-",".zip"))
                .hasMessage("Error al descargar el catálogo. HTTP 500");
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("private-token","private-location","private-response-body"));
        } finally { server.stop(0); logger.detachAppender(appender); appender.stop(); }
    }

    @Test void deletesDownloadedFilesIfTheTransportFailsWhileClosingTheResponse() {
        var received = new AtomicReference<java.nio.file.Path>();
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")},
            (target,writer) -> {
                received.set(writer.write(200,"application/zip",new ByteArrayInputStream(ZIP)));
                throw new java.io.IOException("close failed for URL with private-token");
            });
        assertThatThrownBy(() -> downloader.download("http://public.example/catalog.zip","test-cleanup-",".zip"))
            .hasMessageNotContaining("private-token");
        assertThat(received.get()).doesNotExist();
    }


    @Test void boundsActualBytesWithoutContentLengthAndRemovesPartialDownload() throws Exception {
        String prefix = "oversize-" + java.util.UUID.randomUUID();
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getLoopbackAddress()},
            (target, writer) -> new CatalogDownloadTransport.Result(200,null,"application/zip",
                writer.write(200,"application/zip",new ByteArrayInputStream(new byte[65]))),64,java.time.Duration.ofSeconds(5));
        assertThatThrownBy(() -> downloader.download("http://local.test/catalog.zip",prefix,".zip"))
            .isInstanceOf(CatalogDownloadException.class).hasMessageContaining("tamaño máximo");
        try (var paths = Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(paths.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()).isEmpty();
        }
    }
    @Test void acceptsExactlyTheByteLimit() throws Exception {
        var downloader = new FileDownloader(host -> new InetAddress[]{InetAddress.getLoopbackAddress()},
            (target, writer) -> new CatalogDownloadTransport.Result(200,null,"application/zip",
                writer.write(200,"application/zip",new ByteArrayInputStream(new byte[64]))),64,java.time.Duration.ofSeconds(5));
        var path = downloader.download("http://local.test/catalog.zip","exact-limit-",".zip");
        try { assertThat(Files.size(path)).isEqualTo(64); } finally { Files.delete(path); }
    }
    @Test void totalBudgetBoundsDnsResolution() {
        var downloader = new FileDownloader(host -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.net.UnknownHostException("interrupted"); }
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        }, (target, writer) -> { throw new AssertionError("No request after DNS deadline"); },64,java.time.Duration.ofMillis(100));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
            assertThatThrownBy(() -> downloader.download("http://local.test/catalog.zip","dns-budget-",".zip"))
                .isInstanceOf(CatalogDownloadException.class).hasMessageContaining("tiempo máximo"));
    }
    @Test void totalBudgetCancelsAStalledHttpBodyAndRemovesPartialFile() throws Exception {
        String prefix = "stalled-" + java.util.UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/catalog.zip",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(ZIP); exchange.getResponseBody().flush();
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            var downloader = new FileDownloader(InetAddress::getAllByName,new PinnedCatalogTransport(),64,java.time.Duration.ofMillis(300));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> downloader.download(url(server,"/catalog.zip"),prefix,".zip"))
                    .isInstanceOf(CatalogDownloadException.class).hasMessageContaining("tiempo máximo"));
            try (var paths = Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
                assertThat(paths.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()).isEmpty();
            }
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void redirectsShareOneTransferDeadline() throws Exception {
        var calls = new AtomicInteger();
        var downloader = new FileDownloader(InetAddress::getAllByName, (target, writer) -> {
            Thread.sleep(120);
            int call = calls.incrementAndGet();
            return new CatalogDownloadTransport.Result(302,"/redirect"+call,"",null);
        },64,java.time.Duration.ofMillis(200));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
            assertThatThrownBy(() -> downloader.download("http://127.0.0.1/catalog.zip","redirect-budget-",".zip"))
                .hasMessageContaining("tiempo máximo"));
        assertThat(calls.get()).isLessThanOrEqualTo(2);
    }
    @Test void rejectsOversizedDeclaredHttpBodyBeforeCreatingTemporaryFile() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/catalog.zip",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200,CatalogImportLimits.ZIP_BYTES + 1);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
            }
        });
        server.start();
        String prefix = "declared-" + java.util.UUID.randomUUID();
        try {
            assertThatThrownBy(() -> new FileDownloader().download(url(server,"/catalog.zip"),prefix,".zip"))
                .hasMessageContaining("tamaño máximo");
            try (var paths = Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
                assertThat(paths.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()).isEmpty();
            }
        } finally { server.stop(0); }
    }
}
