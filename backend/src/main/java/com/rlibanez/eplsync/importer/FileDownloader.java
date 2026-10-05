package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.exception.CatalogDownloadException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Catalog downloads use validated DNS addresses and explicitly checked redirect hops. */
@Component
public class FileDownloader {
    private static final Logger log = LoggerFactory.getLogger(FileDownloader.class);
    private static final int MAX_REDIRECTS = 5;
    private final CatalogDownloadPolicy policy;
    private final CatalogDownloadTransport transport;
    private final long maxBytes;
    private final java.time.Duration timeout;

    public FileDownloader() { this(InetAddress::getAllByName,new PinnedCatalogTransport()); }
    FileDownloader(CatalogDownloadPolicy.Resolver resolver,CatalogDownloadTransport transport) {
        this(resolver, transport, CatalogImportLimits.ZIP_BYTES, CatalogImportLimits.DOWNLOAD_TIME);
    }
    FileDownloader(CatalogDownloadPolicy.Resolver resolver, CatalogDownloadTransport transport,
                   long maxBytes, java.time.Duration timeout) {
        this.policy = new CatalogDownloadPolicy(resolver);
        this.transport = transport;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }
    public static URI validateUrl(String value) { return CatalogDownloadPolicy.validateUrl(value); }

    public Path download(String url,String filePrefix,String fileSuffix) throws IOException,InterruptedException {
        URI uri = validateUrl(url);
        var budget = new DownloadBudget(timeout);
        var visited = new HashSet<URI>();
        boolean publicOrigin = false;
        var received = new java.util.concurrent.atomic.AtomicReference<Path>();
        try {
            for (int hop=0; ; hop++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Descarga del catálogo interrumpida");
                if (!visited.add(uri)) throw new CatalogDownloadException("La descarga del catálogo contiene un bucle de redirecciones");
                budget.remaining();
                final URI destination = uri;
                final boolean publicOnly = hop > 0 && publicOrigin;
                var target = budget.resolve(() -> policy.resolve(destination, publicOnly));
                if (hop == 0) publicOrigin = target.publicOnly();
                var result = transport.fetch(target,(status,type,body) -> {
                    if (status < 200 || status >= 300) return null;
                    if (".zip".equalsIgnoreCase(fileSuffix) && (type.startsWith("text/html") || type.startsWith("text/plain")))
                        throw new CatalogDownloadException("El servidor devolvió texto en lugar del ZIP del catálogo");
                    var file = copy(body,filePrefix,fileSuffix,budget);
                    received.set(file);
                    return file;
                },budget);
                budget.remaining();
                int status = result.status();
                if (status >= 200 && status < 300) {
                    received.set(null); // Ownership passes to the importer only after transport cleanup succeeds.
                    return result.file();
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (hop >= MAX_REDIRECTS) throw new CatalogDownloadException("La descarga del catálogo supera el límite de redirecciones");
                    if (result.location() == null || result.location().isBlank())
                        throw new CatalogDownloadException("El servidor devolvió una redirección sin destino");
                    try { uri = validateUrl(uri.resolve(result.location()).toString()); }
                    catch (IllegalArgumentException ex) { throw new CatalogDownloadException("La redirección del catálogo contiene una URL no permitida"); }
                    continue;
                }
                throw new CatalogDownloadException("Error al descargar el catálogo. HTTP " + status);
            }
        } catch (CatalogDownloadException ex) {
            log.warn("Descarga del catálogo rechazada: {}",ex.getMessage());
            throw ex;
        } catch (IOException ex) {
            budget.remaining();
            // HttpClient exceptions can contain the request URI. Expose neither their message nor their cause.
            log.warn("Fallo de comunicación al descargar el catálogo: {}",ex.getClass().getSimpleName());
            throw new CatalogDownloadException("No se pudo descargar el catálogo. Comprueba la conexión y, si usas HTTPS, el certificado del servidor");
        } finally {
            if (received.get() != null) Files.deleteIfExists(received.get());
        }
    }
    private Path copy(InputStream input,String prefix,String suffix,DownloadBudget budget) throws IOException,InterruptedException {
        Path file = Files.createTempFile(prefix,suffix);
        long total = 0;
        try (var output = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count=input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Descarga del catálogo interrumpida");
                budget.remaining();
                if (count > maxBytes - total)
                    throw new CatalogDownloadException("El ZIP descargado supera el tamaño máximo permitido (128 MiB)");
                output.write(buffer,0,count);
                total += count;
            }
        } catch (IOException | InterruptedException | RuntimeException ex) {
            Files.deleteIfExists(file);
            throw ex;
        }
        log.trace("Catálogo descargado: {} bytes",total);
        return file;
    }
}
