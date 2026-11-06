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
class AppearancePreferencesTests {
    @Autowired AccountStore accounts;
    @Autowired AppearancePreferences preferences;
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

    private String body(String id,String scheme,String palette,boolean initialize) {
        return mapper.writeValueAsString(Map.of("userId",id,"scheme",scheme,"palette",palette,"initialize",initialize));
    }
    @Test void anonymousAccessAndWritesWithoutCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/auth/appearance")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/auth/appearance").with(csrf()).contentType("application/json").content(body(userId,"dark","teal",false))).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/auth/appearance").session(login("reader")).contentType("application/json").content(body(userId,"dark","teal",false))).andExpect(status().isForbidden());
    }
    @Test void personalPreferencesPersistAndAreIsolatedEvenFromAdministrators() throws Exception {
        var reader=login("reader");
        mvc.perform(get("/api/auth/appearance").session(reader)).andExpect(status().isOk()).andExpect(jsonPath("$.scheme").isEmpty());
        mvc.perform(put("/api/auth/appearance").session(reader).with(csrf()).contentType("application/json").content(body(userId,"light","rose",false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scheme").value("light")).andExpect(jsonPath("$.palette").value("rose"));
        var administrator=login("administrator");
        mvc.perform(get("/api/auth/appearance").session(administrator)).andExpect(status().isOk()).andExpect(jsonPath("$.scheme").isEmpty());
        mvc.perform(put("/api/auth/appearance").session(administrator).with(csrf()).contentType("application/json").content(body(userId,"dark","blue",false)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/appearance").session(login("reader"))).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId)).andExpect(jsonPath("$.palette").value("rose"));
        assertThat(new AppearancePreferences(jdbc).get(userId).scheme()).isEqualTo("light");
    }
    @Test void initializationIsAtomicAndCannotOverwriteAPreviouslySavedChoice() throws Exception {
        var session=login("reader");
        for(String palette:List.of("violet","orange"))
            mvc.perform(put("/api/auth/appearance").session(session).with(csrf()).contentType("application/json").content(body(userId,"dark",palette,true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.palette").value("violet"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_appearance WHERE user_id=?",Integer.class,userId)).isEqualTo(1);
        mvc.perform(put("/api/auth/appearance").session(session).with(csrf()).contentType("application/json").content(body(userId,"light","orange",false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.palette").value("orange"));
    }
    @Test void invalidValuesCannotChangeSavedPreferences() throws Exception {
        preferences.save(userId,new AppearancePreferences.Input(userId,"dark","teal",false));
        var session=login("reader");
        for(var values:List.of(List.of("system","teal"),List.of("light","invalid")))
            mvc.perform(put("/api/auth/appearance").session(session).with(csrf()).contentType("application/json").content(body(userId,values.get(0),values.get(1),false)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/appearance").session(session).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        assertThat(preferences.get(userId).palette()).isEqualTo("teal");
    }
}
