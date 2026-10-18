package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CalibreIdentifierProtocolTests {
    HttpServer server;CalibreClient client=new CalibreClient();CalibreSettings.Connection connection;
    Map<String,String> ids;String uuid;boolean uncertain;AtomicInteger posts;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void start() throws Exception {
        ids=new LinkedHashMap<>(Map.of("isbn","9780000000001"));uuid="book-uuid";posts=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",r->{
            int status=200;
            if(r.getRequestMethod().equals("POST")) {
                assertThat(r.getRequestURI().getPath()).isEqualTo("/cdb/set-fields/9/main");
                posts.incrementAndGet();var data=json.readTree(r.getRequestBody().readAllBytes());
                assertThat(data.path("changes").properties()).hasSize(1);
                ids=new LinkedHashMap<>();for(var entry:data.path("changes").path("identifiers").properties()) ids.put(entry.getKey(),entry.getValue().asString());
                if(uncertain) {uncertain=false;status=500;}
            } else assertThat(r.getRequestURI().getPath()).isEqualTo("/ajax/book/9/main");
            var response=new LinkedHashMap<String,Object>(Map.of("title","Book","authors",List.of("Author"),"uuid",uuid,"formats",List.of("EPUB")));
            if(!ids.isEmpty()) response.put("identifiers",ids);
            byte[] body=json.writeValueAsBytes(response);
            r.getResponseHeaders().add("Content-Type","application/json");r.sendResponseHeaders(status,body.length);try(var out=r.getResponseBody()){out.write(body);}
        });server.start();connection=new CalibreSettings.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"","","main");
    }
    @AfterEach void close(){server.stop(0);}
    @Test void fillsMissingIdentifiersPreservingOthersAndRepeatedRequestsDoNotWrite() {
        var result=client.completeIdentifiers(connection,9,1,new BigDecimal("1.10"),uuid);
        assertThat(result.eplId()).isEqualTo(1);assertThat(result.revision()).isEqualByComparingTo("1.1");
        assertThat(ids).containsEntry("isbn","9780000000001").containsEntry("epl","1").containsEntry("revision","1.1");
        client.completeIdentifiers(connection,9,1,new BigDecimal("1.1"),uuid);assertThat(posts.get()).isEqualTo(1);
    }
    @Test void acceptsBooksWhoseIdentifierFieldIsOmittedByCalibreWhenEmpty() {
        ids.clear();var book=client.completeIdentifiers(connection,9,1,new BigDecimal("1.1"),uuid);
        assertThat(book.eplId()).isEqualTo(1);assertThat(posts.get()).isEqualTo(1);
    }
    @Test void aLostWriteResponseIsVerifiedBeforeAnyRepeatedWrite() {
        uncertain=true;
        assertThatThrownBy(()->client.completeIdentifiers(connection,9,1,new BigDecimal("1.1"),uuid)).isInstanceOf(CalibreClient.TransientFailure.class);
        client.completeIdentifiers(connection,9,1,new BigDecimal("1.1"),uuid);assertThat(posts.get()).isEqualTo(1);
    }
    @Test void neverOverwritesConflictingOrInvalidIdentifiers() {
        for(var conflict:List.of(Map.of("epl","2"),Map.of("revision","2"),Map.of("epl","invalid"),Map.of("revision","invalid"))) {
            ids=new LinkedHashMap<>(conflict);
            assertThatThrownBy(()->client.completeIdentifiers(connection,9,1,new BigDecimal("1.1"),uuid)).hasMessageContaining("distintos");
        }
        assertThat(posts.get()).isZero();
    }
    @Test void refusesReusedBookIdsWithDifferentUuids() {
        assertThatThrownBy(()->client.completeIdentifiers(connection,9,1,new BigDecimal("1.1"),"old-uuid")).hasMessageContaining("identidad");assertThat(posts.get()).isZero();
    }
}
