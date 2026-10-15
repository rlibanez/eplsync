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
class NotificationPreferencesTests {
    @Autowired AccountStore accounts;
    @Autowired NotificationPreferences preferences;
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
    @Test void preferencesArePersistentIndependentAndBoundToTheSessionUser() throws Exception {
        var session=login("reader");
        mvc.perform(get("/api/auth/notifications").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.floating").value(true)).andExpect(jsonPath("$.unreadBadge").value(true));
        mvc.perform(put("/api/auth/notifications").session(session).with(csrf()).contentType("application/json")
            .content("{\"floating\":false,\"unreadBadge\":true}")).andExpect(status().isOk());
        assertThat(preferences.get(userId)).isEqualTo(new NotificationPreferences.Preferences(false,true));
        assertThat(preferences.get(adminId)).isEqualTo(NotificationPreferences.defaults());
        mvc.perform(get("/api/auth/notifications").session(login("reader"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.floating").value(false)).andExpect(jsonPath("$.unreadBadge").value(true));
        mvc.perform(put("/api/auth/notifications").session(session).with(csrf()).contentType("application/json")
            .content("{\"floating\":true,\"unreadBadge\":false}")).andExpect(status().isOk());
        assertThat(preferences.get(userId)).isEqualTo(new NotificationPreferences.Preferences(true,false));
        assertThat(jdbc.queryForObject("SELECT notification_preferences FROM users WHERE id=?",String.class,userId)).contains("false");
    }
    @Test void requiresAuthenticationCsrfAndCompletePreferences() throws Exception {
        mvc.perform(get("/api/auth/notifications")).andExpect(status().isUnauthorized());
        var session=login("reader");
        mvc.perform(put("/api/auth/notifications").session(session).contentType("application/json")
            .content("{\"floating\":false,\"unreadBadge\":false}")).andExpect(status().isForbidden());
        for(String input:List.of("{}","{\"floating\":false}","{\"floating\":null,\"unreadBadge\":true}")) {
            mvc.perform(put("/api/auth/notifications").session(session).with(csrf()).contentType("application/json")
                .content(input)).andExpect(status().isBadRequest());
        }
        assertThat(preferences.get(userId)).isEqualTo(NotificationPreferences.defaults());
    }
    @Test void savesFiltersAndDurationWithoutAffectingOtherUsersAndSupportsOldStoredChoices() {
        var value=new NotificationPreferences.Preferences(true,false,false,true,false,true,false,12);
        assertThat(preferences.save(userId,value)).isEqualTo(value);
        assertThat(preferences.get(userId)).isEqualTo(value);
        assertThat(preferences.get(adminId)).isEqualTo(NotificationPreferences.defaults());
        // A request from an older browser must not reset the saved filters.
        preferences.save(userId,new NotificationPreferences.Preferences(false,true,null,null,null,null,null,null));
        assertThat(preferences.get(userId)).isEqualTo(new NotificationPreferences.Preferences(false,true,false,true,false,true,false,12));
        jdbc.update("UPDATE users SET notification_preferences=? WHERE id=?","{\"floating\":false,\"unreadBadge\":true}",userId);
        assertThat(preferences.get(userId)).isEqualTo(new NotificationPreferences.Preferences(false,true));
        for(int seconds:List.of(0,61)) {
            assertThatThrownBy(() -> preferences.save(userId,new NotificationPreferences.Preferences(true,true,true,true,true,true,true,seconds)))
                .isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        }
        assertThat(preferences.get(userId)).isEqualTo(new NotificationPreferences.Preferences(false,true));
    }

}
