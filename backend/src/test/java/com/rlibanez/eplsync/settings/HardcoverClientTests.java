package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class HardcoverClientTests {
    HttpServer server;HardcoverClient client;int status;String body;String request;String authorization;int calls;String rateLimit,ratePolicy;
    @BeforeEach void setup() throws Exception {
        status=200;calls=0;rateLimit=null;ratePolicy=null;body="{\"data\":{\"search\":{\"results\":{\"found\":1,\"hits\":[{\"document\":{\"id\":\"7\",\"slug\":\"the-book\",\"title\":\"The Book\",\"author_names\":[\"Author\"]}}]}}}}";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{
            calls++;authorization=e.getRequestHeaders().getFirst("Authorization");request=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(rateLimit!=null) e.getResponseHeaders().add("RateLimit",rateLimit);if(ratePolicy!=null) e.getResponseHeaders().add("RateLimit-Policy",ratePolicy);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();client=new HardcoverClient("Bearer secret-test-only",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
    }
    @AfterEach void close() {server.stop(0);}
    @Test void searchesWithVariablesAndBothTitleAndAuthor() {
        var result=client.search("The \"Book\"",List.of("Author"),1);assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().id()).isEqualTo(7);assertThat(result.hasNext()).isFalse();
        var data=JsonMapper.builder().build().readTree(request);assertThat(data.path("variables").path("q").asString()).isEqualTo("The \"Book\" Author");assertThat(data.path("query").asString()).doesNotContain("The \"Book\"");assertThat(authorization).isEqualTo("Bearer secret-test-only");assertThat(data.path("query").asString()).contains("title,author_names","sort:$sort");assertThat(data.path("variables").path("sort").asString()).contains("_eval(title:=`The \"Book\"`):desc","_text_match:desc,ratings_count:desc");
    }
    @Test void exposesPopularityFromTheSameSearchBookAndEditionResponses() {
        body="{\"data\":{\"search\":{\"results\":{\"found\":1,\"hits\":[{\"document\":{\"id\":\"7\",\"slug\":\"book\",\"title\":\"Book\",\"author_names\":[\"Author\"],\"ratings_count\":1234}}]}}}}";
        assertThat(client.search("Book",List.of("Author"),1).items().getFirst().ratingsCount()).isEqualTo(1234L);
        body="{\"data\":{\"books\":[{\"id\":7,\"title\":\"Book\",\"slug\":\"book\",\"ratings_count\":0,\"contributions\":[]}]}}";
        assertThat(client.searchIdentifiers(List.of("020161622X"),"",1).items().getFirst().ratingsCount()).isZero();
        assertThat(JsonMapper.builder().build().readTree(request).path("query").asString()).contains("title ratings_count");
        assertThat(client.book(7).ratingsCount()).isZero();
        body="{\"data\":{\"editions\":[{\"id\":11,\"book_id\":7,\"title\":\"Edition\",\"users_count\":87}]}}";
        assertThat(client.editions(7,1).items().getFirst().usersCount()).isEqualTo(87L);
        assertThat(JsonMapper.builder().build().readTree(request).path("query").asString()).contains("book_id users_count");
        assertThat(client.edition(7,11).usersCount()).isEqualTo(87L);
        assertThat(calls).isEqualTo(5);
    }
    @Test void missingPopularityIsNotInventedAsZero() {
        assertThat(client.search("Book",List.of("Author"),1).items().getFirst().ratingsCount()).isNull();
    }
    @Test void capturesQuotaFromExistingResponsesWithoutAnotherRequestAndKeeps429Usage() {
        rateLimit="\"Free\";r=9;t=0, \"daily\";r=4840;t=3600";
        ratePolicy="\"Free\";q=60;w=60;burst=10, \"daily\";q=5000;w=86400";
        client.search("Book",List.of("Author"),1);
        assertThat(client.usage().used()).isEqualTo(160);assertThat(client.usageFor("other-key")).isNull();assertThat(calls).isEqualTo(1);
        rateLimit="\"daily\";r=0;t=3600";status=429;
        assertThatThrownBy(()->client.search("Book",List.of("Author"),1)).hasMessageContaining("límite");
        assertThat(client.usage().remaining()).isZero();assertThat(calls).isEqualTo(2);
    }
    @Test void searchesOnlyTheChosenField() {
        client.search("Book",List.of(),1,true,false);
        var mapper=JsonMapper.builder().build();var data=mapper.readTree(request);
        assertThat(data.path("variables").path("q").asString()).isEqualTo("Book");
        assertThat(data.path("query").asString()).contains("fields:\"title\"").doesNotContain("author_names");
        assertThatThrownBy(()->client.search("Book",List.of("Author"),1,false,false)).hasMessageContaining("al menos");
        assertThat(calls).isEqualTo(1);
    }
    @Test void searchesOnlyAuthorWithoutSendingTitle() {
        client.search("",List.of("Author"),1,false,true);
        var data=JsonMapper.builder().build().readTree(request);
        assertThat(data.path("variables").path("q").asString()).isEqualTo("Author");
        assertThat(data.path("query").asString()).contains("fields:\"author_names\"");
    }
    @Test void unusualTitleDelimitersCannotInjectSortingFilters() {
        client.search("Book`):desc,other:asc",List.of("Author"),1);
        var data=JsonMapper.builder().build().readTree(request);
        assertThat(data.path("variables").path("sort").asString()).isEqualTo("_text_match:desc,ratings_count:desc");
    }
    @Test void acceptsSerializedSearchResults() {
        var mapper=JsonMapper.builder().build();var tree=mapper.readTree(body);body=mapper.writeValueAsString(Map.of("data",Map.of("search",Map.of("results",mapper.writeValueAsString(tree.path("data").path("search").path("results"))))));
        assertThat(client.search("Book",List.of("Author"),2).items()).hasSize(1);
    }
    @Test void rejectsGraphqlPartialResultsAndNeverLeaksServerDetails() {
        body="{\"errors\":[{\"message\":\"secret-test-only\"}],\"data\":{\"search\":{\"results\":{\"hits\":[]}}}}";
        assertThatThrownBy(()->client.search("Book",List.of("Author"),1)).hasMessageContaining("parciales").hasMessageNotContaining("secret-test-only");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={401,403,429,302,500})
    void rejectsHttpErrorsWithoutLeakingTheToken(int code) {
        status=code;assertThatThrownBy(()->client.search("Book",List.of("Author"),1)).hasMessageNotContaining("secret-test-only");assertThat(calls).isEqualTo(1);
    }
    @Test void requiresAKeyAndBothSearchFieldsWithoutSendingAnything() {
        var missing=new HardcoverClient("",URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        assertThatThrownBy(()->missing.search("Book",List.of("Author"),1)).hasMessageContaining("EPLSYNC_HARDCOVER_API_KEY");
        assertThatThrownBy(()->client.search("",List.of("Author"),1)).hasMessageContaining("título");assertThatThrownBy(()->client.search("Book",List.of(),1)).hasMessageContaining("autor");assertThat(calls).isZero();
    }
    @Test void rejectsOversizedResponses() {body="x".repeat(2097153);assertThatThrownBy(()->client.search("Book",List.of("Author"),1)).hasMessageContaining("tamaño");}
    @Test void looksUpIdentifiersUsingVariablesAndTheSameEdition() {
        body="{\"data\":{\"books\":[{\"id\":7,\"title\":\"Book\",\"slug\":\"book\",\"contributions\":[{\"author\":{\"name\":\"Author\"}}]}]}}";
        assertThat(client.searchIdentifiers(List.of("020161622X"),"B000000001",2).items()).hasSize(1);
        var data=JsonMapper.builder().build().readTree(request);
        assertThat(data.path("variables").path("offset").asInt()).isEqualTo(20);
        var conditions=data.path("variables").path("where").path("editions").path("_and");
        assertThat(conditions.size()).isEqualTo(2);
        assertThat(conditions.get(0).path("_or").get(0).path("isbn_10").path("_in").get(0).asString()).isEqualTo("020161622X");
        assertThat(conditions.get(1).path("asin").path("_eq").asString()).isEqualTo("B000000001");
        assertThat(data.path("query").asString()).doesNotContain("020161622X","B000000001");
    }
    @Test void emptyIdentifierSearchNeverCallsTheApi() {
        assertThatThrownBy(()->client.searchIdentifiers(List.of(),"",1)).hasMessageContaining("válidos");assertThat(calls).isZero();
    }
    @Test void rejectsInvalidOrUnknownManualSlugsWithoutInventingAnId() {
        assertThatThrownBy(()->client.bookBySlug("https://other.test/book")).hasMessageContaining("válido");assertThat(calls).isZero();
        body="{\"data\":{\"books\":[]}}";
        assertThatThrownBy(()->client.bookBySlug("missing-book")).hasMessageContaining("inequívoca");
        var data=JsonMapper.builder().build().readTree(request);
        assertThat(data.path("variables").path("slug").asString()).isEqualTo("missing-book");
        assertThat(data.path("query").asString()).doesNotContain("missing-book");
    }
    @Test void editionPagesAreOrderedByPopularityWithAStableTieBreaker() {
        body="{\"data\":{\"editions\":[]}}";
        client.editions(7,2);
        var data=JsonMapper.builder().build().readTree(request);
        assertThat(data.path("query").asString()).contains("order_by:[{users_count:desc},{id:asc}]");
        assertThat(data.path("variables").path("offset").asInt()).isEqualTo(20);
    }
    @Test void filtersEditionLanguageBeforePaginationAndRejectsInvalidCodes() {
        body="{\"data\":{\"editions\":[]}}";
        client.editions(7,2,"es");
        var data=JsonMapper.builder().build().readTree(request);
        var where=data.path("variables").path("where");
        assertThat(where.path("book_id").path("_eq").asLong()).isEqualTo(7);
        assertThat(where.path("language").path("_or").get(0).path("code2").path("_eq").asString()).isEqualTo("es");
        assertThat(data.path("variables").path("offset").asInt()).isEqualTo(20);
        assertThatThrownBy(()->client.editions(7,1,"es OR true")).hasMessageContaining("idioma");assertThat(calls).isEqualTo(1);
    }
    @Test void availableLanguagesCoverTheWholeBookAndHandleMissingCodes() {
        body="{\"data\":{\"editions\":[{\"language\":{\"code2\":\"es\",\"code3\":\"spa\",\"language\":\"Spanish\"}},{\"language\":{\"code2\":\"en\",\"code3\":\"eng\",\"language\":\"English\"}},{\"language\":null},{\"language\":{\"code2\":null,\"code3\":\"ast\",\"language\":\"Asturian\"}}]}}";
        assertThat(client.editionLanguages(7)).extracting(l->l.code()).containsExactly("ast","en","es");
        var data=JsonMapper.builder().build().readTree(request);
        assertThat(data.path("query").asString()).contains("distinct_on:language_id");
        assertThat(data.path("variables").path("book").asLong()).isEqualTo(7);
    }
    @Test void resolvesTheBookOfAnEditionFromTheApiRelation() {
        body="{\"data\":{\"editions\":[{\"id\":11,\"book_id\":7,\"title\":\"Edition\",\"language\":{\"code2\":\"es\"}}]}}";
        var checked=org.mockito.Mockito.spy(client);
        org.mockito.Mockito.doReturn(new HardcoverClient.Match(7,"book","Book",List.of("Author"))).when(checked).book(7);
        var linked=checked.editionBook(11);
        assertThat(linked.book().id()).isEqualTo(7);assertThat(linked.edition().id()).isEqualTo(11);
        assertThatThrownBy(()->client.editionBook(12)).hasMessageContaining("no existe");
        assertThatThrownBy(()->client.editionBook(0)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    @Test void includesCoversInExistingResponsesWithoutAdditionalGraphqlRequests() {
        body="{\"data\":{\"search\":{\"results\":{\"found\":1,\"hits\":[{\"document\":{\"id\":7,\"title\":\"Book\",\"slug\":\"book\",\"author_names\":[\"Author\"],\"image\":{\"url\":\"https://assets.hardcover.app/book.jpg\"}}}]}}}}";
        assertThat(client.search("Book",List.of("Author"),1).items().getFirst().coverUrl()).isEqualTo("https://assets.hardcover.app/book.jpg");assertThat(calls).isEqualTo(1);
        body="{\"data\":{\"editions\":[{\"id\":11,\"book_id\":7,\"image\":{\"url\":\"https://assets.hardcover.app/edition.jpg\"}}]}}";
        assertThat(client.editions(7,1).items().getFirst().coverUrl()).isEqualTo("https://assets.hardcover.app/edition.jpg");assertThat(calls).isEqualTo(2);assertThat(request).contains("image{url}");
    }
    @Test void unsafeAndAbsentCoverUrlsUseAnEmptyPlaceholder() {
        body="{\"data\":{\"books\":[{\"id\":7,\"slug\":\"book\",\"title\":\"Book\",\"image\":{\"url\":\"http://127.0.0.1/private\"},\"contributions\":[]}]}}";
        assertThat(client.book(7).coverUrl()).isEmpty();assertThat(calls).isEqualTo(1);
    }
    @Test void verifiesEditionBelongsToTheSelectedBook() {
        body="{\"data\":{\"editions\":[{\"id\":11,\"book_id\":7,\"title\":\"Edition\",\"isbn_10\":\"020161622X\",\"isbn_13\":\"9780201616224\",\"asin\":\"B000000001\",\"language\":{\"code2\":\"es\"},\"publisher\":{\"name\":\"Publisher\"}}]}}";
        var result=client.edition(7,11);assertThat(result.isbn10()).isEqualTo("020161622X");var data=JsonMapper.builder().build().readTree(request);assertThat(data.path("variables").path("book").asLong()).isEqualTo(7);assertThat(data.path("query").asString()).contains("book_id");
    }
}
