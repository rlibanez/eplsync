package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalibreEpubDownloadsTests {
    HttpServer server;
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    CalibreSettings settings;
    CalibreEpubDownloads downloads;
    byte[] epub;
    int status=200;
    boolean redirect=false, authenticate=false, digest=false, chunked=false;
    List<String> calls=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        epub=epub();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e -> {
            calls.add(e.getRequestURI().toString());
            if(authenticate && e.getRequestHeaders().getFirst("Authorization")==null) {
                e.getResponseHeaders().add("WWW-Authenticate",digest ? "Digest realm=\"calibre\", nonce=\"nonce\", algorithm=MD5, qop=\"auth\"" : "Basic realm=\"calibre\"");
                e.sendResponseHeaders(401,-1);e.close();return;
            }
            if(authenticate) {
                var auth=e.getRequestHeaders().getFirst("Authorization");
                if(digest) assertThat(auth).startsWith("Digest ").contains("uri=\"/get/epub/3/main\"");
                else assertThat(auth).isEqualTo("Basic "+Base64.getEncoder().encodeToString("reader:password".getBytes(StandardCharsets.UTF_8)));
            }
            if(redirect) e.getResponseHeaders().add("Location","/other");
            e.getResponseHeaders().add("Content-Type","application/epub+zip");
            e.sendResponseHeaders(redirect?302:status,chunked?0:epub.length);
            try(var out=e.getResponseBody()) { out.write(epub); }
        });
        server.start();
        source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY,setting_value TEXT)");
        jdbc.execute("CREATE TABLE update_search_results(search_type TEXT)");
        jdbc.execute("CREATE TABLE calibre_books(calibre_id INTEGER,title TEXT,active BOOLEAN)");
        jdbc.update("INSERT INTO calibre_books VALUES(3,'El libro',true),(8,'El libro',true)");
        settings=new CalibreSettings(jdbc,"","http://127.0.0.1:"+server.getAddress().getPort(),"","reader","password","main");
        downloads=new CalibreEpubDownloads(settings,new CalibreClient(),jdbc);
    }
    @AfterEach void cleanup() { downloads.close();server.stop(0);source.destroy(); }
    static byte[] epub() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("mimetype"));zip.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));zip.write("<container/>".getBytes());zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    String token(CalibreEpubDownloads.Ready ready) { return ready.url().substring(ready.url().lastIndexOf('/')+1); }
    @Test void singleDownloadUsesBasicAuthenticationAndIsSessionBoundAndSingleUse() throws Exception {
        authenticate=true;
        var ready=downloads.prepare("owner",List.of(3L));
        assertThat(ready.archive()).isFalse();
        assertThatThrownBy(() -> downloads.download("other",token(ready),new MockHttpServletResponse())).hasMessageContaining("sesión");
        var response=new MockHttpServletResponse();downloads.download("owner",token(ready),response);
        assertThat(response.getContentAsByteArray()).isEqualTo(epub);
        assertThat(response.getContentType()).isEqualTo("application/epub+zip");
        assertThat(response.getHeader("Content-Disposition")).contains("3");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThatThrownBy(() -> downloads.download("owner",token(ready),response)).hasMessageContaining("caducado");
        assertThat(calls).containsOnly("/get/epub/3/main");
    }
    @Test void digestAuthenticationAndZipContainEachRevisionWithDistinctNames() throws Exception {
        authenticate=true;digest=true;
        // Digest path assertion above is for book 3; test the challenge once, then stream both anonymously.
        var first=downloads.prepare("owner",List.of(3L));downloads.download("owner",token(first),new MockHttpServletResponse());
        authenticate=false;
        var ready=downloads.prepare("owner",List.of(3L,8L,3L));assertThat(ready.fileCount()).isEqualTo(2);
        var response=new MockHttpServletResponse();downloads.download("owner",token(ready),response);
        assertThat(response.getContentType()).isEqualTo("application/zip");
        try(var zip=new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("El libro [3].epub");assertThat(zip.readAllBytes()).isEqualTo(epub);
            assertThat(zip.getNextEntry().getName()).isEqualTo("El libro [8].epub");assertThat(zip.readAllBytes()).isEqualTo(epub);
            assertThat(zip.getNextEntry()).isNull();
        }
    }
    @Test void rejectsMissingFormatsAuthenticationRedirectsAndNonEpubResponses() {
        status=404;assertThatThrownBy(() -> downloads.prepare("owner",List.of(3L))).hasMessageContaining("Calibre ID 3").hasMessageContaining("EPUB");
        status=403;assertThatThrownBy(() -> downloads.prepare("owner",List.of(3L))).hasMessageContaining("rechazado");
        status=200;redirect=true;assertThatThrownBy(() -> downloads.prepare("owner",List.of(3L))).hasMessageContaining("redirección");assertThat(calls).doesNotContain("/other");
        redirect=false;epub="<html>login</html>".getBytes();assertThatThrownBy(() -> downloads.prepare("owner",List.of(3L))).hasMessageContaining("Calibre ID 3");
    }
    @Test void boundsBodiesEvenWithoutContentLength() throws Exception {
        var client=new CalibreClient();var target=Files.createTempFile("calibre-test-",".epub");
        try {
            assertThatThrownBy(() -> client.downloadEpub(settings.connection(),3,target,10)).hasMessageContaining("límite");
            chunked=true;assertThatThrownBy(() -> client.downloadEpub(settings.connection(),3,target,10)).hasMessageContaining("límite");
        } finally { Files.delete(target); }
    }
    @Test void rejectsInvalidOrStaleSelectionsBeforeNetworkAndBoundsPendingDownloads() throws Exception {
        assertThatThrownBy(() -> downloads.prepare("owner",List.of())).hasMessageContaining("1000");
        assertThatThrownBy(() -> downloads.prepare("owner",Collections.nCopies(1001,3L))).hasMessageContaining("1000");
        assertThatThrownBy(() -> downloads.prepare("owner",List.of(99L))).hasMessageContaining("sincronizada");assertThat(calls).isEmpty();
        var one=downloads.prepare("owner",List.of(3L));downloads.prepare("owner",List.of(8L));
        assertThatThrownBy(() -> downloads.prepare("owner",List.of(3L))).hasMessageContaining("dos descargas");
        downloads.download("owner",token(one),new MockHttpServletResponse());
        downloads.prepare("owner",List.of(3L));
    }
    @Test void abandonedDownloadsExpireAndReleaseStorageAndCapacity() throws Exception {
        var clock=mock(java.time.Clock.class);var now=java.time.Instant.parse("2026-10-13T00:00:00Z");when(clock.instant()).thenReturn(now);
        var client=mock(CalibreClient.class);var paths=new ArrayList<Path>();
        doAnswer(call -> {Path path=call.getArgument(2);paths.add(path);Files.write(path,epub);return null;})
            .when(client).downloadEpub(any(),anyLong(),any(),anyLong());
        var expiring=new CalibreEpubDownloads(settings,client,jdbc,clock);
        try {
            var ready=expiring.prepare("owner",List.of(3L));assertThat(Files.exists(paths.getFirst())).isTrue();
            when(clock.instant()).thenReturn(now.plusSeconds(121));expiring.expire();
            assertThat(Files.exists(paths.getFirst())).isFalse();
            assertThatThrownBy(() -> expiring.download("owner",token(ready),new MockHttpServletResponse())).hasMessageContaining("caducado");
            expiring.prepare("owner",List.of(3L));expiring.prepare("owner",List.of(8L));
        } finally { expiring.close(); }
        assertThat(paths).allMatch(path -> !Files.exists(path));
    }
    @Test void rejectsDestinationChangesAndDeletesFailedPreparations() throws Exception {
        var ready=downloads.prepare("owner",List.of(3L));
        settings.save(new CalibreSettings.Input("http://other:8080","","",false,"other"));
        assertThatThrownBy(() -> downloads.download("owner",token(ready),new MockHttpServletResponse())).hasMessageContaining("ha cambiado");
        var client=mock(CalibreClient.class);var paths=new ArrayList<Path>();
        doAnswer(call -> { Path path=call.getArgument(2);paths.add(path);Files.writeString(path,"partial");throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"EPUB no disponible"); })
            .when(client).downloadEpub(any(),anyLong(),any(),anyLong());
        var failed=new CalibreEpubDownloads(settings,client,jdbc);
        jdbc.update("INSERT INTO calibre_books VALUES(3,'Book',true)");
        try { assertThatThrownBy(() -> failed.prepare("owner",List.of(3L))).hasMessageContaining("EPUB no disponible");assertThat(paths).hasSize(1);assertThat(Files.exists(paths.getFirst())).isFalse(); }
        finally { failed.close(); }
    }
}
