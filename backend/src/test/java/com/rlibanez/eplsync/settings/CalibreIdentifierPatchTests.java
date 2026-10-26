package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CalibreIdentifierPatchTests {
    HttpServer server;CalibreClient client;CalibreSettings.Connection connection;Map<String,String> ids;String uuid;int posts;boolean loseResponse;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() throws Exception {
        client=new CalibreClient();ids=new LinkedHashMap<>(Map.of("epl","123","revision","1.2","isbn","9780201616224"));uuid="identity";posts=0;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            if(e.getRequestURI().getPath().startsWith("/cdb/")) {
                posts++;var body=json.readTree(e.getRequestBody().readAllBytes());assertThat(body.path("changes").properties()).hasSize(1);
                ids=new LinkedHashMap<>();for(var entry:body.path("changes").path("identifiers").properties()) ids.put(entry.getKey(),entry.getValue().asString());
                if(loseResponse) {e.close();return;}
            }
            byte[] body=json.writeValueAsString(Map.of("uuid",uuid,"title","Book","authors",List.of("Author"),"identifiers",ids)).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });server.start();connection=new CalibreSettings.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"","","main");
    }
    @AfterEach void close() {server.stop(0);}
    @Test void addsIdentifiersWithoutChangingEplRevisionOrIsbnAndReplaysWithoutAnotherPost() {
        var before=client.identifierMetadata(connection,9);var additions=Map.of("hardcover","book-slug","hardcover_book","7","hardcover_edition","11");
        var result=client.addIdentifiers(connection,9,before,additions);assertThat(result.identifiers()).containsAllEntriesOf(before.identifiers()).containsAllEntriesOf(additions);
        assertThat(client.addIdentifiers(connection,9,before,additions)).isEqualTo(result);assertThat(posts).isEqualTo(1);
    }
    @Test void reconcilesAResponseLostAfterTheWriteWithoutRepeatingThePost() {
        loseResponse=true;var before=client.identifierMetadata(connection,9);var result=client.addIdentifiers(connection,9,before,Map.of("hardcover_book","7"));
        assertThat(result.identifiers()).containsEntry("hardcover_book","7");client.addIdentifiers(connection,9,before,Map.of("hardcover_book","7"));assertThat(posts).isEqualTo(1);
    }
    @Test void refusesEntryReplacementOrConcurrentMetadataChanges() {
        var before=client.identifierMetadata(connection,9);uuid="replacement";assertThatThrownBy(()->client.addIdentifiers(connection,9,before,Map.of("hardcover_book","7"))).hasMessageContaining("cambiaron");
        uuid="identity";ids.put("external","change");assertThatThrownBy(()->client.addIdentifiers(connection,9,before,Map.of("hardcover_book","7"))).hasMessageContaining("cambiaron");assertThat(posts).isZero();
    }
    @Test void neverOverwritesExistingValues() {
        ids.put("hardcover_book","8");var before=client.identifierMetadata(connection,9);assertThatThrownBy(()->client.addIdentifiers(connection,9,before,Map.of("hardcover_book","7"))).hasMessageContaining("sobrescriben");assertThat(posts).isZero();
    }    @Test void coversUseABoundedAuthenticatedImageEndpoint() {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQX94AAAJfAVfDJ0G+AAAAAElFTkSuQmCC");
        server.createContext("/get/cover/",e->{assertThat(e.getRequestURI().getPath()).isEqualTo("/get/cover/9/main");e.getResponseHeaders().add("Content-Type","image/png");e.sendResponseHeaders(200,png.length);e.getResponseBody().write(png);e.close();});
        var cover=client.cover(connection,9);assertThat(cover.contentType()).isEqualTo("image/png");assertThat(cover.bytes()).isEqualTo(png);assertThat(posts).isZero();
    }
    @Test void fullResolutionCoversMayExceedTheOldThumbnailLimit() {
        byte[] png=new byte[700000];
        System.arraycopy(new byte[]{(byte)137,80,78,71,13,10,26,10},0,png,0,8);
        server.createContext("/get/cover/9/",e->{e.getResponseHeaders().add("Content-Type","image/png");e.sendResponseHeaders(200,png.length);e.getResponseBody().write(png);e.close();});
        assertThat(client.cover(connection,9).bytes()).isEqualTo(png);
    }
    @Test void missingCoversAreOptionalAndHtmlCannotBeServedAsAnImage() {
        server.createContext("/get/cover/9/",e->{e.sendResponseHeaders(404,-1);e.close();});assertThat(client.cover(connection,9).bytes()).isEmpty();
        server.createContext("/get/cover/10/",e->{byte[] html="<html>secret-test-only</html>".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","image/png");e.sendResponseHeaders(200,html.length);e.getResponseBody().write(html);e.close();});
        assertThatThrownBy(()->client.cover(connection,10)).hasMessageNotContaining("secret-test-only");
    }
    @Test void oversizedCoversAndRedirectsAreRejected() {
        server.createContext("/get/cover/9/",e->{byte[] body=new byte[4194305];e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
        assertThatThrownBy(()->client.cover(connection,9)).hasMessageContaining("tamaño");
        server.createContext("/get/cover/10/",e->{e.getResponseHeaders().add("Location","http://127.0.0.1/private");e.sendResponseHeaders(302,-1);e.close();});
        assertThatThrownBy(()->client.cover(connection,10)).hasMessageContaining("portada");
    }

}
