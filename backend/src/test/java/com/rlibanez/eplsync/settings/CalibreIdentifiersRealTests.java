package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real manual import and real HTTP export. No Calibre transport or EPUB download is mocked.
 * Run explicitly with EPLSYNC_TEST_REAL_CALIBRE=1; requires calibre-server and calibredb. */
@EnabledIfEnvironmentVariable(named="EPLSYNC_TEST_REAL_CALIBRE",matches="1")
class CalibreIdentifiersRealTests {
    @TempDir Path root;
    void command(String... args) throws Exception {
        var builder=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(root.resolve("command.log").toFile());
        builder.environment().put("CALIBRE_CONFIG_DIRECTORY",root.resolve("config").toString());
        var process=builder.start();
        if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("Calibre command timed out");}
        assertThat(process.exitValue()).as(Files.readString(root.resolve("command.log"))).isZero();
    }
    static void epub(Path target,String text) throws Exception {
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        var entries=new LinkedHashMap<String,byte[]>();
        entries.put("mimetype","application/epub+zip".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/container.xml",("<container version='1.0' xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>").getBytes(StandardCharsets.UTF_8));
        entries.put("content.opf",("<package xmlns='http://www.idpf.org/2007/opf' version='2.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='uid'>urn:uuid:11223344-1122-1122-1122-112233445566</dc:identifier><dc:title>A todo vapor</dc:title><dc:creator>Terry Pratchett</dc:creator><dc:language>es</dc:language><meta name='cover' content='cover'/></metadata><manifest><item id='chapter' href='chapter.xhtml' media-type='application/xhtml+xml'/><item id='cover' href='cover.png' media-type='image/png'/></manifest><spine><itemref idref='chapter'/></spine></package>").getBytes(StandardCharsets.UTF_8));
        entries.put("chapter.xhtml",("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>A todo vapor</title></head><body><p>"+text+"</p></body></html>").getBytes(StandardCharsets.UTF_8));
        entries.put("cover.png",png.toByteArray());
        try(var zip=new ZipOutputStream(Files.newOutputStream(target))) {for(var entry:entries.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
    }
    @Test void identifiesManualImportUsingTheStoredEpubDespiteContentServerRewriting() throws Exception {
        Path source=root.resolve("[27253] A todo vapor (r1.1).epub"),library=root.resolve("library");
        Files.createDirectories(root.resolve("config"));epub(source,"Contenido original para la prueba real");
        command("calibredb","add","--with-library",library.toString(),source.toString());
        int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
        var builder=new ProcessBuilder("calibre-server","--listen-on","127.0.0.1","--port",Integer.toString(port),"--enable-local-write",library.toString()).redirectErrorStream(true).redirectOutput(root.resolve("server.log").toFile());
        builder.environment().put("CALIBRE_CONFIG_DIRECTORY",root.resolve("config").toString());var server=builder.start();
        try {
            var client=new CalibreClient();var connection=new CalibreSettings.Connection("http://127.0.0.1:"+port,"","","library");
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(true) {try {client.check(connection);break;}catch(Exception ex) {if(!server.isAlive() || System.nanoTime()>deadline) throw new AssertionError(Files.readString(root.resolve("server.log")),ex);Thread.sleep(100);}}
            assertThat(client.book(connection,1).eplId()).isNull();
            try(var paths=Files.walk(library)) {
                var stored=paths.filter(path->path.toString().endsWith(".epub")).toList();
                assertThat(stored).hasSize(1);assertThat(stored.getFirst().getFileName()).isNotEqualTo(source.getFileName());
                assertThat(Files.mismatch(source,stored.getFirst())).isEqualTo(-1);
            }
            var request=HttpRequest.newBuilder(URI.create(connection.url()+"/cdb/set-fields/1/library")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"changes\":{\"title\":\"Título editado en Calibre\",\"identifiers\":{\"isbn\":\"9780000000001\"}}}")).build();
            assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            Path served=root.resolve("served.epub"),original=root.resolve("original.epub");
            client.downloadEpub(connection,1,served,268435456);client.downloadOriginalEpub(connection,1,original,268435456);
            assertThat(Files.mismatch(source,original)).isEqualTo(-1);
            assertThat(Files.mismatch(source,served)).isNotEqualTo(-1);
            assertThat(CalibreEpubMetadata.contentFingerprint(source)).isNotEqualTo(CalibreEpubMetadata.contentFingerprint(served));
            var data=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("test.db")));
            org.flywaydb.core.Flyway.configure().dataSource(data).load().migrate();var jdbc=new JdbcTemplate(data);
            jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,created_at,client_instance_id,hash,client,origin,status) VALUES('download',27253,1.1,'now','instance','hash','qbittorrent','EPLSYNC','SUBMITTED')");
            var settings=mock(CalibreSettings.class);when(settings.connection()).thenReturn(connection);
            var transfers=mock(CalibreTransfers.class);when(transfers.exclusive(any())).thenAnswer(call->call.<java.util.function.Supplier<Object>>getArgument(0).get());
            var files=mock(EpubFiles.class);when(files.clientInstanceId()).thenReturn("instance");
            when(files.completed("download")).thenAnswer(call->List.of(new EpubFiles.Item(0,source.getFileName().toString(),Files.size(source))));
            when(files.download("download",0)).thenAnswer(call->new EpubFiles.Opened(source.getFileName().toString(),Files.size(source),Files.newInputStream(source)));
            var sessions=mock(SessionAccess.class);var account=new Account("owner","owner","","ADMIN","ACTIVE",false,null,0,Permission.defaults("ADMIN"),Instant.now());when(sessions.current()).thenReturn(account);
            SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account,null,account.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));
            var catalog=mock(CalibreCatalogService.class);var identifiers=new CalibreIdentifiers(jdbc,client,settings,transfers,catalog,files,sessions);
            var preview=identifiers.preview(1,UUID.randomUUID().toString());assertThat(preview.allowed()).isTrue();assertThat(preview.eplId()).isEqualTo(27253L);assertThat(preview.revision()).isEqualTo("1.1");assertThat(preview.message()).contains("Importación ajena");
            identifiers.apply(preview.token(),UUID.randomUUID().toString());identifiers.apply(preview.token(),UUID.randomUUID().toString());
            var book=client.book(connection,1);assertThat(book.eplId()).isEqualTo(27253L);assertThat(book.revision()).isEqualByComparingTo("1.1");assertThat(book.title()).isEqualTo("Título editado en Calibre");
            assertThat(client.books(connection,0,20).total()).isEqualTo(1);
            client.downloadOriginalEpub(connection,1,original,268435456);assertThat(Files.mismatch(source,original)).isEqualTo(-1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isZero();
            verify(catalog,times(2)).observe(eq(connection),any());
            // Clear only the library identifiers, then prove changed content cannot be linked.
            assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            epub(source,"Contenido distinto que no debe identificarse");
            var excluded=identifiers.preview(1);assertThat(excluded.allowed()).isFalse();assertThat(excluded.message()).isEqualTo("Importación ajena a EPL Sync: no hay correspondencia inequívoca");
            assertThat(client.book(connection,1).eplId()).isNull();
        } finally {SecurityContextHolder.clearContext();server.destroy();if(!server.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly();}
    }
}
