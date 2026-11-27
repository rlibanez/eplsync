package com.rlibanez.eplsync.security;

import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.datasource.hikari.maximum-pool-size=1","eplsync.security.remember-me-days=45","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.inventory.worker-enabled=false","eplsync.updates.scheduler-enabled=false","eplsync.revision-cleanup.worker-enabled=false"})
class RememberedAccessTests {
    @Autowired AccountStore accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired LoginThrottle throttle;
    MockMvc mvc;Account owner;
    static final String PASSWORD="permanent password for remember tests";
    @BeforeEach void setup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        accounts.clear();owner=accounts.initialize("owner","owner@example.org",PASSWORD,PASSWORD);
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(throttle,"attempts")).clear();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    String json(Object value) {return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);}
    MvcResult login(boolean remember) throws Exception {
        return mvc.perform(post("/api/auth/login").secure(true).with(csrf()).contentType("application/json")
            .content(json(Map.of("username","owner","password",PASSWORD,"rememberMe",remember)))).andExpect(status().isOk()).andReturn();
    }
    Cookie cookie(MvcResult result) {
        String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(s->s.startsWith(RememberedAccess.COOKIE+"=")).findFirst().orElseThrow();
        return new Cookie(RememberedAccess.COOKIE,header.substring(header.indexOf('=')+1,header.indexOf(';')));
    }
    @Test void optInUsesEnvironmentDurationAndStoresOnlyHashWithSecureCookieFlags() throws Exception {
        var regular=login(false);assertThat(jdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isZero();
        assertThat(regular.getResponse().getHeaders("Set-Cookie")).noneMatch(s->s.startsWith(RememberedAccess.COOKIE+"="));
        var result=login(true);var cookie=cookie(result);var row=jdbc.queryForMap("SELECT * FROM remembered_access");
        assertThat(cookie.getValue()).hasSize(43);assertThat(row.get("token_hash")).isEqualTo(RememberedAccess.hash(cookie.getValue())).isNotEqualTo(cookie.getValue());
        assertThat(((Number)row.get("expires_at")).longValue()-((Number)row.get("issued_at")).longValue()).isEqualTo(Duration.ofDays(45).toMillis());
        assertThat(result.getResponse().getHeaders("Set-Cookie").toString()).contains("HttpOnly","SameSite=Lax","Secure","Max-Age=3888000");
        mvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.rememberMeDays").value(45));
    }
    @Test void restoresWithoutOldHttpSessionAndNeverExtendsExpiryOnActivity() throws Exception {
        var result=login(true);var cookie=cookie(result);((MockHttpSession)result.getRequest().getSession()).invalidate();
        long expiry=jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class);
        var restored=mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("owner")).andReturn();
        mvc.perform(get("/api/auth/me").session((MockHttpSession)restored.getRequest().getSession()).cookie(cookie)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class)).isEqualTo(expiry);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isEqualTo(1);
    }
    @Test void rememberedLoginRecoversAfterNormalMaximumHoursButNormalLoginStillExpires() throws Exception {
        for(boolean remember:new boolean[]{false,true}) {
            var result=login(remember);var session=(MockHttpSession)result.getRequest().getSession();
            var security=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
            var a=(Account)security.getAuthentication().getPrincipal();
            var old=new Account(a.id(),a.username(),a.email(),a.role(),a.status(),false,null,a.securityVersion(),a.permissions(),Instant.now().minus(Duration.ofHours(13)));
            security.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(old,null,security.getAuthentication().getAuthorities()));
            var request=get("/api/auth/me").session(session);if(remember) request.cookie(cookie(result));
            mvc.perform(request).andExpect(remember?status().isOk():status().isUnauthorized());
        }
    }
    @Test void expiryAndReductionInvalidateActiveSessionsAndIncreasingPolicyCannotReviveThem() throws Exception {
        var result=login(true);var cookie=cookie(result);var session=(MockHttpSession)result.getRequest().getSession();
        long issued=Instant.now().minus(Duration.ofDays(2)).toEpochMilli();jdbc.update("UPDATE remembered_access SET issued_at=?",issued);
        accounts.policy(new AccountStore.Policy(false,true,30,12,8,1));
        long expiry=jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class);
        assertThat(expiry).isEqualTo(issued+Duration.ofDays(1).toMillis());
        accounts.policy(new AccountStore.Policy(false,true,30,12,8,365));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class)).isEqualTo(expiry);
        mvc.perform(get("/api/auth/me").session(session).cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }
    @Test void fixedExpiryWithoutPolicyChangesIsEnforced() throws Exception {
        var result=login(true);long now=Instant.now().toEpochMilli();
        jdbc.update("UPDATE remembered_access SET issued_at=?,expires_at=?",now-Duration.ofDays(46).toMillis(),now-1);
        mvc.perform(get("/api/auth/me").cookie(cookie(result))).andExpect(status().isUnauthorized());
    }
    @Test void logoutRevokesCredentialAndReplayingTheCookieCannotAuthenticate() throws Exception {
        var result=login(true);var cookie=cookie(result);
        var logout=mvc.perform(post("/api/auth/logout").session((MockHttpSession)result.getRequest().getSession()).cookie(cookie).with(csrf())).andExpect(status().isOk()).andReturn();
        assertThat(logout.getResponse().getHeaders("Set-Cookie").toString()).contains("Max-Age=0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isZero();
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }
    @Test void passwordChangeRevokesEveryDeviceAndPrivilegeChangesAndDisablingAlsoRevoke() throws Exception {
        var first=login(true);var second=login(true);
        accounts.changePassword(accounts.find(owner.id()),PASSWORD,"replacement permanent password");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isZero();
        mvc.perform(get("/api/auth/me").cookie(cookie(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session((MockHttpSession)second.getRequest().getSession()).cookie(cookie(second))).andExpect(status().isUnauthorized());
        accounts.changePassword(accounts.find(owner.id()),"replacement permanent password",PASSWORD);
        var remembered=login(true);accounts.update(owner.id(),"ADMIN","ACTIVE",Map.of(),owner.id());
        mvc.perform(get("/api/auth/me").cookie(cookie(remembered))).andExpect(status().isUnauthorized());
        accounts.policy(new AccountStore.Policy(true,false,30,12));accounts.register("reader","",PASSWORD);
        var reader=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json(Map.of("username","reader","password",PASSWORD,"rememberMe",true)))).andExpect(status().isOk()).andReturn();
        String id=accounts.users().stream().filter(u->u.username().equals("reader")).findFirst().orElseThrow().id();
        accounts.update(id,"USER","DISABLED",Map.of(),owner.id());
        mvc.perform(get("/api/auth/me").cookie(cookie(reader))).andExpect(status().isUnauthorized());
    }
    @Test void adminPolicyOverridesEnvironmentIsBoundedAndNonAdminsCannotChangeIt() throws Exception {
        var result=login(false);var session=(MockHttpSession)result.getRequest().getSession();
        mvc.perform(put("/api/security/policy").session(session).with(csrf()).contentType("application/json").content(json(new AccountStore.Policy(false,true,30,12,8,365)))).andExpect(status().isOk());
        assertThat(accounts.policy().rememberMeDays()).isEqualTo(365);
        for(int days:new int[]{0,366,-1}) mvc.perform(put("/api/security/policy").session(session).with(csrf()).contentType("application/json").content(json(new AccountStore.Policy(false,true,30,12,8,days)))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/security/policy").with(csrf()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"))).contentType("application/json").content(json(new AccountStore.Policy(false,true,30,12,8,30))))
            .andExpect(status().isForbidden());
    }
    @Test void malformedCookiesDoNotAuthenticateAndRememberedMutationsStillRequireCsrf() throws Exception {
        mvc.perform(get("/api/auth/me").cookie(new Cookie(RememberedAccess.COOKIE,"malformed"))).andExpect(status().isUnauthorized());
        var result=login(true);var cookie=cookie(result);
        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isEqualTo(1);
    }
    @Test void temporaryPasswordsNeverCreatePersistentCredentials() throws Exception {
        var temp=accounts.recover("owner",true);
        var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json(Map.of("username","owner","password",temp.password(),"rememberMe",true)))).andExpect(status().isOk()).andReturn();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isZero();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(s->s.startsWith(RememberedAccess.COOKIE+"="));
    }
}
