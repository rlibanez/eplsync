package com.rlibanez.eplsync.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.rlibanez.eplsync.exception.CatalogDownloadException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Utilidad para descargar archivos desde una URL.
 */
@Component
public class FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(FileDownloader.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient;

    public FileDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Descarga un archivo desde una URL a un archivo temporal.
     * 
     * @param url        La URL del archivo a descargar.
     * @param filePrefix Prefijo para el archivo temporal.
     * @param fileSuffix Sufijo para el archivo temporal.
     * @return Path al archivo temporal descargado.
     * @throws IOException          Si hay error en la descarga.
     * @throws InterruptedException Si la descarga es interrumpida.
     */
    public Path download(String url, String filePrefix, String fileSuffix)
            throws IOException, InterruptedException {
        log.trace("Descargando archivo desde: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "EplSync/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream());

        // Cierra también el cuerpo de las respuestas de error.
        try (InputStream inputStream = response.body()) {
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                String location = response.headers().firstValue("Location").orElse("sin Location");
                log.warn("Descarga fallida: HTTP {}, URL={}, Location={}", statusCode, response.uri(), location);
                throw new CatalogDownloadException("Error al descargar archivo. HTTP " + statusCode
                        + (statusCode >= 300 && statusCode < 400
                        ? ": redirección no completada (ver URL y Location en el log)" : ""));
            }

            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT);
            if (".zip".equalsIgnoreCase(fileSuffix)
                    && (contentType.startsWith("text/html") || contentType.startsWith("text/plain"))) {
                String message = new String(inputStream.readNBytes(2048), StandardCharsets.UTF_8)
                        .replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
                if (message.length() > 300) message = message.substring(0, 300);
                throw new CatalogDownloadException("El servidor devolvió texto en lugar del ZIP del catálogo"
                        + (message.isEmpty() ? "" : ": " + message));
            }

            Path tempFile = Files.createTempFile(filePrefix, fileSuffix);
            try {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                log.trace("Archivo descargado exitosamente: {} ({} bytes)", tempFile, Files.size(tempFile));
                return tempFile;
            } catch (IOException e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }
        }
    }
}
