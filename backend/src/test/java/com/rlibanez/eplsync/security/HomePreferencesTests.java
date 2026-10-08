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
        assertThat(loaded.sections()).extracting((HomePreferences.Section entryValue) -> java.util.Objects.requireNonNull(entryValue).id())
            .containsExactlyElementsOf(legacy.stream().map((HomePreferences.Section entryValue) -> java.util.Objects.requireNonNull(entryValue).id()).toList());
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

    private String stored(HomePreferences.Preferences value) {
        String json=mapper.writeValueAsString(value);
        jdbc.update("UPDATE users SET home_preferences=? WHERE id=?",json,userId);
        return json;
    }

    @Test void incompletePreferencesKeepOrderAndChoicesAndAppendMissingSectionsWithoutWriting() throws Exception {
        var input=new HomePreferences.Preferences(List.of(
            new HomePreferences.Section("recentEvents",false,null,25),
            new HomePreferences.Section("newReleases",true,42),
            new HomePreferences.Section("header",false,null)));
        String json=stored(input);
        var loaded=preferences.get(userId);
        assertThat(loaded.sections().subList(0,3)).isEqualTo(input.sections());
        assertThat(loaded.sections()).extracting(HomePreferences.Section::id)
            .containsExactly("recentEvents","newReleases","header","overview","recentUpdates","recentBooks");
        assertThat(loaded.sections().subList(3,6)).containsExactly(
            new HomePreferences.Section("overview",true,null),
            new HomePreferences.Section("recentUpdates",true,10),
            new HomePreferences.Section("recentBooks",true,10));
        assertThat(jdbc.queryForObject("SELECT home_preferences FROM users WHERE id=?",String.class,userId)).isEqualTo(json);
        mvc.perform(get("/api/auth/home").session(login("reader"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.sections[0].eventCount").value(25))
            .andExpect(jsonPath("$.sections[1].bookCount").value(42))
            .andExpect(jsonPath("$.sections[5].id").value("recentBooks"));
        assertThat(preferences.save(userId,loaded)).isEqualTo(loaded);
        assertThat(preferences.get(userId)).isEqualTo(loaded);
    }

    @Test void unknownSectionsAreIgnoredAndFirstKnownDuplicateWins() {
        stored(new HomePreferences.Preferences(Arrays.asList(
            new HomePreferences.Section("retiredSection",false,99,99),null,
            new HomePreferences.Section("recentBooks",false,7),
            new HomePreferences.Section("recentBooks",true,90),
            new HomePreferences.Section(null,true,null),
            new HomePreferences.Section("overview",false,null))));
        var loaded=preferences.get(userId);
        assertThat(loaded.sections()).extracting(HomePreferences.Section::id)
            .containsExactly("recentBooks","overview","header","newReleases","recentUpdates","recentEvents");
        assertThat(loaded.sections().getFirst()).isEqualTo(new HomePreferences.Section("recentBooks",false,7));
        assertThat(loaded.sections().get(1).enabled()).isFalse();
        assertThat(loaded.sections().getLast()).isEqualTo(new HomePreferences.Section("recentEvents",true,null,10));
    }

    @Test void missingAndInvalidStoredFieldsUseSectionDefaults() {
        stored(new HomePreferences.Preferences(List.of(
            new HomePreferences.Section("newReleases",null,null),
            new HomePreferences.Section("recentUpdates",false,0),
            new HomePreferences.Section("recentBooks",false,101),
            new HomePreferences.Section("recentEvents",false,9,null),
            new HomePreferences.Section("header",true,99,99))));
        var loaded=preferences.get(userId);
        assertThat(loaded.sections().getFirst()).isEqualTo(new HomePreferences.Section("newReleases",true,10));
        assertThat(loaded.sections().get(1)).isEqualTo(new HomePreferences.Section("recentUpdates",false,10));
        assertThat(loaded.sections().get(2)).isEqualTo(new HomePreferences.Section("recentBooks",false,10));
        assertThat(loaded.sections().get(3)).isEqualTo(new HomePreferences.Section("recentEvents",false,null,10));
        assertThat(loaded.sections().get(4)).isEqualTo(new HomePreferences.Section("header",true,null));
    }

    @Test void emptyOrNullStoredListsUseDefaultsAndFuturePropertiesAreIgnored() {
        for(var input:List.of("null","{}","{\"sections\":null}","{\"sections\":[]}")) {
            jdbc.update("UPDATE users SET home_preferences=? WHERE id=?",input,userId);
            assertThat(preferences.get(userId)).isEqualTo(HomePreferences.defaults());
        }
        jdbc.update("UPDATE users SET home_preferences=? WHERE id=?",
            "{\"futureProperty\":true,\"sections\":[{\"id\":\"newReleases\",\"enabled\":false,\"bookCount\":37,\"futureField\":9}]}",userId);
        assertThat(preferences.get(userId).sections().getFirst()).isEqualTo(new HomePreferences.Section("newReleases",false,37));
    }

    @Test void savingStillRejectsUnknownAndMissingSections() {
        var unknown=new ArrayList<>(HomePreferences.defaults().sections());
        unknown.set(0,new HomePreferences.Section("removed",true,null));
        assertThatThrownBy(() -> preferences.save(userId,new HomePreferences.Preferences(unknown)))
            .isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(() -> preferences.save(userId,new HomePreferences.Preferences(List.of(unknown.get(1)))))
            .isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(preferences.get(userId)).isEqualTo(HomePreferences.defaults());
    }
}
