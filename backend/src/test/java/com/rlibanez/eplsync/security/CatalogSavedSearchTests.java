package com.rlibanez.eplsync.security;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class CatalogSavedSearchTests {
    @Autowired AccountStore accounts;
    @Autowired com.rlibanez.eplsync.service.CatalogSavedSearches searches;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired LoginThrottle throttle;
    MockMvc mvc;
    static final String PASSWORD="permanent password for testing";
    String adminId,userId;
    tools.jackson.databind.json.JsonMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    @BeforeEach void setup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        accounts.clear();
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(throttle,"attempts")).clear();
        adminId=accounts.initialize("administrator","admin@example.org",PASSWORD,PASSWORD).id();
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        accounts.register("reader","reader@example.org",PASSWORD);
        userId=accounts.users().stream().filter(user -> user.username().equals("reader")).findFirst().orElseThrow().id();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    MockHttpSession login(String username) throws Exception {
        var response=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("username",username,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)response.getRequest().getSession(false);
    }

    @Test void savesAndRestoresAllAdditionalNegationOperators() throws Exception {
        var reader=login("reader");var filters=new LinkedHashMap<String,List<String>>();
        for(String key:List.of("eplId","collection","genres","revision")) {filters.put(key,List.of(key.equals("eplId")?"1":key.equals("revision")?"1.4":"Text"));filters.put(key+"Operator",List.of("ne"));}
        var body=mapper.writeValueAsString(Map.of("name","Excluded","filters",filters));
        mvc.perform(post("/api/catalog/saved-searches").session(reader).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        var response=mvc.perform(get("/api/catalog/saved-searches").session(reader)).andExpect(status().isOk()).andReturn();
        var saved=mapper.readTree(response.getResponse().getContentAsString()).get(0).get("filters");
        for(String key:List.of("eplId","collection","genres","revision")) assertThat(saved.get(key+"Operator").get(0).asString()).isEqualTo("ne");
    }
    @Test void personalCrudAndIsolationPersistOnlyFilters() throws Exception {
        var reader=login("reader");
        String body=mapper.writeValueAsString(Map.of("name","Autores","filters",Map.of("author",List.of("Pratchett, Terry","Orwell"),"authorOperator",List.of("NOT"))));
        var response=mvc.perform(post("/api/catalog/saved-searches").session(reader).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.filters.author[0]").value("Pratchett, Terry")).andReturn();
        String id=mapper.readTree(response.getResponse().getContentAsString()).get("id").asString();
        var admin=login("administrator");
        mvc.perform(get("/api/catalog/saved-searches").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/catalog/saved-searches/"+id).session(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/catalog/saved-searches/"+id).session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/saved-searches").session(reader)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        String updated=mapper.writeValueAsString(Map.of("name","Renombrada","filters",Map.of("title",List.of("1984"))));
        mvc.perform(put("/api/catalog/saved-searches/"+id).session(reader).with(csrf()).contentType("application/json").content(updated))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renombrada")).andExpect(jsonPath("$.filters.author").doesNotExist());
        mvc.perform(delete("/api/catalog/saved-searches/"+id).session(reader).with(csrf())).andExpect(status().isOk());
        assertThat(searches.list(userId)).isEmpty();
    }
    @Test void rejectsTableSettingsDuplicateNamesAndInvalidNamesAndRequiresCsrf() throws Exception {
        var session=login("reader");
        for(String key:List.of("sort","page","size","columns","view")) {
            mvc.perform(post("/api/catalog/saved-searches").session(session).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("name","Invalid","filters",Map.of(key,List.of("value")))))).andExpect(status().isBadRequest());
        }
        for(String name:List.of(" ","x".repeat(121))) {
            mvc.perform(post("/api/catalog/saved-searches").session(session).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("name",name,"filters",Map.of())))).andExpect(status().isBadRequest());
        }
        String body="{\"name\":\"All\",\"filters\":{}}";
        mvc.perform(post("/api/catalog/saved-searches").session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/catalog/saved-searches").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/catalog/saved-searches").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/saved-searches")).andExpect(status().isUnauthorized());
    }
    @Test void supportsLargeIdListsAndRemovesPersonalDataWithTheUser() {
        var ids=java.util.stream.LongStream.rangeClosed(1,73000).mapToObj(Long::toString).toList();
        var filters=Map.of("eplId",ids);
        var search=searches.save(userId,null,new com.rlibanez.eplsync.service.CatalogSavedSearches.Input("Todo el catálogo",filters));
        assertThat(searches.list(userId).getFirst().filters().get("eplId")).isEqualTo(ids);
        jdbc.update("DELETE FROM users WHERE id=?",userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_saved_searches WHERE id=?",Long.class,search.id())).isZero();
    }
    @Test void requiresCatalogReadPermission() throws Exception {
        accounts.update(userId,"USER","ACTIVE",Map.of("CATALOG_READ","DENY"),adminId);
        var session=login("reader");
        mvc.perform(get("/api/catalog/saved-searches").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/catalog/saved-searches").session(session).with(csrf()).contentType("application/json")
            .content("{\"name\":\"Test\",\"filters\":{}}" )).andExpect(status().isForbidden());
    }

}
