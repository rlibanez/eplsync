package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CalibreTransferProtocolTests {
    @TempDir Path directory;
    HttpServer server;CalibreClient client=new CalibreClient();CalibreSettings.Connection connection;
    String searchBooks="{\"8\":{\"identifiers\":{\"epl\":\"11\",\"revision\":\"1.1\"},\"formats\":[\"PDF\"]},\"9\":{\"identifiers\":{\"epl\":\"1\",\"revision\":\"1.10\"},\"formats\":[\"EPUB\"]}}";
    String uploadResponse="{\"book_id\":9}";int uploadStatus=200;byte[] sent;String uri;AtomicInteger posts=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", request -> {
            String path=request.getRequestURI().getPath(),body;
            if(request.getRequestMethod().equals("POST")) {sent=request.getRequestBody().readAllBytes();uri=request.getRequestURI().toString();posts.incrementAndGet();body=uploadResponse;}
            else if(path.contains("/search/")) body="{\"total_num\":2,\"book_ids\":[8,9]}";
            else if(path.contains("/books/")) body=searchBooks;
            else body="{\"title\":\"Book\",\"authors\":[\"Author\"],\"identifiers\":{\"epl\":\"1\",\"revision\":\"1.1\"}}";
            request.getResponseHeaders().add("Content-Type","application/json");
            if(uploadStatus==302) request.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.sendResponseHeaders(request.getRequestMethod().equals("POST")?uploadStatus:200,bytes.length);
            try(var out=request.getResponseBody()) {out.write(bytes);}
        });server.start();connection=new CalibreSettings.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"","","Library with spaces");
    }
    @AfterEach void close() {server.stop(0);}
    @Test void missingFormatsAreNotTreatedAsConfirmedEpubRemoval() {
        assertThatThrownBy(()->client.hasEpub(connection,9)).hasMessageContaining("formatos");
    }
    @Test void removesOnlyRequestedFormatOrEntireBookWithoutRetries() {
        uploadResponse="{}";
        client.remove(connection,7,false);
        assertThat(uri).contains("/cdb/set-fields/7/Library%20with%20spaces");
        assertThat(new String(sent,StandardCharsets.UTF_8)).isEqualTo("{\"changes\":{\"removed_formats\":[\"EPUB\"]}}");
        client.remove(connection,8,true);
        assertThat(uri).contains("/cdb/delete-books/8/Library%20with%20spaces");assertThat(posts.get()).isEqualTo(2);
        uploadStatus=500;assertThatThrownBy(()->client.remove(connection,8,true)).hasMessageContaining("HTTP 500");assertThat(posts.get()).isEqualTo(3);
    }
    @Test void uploadsRawFileToSelectedLibraryAndParsesConfirmation() throws Exception {
        byte[] epub=CalibreTransfersTests.epub(1,"1.1");Path path=directory.resolve("book.epub");Files.write(path,epub);
        assertThat(client.uploadEpub(connection,path,"Libro & revisión.epub","token")).isEqualTo(9);
        assertThat(sent).isEqualTo(epub);assertThat(uri).contains("/cdb/add-book/token/1/Libro%20%26%20revisi%C3%B3n.epub/Library%20with%20spaces");assertThat(posts.get()).isEqualTo(1);
    }
    @Test void checksIdentifiersNumericallyAndIgnoresDifferentIds() {
        assertThat(client.findRevision(connection,1,new BigDecimal("1.1"))).isEqualTo(9);
        assertThat(client.findRevision(connection,1,new BigDecimal("1.2"))).isNull();
        assertThat(client.book(connection,9).eplId()).isEqualTo(1);
    }
    @Test void malformedOrServerErrorIsUnconfirmedAndNeverRetried() throws Exception {
        var path=directory.resolve("book.epub");Files.write(path,CalibreTransfersTests.epub(1,"1.1"));uploadResponse="invalid";
        assertThatThrownBy(() -> client.uploadEpub(connection,path,"book.epub","token")).isInstanceOf(CalibreClient.UnconfirmedUpload.class);assertThat(posts.get()).isEqualTo(1);
        uploadResponse="{}";uploadStatus=500;
        assertThatThrownBy(() -> client.uploadEpub(connection,path,"book.epub","token")).isInstanceOf(CalibreClient.UnconfirmedUpload.class);assertThat(posts.get()).isEqualTo(2);
    }
    @Test void doesNotFollowRedirectsOrRetryAuthenticationFailures() throws Exception {
        var path=directory.resolve("book.epub");Files.write(path,CalibreTransfersTests.epub(1,"1.1"));uploadStatus=302;
        assertThatThrownBy(() -> client.uploadEpub(connection,path,"book.epub","token")).hasMessageContaining("redirección");assertThat(posts.get()).isEqualTo(1);
        uploadStatus=403;assertThatThrownBy(() -> client.uploadEpub(connection,path,"book.epub","token")).hasMessageContaining("rechazado");assertThat(posts.get()).isEqualTo(2);
    }
    @Test void readsBoundedEpubIdentifiersAndRejectsUnknownOrInvalidMetadata() throws Exception {
        var path=directory.resolve("book.epub");Files.write(path,CalibreTransfersTests.epub(1,"1.10"));
        assertThat(CalibreEpubMetadata.read(path).revision()).isEqualByComparingTo("1.1");
        Files.write(path,CalibreTransfersTests.epub(0,"1.1"));assertThatThrownBy(() -> CalibreEpubMetadata.read(path)).hasMessageContaining("validar");
        Files.write(path,CalibreTransfersTests.epub(1,"Infinity"));assertThatThrownBy(() -> CalibreEpubMetadata.read(path)).hasMessageContaining("validar");
    }
    @Test void recognizesEpubTwoSchemeIdentifiersWithUppercaseEpl() throws Exception {
        var path=directory.resolve("pantagruel.epub");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            var entries=Map.of("mimetype","application/epub+zip",
                "META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
                "OEBPS/content.opf","<package version='2.0' xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/' xmlns:opf='http://www.idpf.org/2007/opf'><dc:identifier opf:scheme='EPL'>34644</dc:identifier><dc:identifier opf:scheme='revision'>1.6</dc:identifier><dc:identifier opf:scheme='UUID'>urn:uuid:57962213-6a86-4efd-db7d-9b9f4d481c16</dc:identifier></metadata></package>");
            for(var entry:entries.entrySet()) {zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        var identity=CalibreEpubMetadata.read(path);
        assertThat(identity.eplId()).isEqualTo(34644);
        assertThat(identity.revision()).isEqualByComparingTo("1.6");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"urn:epl:60525", "URN:EPL:60525"})
    void recognizesEpubThreeUrnIdentifiers(String identifier) throws Exception {
        var path=directory.resolve("hail-mary.epub");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            var entries=Map.of("mimetype","application/epub+zip",
                "META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
                "OEBPS/content.opf","<package version='3.0' xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier>"+identifier+"</dc:identifier><dc:identifier>urn:revision:3.4</dc:identifier><dc:identifier id='BookId'>urn:uuid:c6d4355c-9d3c-473b-bfb0-db738a360e1a</dc:identifier></metadata></package>");
            for(var entry:entries.entrySet()) {zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        var identity=CalibreEpubMetadata.read(path);
        assertThat(identity.eplId()).isEqualTo(60525);
        assertThat(identity.revision()).isEqualByComparingTo("3.4");
    }
    @Test void rejectsExternalEntitiesAndOversizedXmlEntries() throws Exception {
        var path=directory.resolve("attack.epub");
        for(String xml:List.of("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><container>&leak;</container>"," ".repeat(1048577))) {
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("mimetype"));zip.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/container.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            assertThatThrownBy(() -> CalibreEpubMetadata.read(path)).hasMessageContaining("validar");
        }
    }
    @Test void revisionSearchContinuesPastMatchingMetadataWithoutEpub() {
        searchBooks="{\"8\":{\"identifiers\":{\"epl\":\"1\",\"revision\":\"1.1\"},\"formats\":[\"PDF\"]},\"9\":{\"identifiers\":{\"epl\":\"1\",\"revision\":\"1.1\"},\"formats\":[\"epub\"]}}";
        assertThat(client.findRevision(connection,1,new BigDecimal("1.1"))).isEqualTo(9);
        searchBooks=searchBooks.replace("epub","PDF");
        assertThat(client.findRevision(connection,1,new BigDecimal("1.1"))).isNull();
    }
    @Test void revisionSearchCannotConfirmBooksWithMissingFormats() {
        searchBooks="{\"8\":{\"identifiers\":{\"epl\":\"1\",\"revision\":\"1.1\"}},\"9\":{}}";
        assertThatThrownBy(() -> client.findRevision(connection,1,new BigDecimal("1.1"))).hasMessageContaining("formatos");
    }

    @Test void connectionFailurePreservesTechnicalCauseWithoutLoggingSecrets() throws Exception {
        int port;try(var socket=new java.net.ServerSocket(0)) {port=socket.getLocalPort();}
        try {client.check(new CalibreSettings.Connection("http://127.0.0.1:"+port,"private-user","secret-password","main"));org.junit.jupiter.api.Assertions.fail("Expected failure");}
        catch(CalibreClient.TransientFailure failure) {
            assertThat(failure.getCause()).isNotNull();
            assertThat(com.rlibanez.eplsync.exception.OperationDiagnostics.technical(failure)).contains("Connect").doesNotContain("secret-password","private-user");
        }
    }
}
