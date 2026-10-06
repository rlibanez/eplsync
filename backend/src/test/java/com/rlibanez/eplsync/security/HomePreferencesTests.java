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

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class HomePreferencesTests {
    @Autowired AccountStore accounts;
    @Autowired HomePreferences preferences;
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
    HomePreferences.Preferences customized(int count) {
        var sections=new ArrayList<>(HomePreferences.defaults().sections());
        Collections.reverse(sections);
        sections.replaceAll(section -> new HomePreferences.Section(section.id(),false,section.bookCount()==null ? null : count,section.eventCount()==null ? null : count));
        return new HomePreferences.Preferences(sections);
    }
    @Test void usersWithoutSettingsPermissionCanSaveOnlyTheirOwnPreferences() throws Exception {
        var session=login("reader");
        var value=customized(100);
        mvc.perform(put("/api/auth/home").session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("userId",adminId,"sections",value.sections()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sections[0].id").value("recentEvents"));
        assertThat(preferences.get(userId)).isEqualTo(value);
        assertThat(preferences.get(adminId)).isEqualTo(HomePreferences.defaults());
        mvc.perform(get("/api/auth/home").session(login("reader"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.sections[2].bookCount").value(100));
        assertThat(accounts.find(userId).permissions()).doesNotContain(Permission.CATALOG_IMPORT);
    }
    @Test void rejectsInvalidCountsAndSectionListsWithoutChangingSavedPreferences() throws Exception {
        var session=login("reader");
        for(int count:new int[]{0,101}) mvc.perform(put("/api/auth/home").session(session).with(csrf())
            .contentType("application/json").content(mapper.writeValueAsString(customized(count)))).andExpect(status().isBadRequest());
        var invalid=new ArrayList<>(HomePreferences.defaults().sections());
        invalid.set(1,invalid.getFirst());
        mvc.perform(put("/api/auth/home").session(session).with(csrf()).contentType("application/json")
            .content(mapper.writeValueAsString(new HomePreferences.Preferences(invalid)))).andExpect(status().isBadRequest());
        invalid.set(1,new HomePreferences.Section(null,true,null));
        mvc.perform(put("/api/auth/home").session(session).with(csrf()).contentType("application/json")
            .content(mapper.writeValueAsString(new HomePreferences.Preferences(invalid)))).andExpect(status().isBadRequest());
        assertThat(preferences.get(userId)).isEqualTo(HomePreferences.defaults());
    }
    @Test void validatesEventCountsAndUpgradesSavedPreferencesWithoutChangingOtherChoices() throws Exception {
        var legacy=customized(20).sections().stream()
            .map(section -> new HomePreferences.Section(section.id(),section.enabled(),section.bookCount())).toList();
        jdbc.update("UPDATE users SET home_preferences=? WHERE id=?",mapper.writeValueAsString(new HomePreferences.Preferences(legacy)),userId);
        var loaded=preferences.get(userId);
        assertThat(loaded.sections().getFirst()).isEqualTo(new HomePreferences.Section("recentEvents",false,null,10));
        assertThat(loaded.sections()).extracting(HomePreferences.Section::id)
            .containsExactlyElementsOf(legacy.stream().map(HomePreferences.Section::id).toList());
        assertThat(loaded.sections()).allSatisfy(section -> assertThat(section.enabled()).isFalse());
        assertThat(loaded.sections().get(2).bookCount()).isEqualTo(20);
        var session=login("reader");
        for(int count:new int[]{0,101}) {
            var sections=new ArrayList<>(loaded.sections());
            sections.set(0,new HomePreferences.Section("recentEvents",false,null,count));
            mvc.perform(put("/api/auth/home").session(session).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(new HomePreferences.Preferences(sections))))
                .andExpect(status().isBadRequest());
        }
        assertThat(preferences.get(userId)).isEqualTo(loaded);
    }

    @Test void requiresAuthenticationAndCsrfAndAcceptsMinimumCount() throws Exception {
        mvc.perform(get("/api/auth/home")).andExpect(status().isUnauthorized());
        var session=login("reader");
        mvc.perform(put("/api/auth/home").session(session).contentType("application/json")
            .content(mapper.writeValueAsString(customized(1)))).andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/home").session(session).with(csrf()).contentType("application/json")
            .content(mapper.writeValueAsString(customized(1)))).andExpect(status().isOk());
        assertThat(preferences.get(userId)).isEqualTo(customized(1));
    }
}
