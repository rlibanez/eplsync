package com.rlibanez.eplsync.importer;

import com.sun.net.httpserver.HttpServer;
import com.rlibanez.eplsync.exception.CatalogDownloadException;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.*;

class FileDownloaderTests {
    @Test
    void follows302RedirectAndDownloadsContent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] content = new byte[] {'P', 'K', 3, 4};
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/catalog.zip");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/catalog.zip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, content.length);
            try (var body = exchange.getResponseBody()) { body.write(content); }
        });
        server.start();
        try {
            var file = new FileDownloader().download("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/redirect", "test-catalog-", ".zip");
            try { assertThat(Files.readAllBytes(file)).isEqualTo(content); }
            finally { Files.deleteIfExists(file); }
        } finally { server.stop(0); }
    }

    @Test
    void reportsQuotaResponseInsteadOfTreatingHtmlAsZip() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] content = "Se ha superado el numero maximo de descargas diarias del fichero (3)"
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/catalog.zip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, content.length);
            try (var body = exchange.getResponseBody()) { body.write(content); }
        });
        server.start();
        try {
            assertThatThrownBy(() -> new FileDownloader().download("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/catalog.zip", "test-catalog-", ".zip"))
                    .isInstanceOf(CatalogDownloadException.class)
                    .hasMessageContaining("texto en lugar del ZIP")
                    .hasMessageContaining("descargas diarias");
        } finally { server.stop(0); }
    }
}
