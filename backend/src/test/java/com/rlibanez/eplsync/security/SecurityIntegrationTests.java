package com.rlibanez.eplsync.security;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.bind.annotation.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Import(SecurityIntegrationTests.Unprotected.class)
class SecurityIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired AccountStore accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginThrottle throttle;
    @Autowired CatalogBookRepository books;
    @Autowired DownloadRepository downloads;
    MockMvc mvc;
    AccountStore.Temporary initial;
    static final String PASSWORD="a long permanent password for tests";
    @RestController static class Unprotected { @GetMapping("/api/unprotected-test") public Map<String,Boolean> missingPolicy() {return Map.of("oops",true);} }
    @BeforeEach void setup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ((java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(throttle,"attempts")).clear();
        accounts.clear();downloads.deleteAll();books.deleteAll();
        initial=initializeTemporary("admin","admin@example.org");
    }
    AccountStore.Temporary initializeTemporary(String username,String email) {
        accounts.initialize(username,email,PASSWORD,PASSWORD);
        return accounts.recover(username,true);
    }
    MockHttpSession login(String name,String password) throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json(Map.of("username",name,"password",password))))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    MockHttpSession admin() throws Exception {
        var session=login("admin",initial.password());
        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType("application/json")
            .content(json(Map.of("currentPassword",initial.password(),"newPassword",PASSWORD)))).andExpect(status().isOk());
        return login("admin",PASSWORD);
    }
    String json(Object value) {return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);}
    @Test void securityEventsArePrivateAndContainNoCredentials() throws Exception {
        var administrator=admin();
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        accounts.register("eventreader","events@example.org",PASSWORD);
        var id=jdbc.queryForObject("SELECT id FROM users WHERE username='eventreader'",String.class);
        accounts.update(id,"USER","ACTIVE",Map.of("EVENTS_MANAGE","ALLOW"),initial.user().id());
        var reader=login("eventreader",PASSWORD);
        mvc.perform(get("/api/events").session(administrator).param("category","SECURITY")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(get("/api/events").session(reader).param("category","SECURITY")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/events/operations").session(reader).param("category","SECURITY")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/events/unread").session(reader).with(csrf()).contentType("application/json").content("{\"afterId\":0,\"readIds\":[]}")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        var details=jdbc.queryForList("SELECT details FROM app_events WHERE category='SECURITY'",String.class);
        org.junit.jupiter.api.Assertions.assertFalse(details.isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(details.stream().noneMatch(value -> value.contains(PASSWORD)||value.contains(initial.password())||value.contains("@")||value.contains("password_hash")));
    }
    @Test void deletionRequiresAdminCsrfAndConfirmationAndRevokesSessions() throws Exception {
        var administrator=admin();
        var created=accounts.create("deletable","delete@example.org","USER",initial.user().id());
        accounts.update(created.user().id(),"USER","ACTIVE",Map.of("TORRENT_SEND","ALLOW"),initial.user().id());
        org.junit.jupiter.api.Assertions.assertTrue(accounts.view(created.user().id()).permissions().contains(Permission.TORRENT_SEND));
        var reader=login("deletable",created.password());
        String path="/api/security/users/"+created.user().id();
        mvc.perform(delete(path).session(reader).with(csrf()).contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isForbidden());
        mvc.perform(delete(path).session(administrator).contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isForbidden());
        mvc.perform(delete(path).session(administrator).with(csrf()).contentType("application/json").content("{\"confirm\":false}")).andExpect(status().isBadRequest());
        mvc.perform(delete(path).session(administrator).with(csrf()).contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertNull(accounts.find(created.user().id()));
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_permission_overrides WHERE user_id=?",Integer.class,created.user().id()));
        mvc.perform(get("/api/auth/me").session(reader)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/security/users/"+initial.user().id()).session(administrator).with(csrf()).contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertNotNull(accounts.find(initial.user().id()));
    }
    @Test void duplicateUsernamesReturnSafeValidationErrorsWithoutCreatingAccountsOrEvents() throws Exception {
        var administrator=admin();
        long eventCount=jdbc.queryForObject("SELECT count(*) FROM app_events WHERE category='SECURITY'", Long.class);
        for (String name:List.of("admin","ADMIN")) {
            mvc.perform(post("/api/security/users").session(administrator).with(csrf()).contentType("application/json")
                .content(json(Map.of("username",name,"email","other@example.org","role","USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").value("El nombre de usuario no está disponible"));
        }
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content(json(Map.of("username","Admin","email","other@example.org","password",PASSWORD))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details").value("El nombre de usuario no está disponible"));
        assertThat(accounts.users()).hasSize(1);
        assertThat(accounts.authenticate("admin",PASSWORD)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE category='SECURITY'", Long.class)).isEqualTo(eventCount);
    }

    @Test void approvingPendingAccountsRequiresAdminAndCsrfAndPreservesPermissions() throws Exception {
        var administrator=admin();
        accounts.policy(new AccountStore.Policy(true,true,30,12));
        accounts.register("pendinguser","pending@example.org",PASSWORD);
        var user=accounts.users().stream().filter(u -> u.username().equals("pendinguser")).findFirst().orElseThrow();
        accounts.update(user.id(),"USER","PENDING",Map.of("TORRENT_SEND","ALLOW"),initial.user().id());
        String path="/api/security/users/"+user.id()+"/approve";
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        accounts.register("ordinary","ordinary@example.org",PASSWORD);
        mvc.perform(post(path).session(login("ordinary",PASSWORD)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path).session(administrator)).andExpect(status().isForbidden());
        assertThat(accounts.authenticate("pendinguser",PASSWORD)).isNull();
        mvc.perform(post(path).session(administrator).with(csrf())).andExpect(status().isOk());
        assertThat(accounts.authenticate("pendinguser",PASSWORD).permissions()).contains(Permission.TORRENT_SEND);
        assertThat(accounts.view(user.id()).approvedBy()).isEqualTo(initial.user().id());
        mvc.perform(post(path).session(administrator).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test void passwordPolicyIsPersistedAndEnforcedForEveryNewCredential() throws Exception {
        var session=admin();
        mvc.perform(put("/api/security/policy").session(session).with(csrf()).contentType("application/json")
            .content(json(new AccountStore.Policy(true,false,30,12,10)))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.passwordMinimumLength").value(10));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> accounts.register("shortuser","short@example.org","123456789"));
        accounts.register("validuser","valid@example.org","1234567890");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> accounts.changePassword(accounts.find(initial.user().id()),PASSWORD,"123456789"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> accounts.policy(new AccountStore.Policy(false,true,30,12,7)));
        accounts.policy(new AccountStore.Policy(false,true,30,12,128));
        var temporary=accounts.create("temporary","temp@example.org","USER",initial.user().id());
        org.junit.jupiter.api.Assertions.assertTrue(temporary.password().length() >= 128);
        org.junit.jupiter.api.Assertions.assertNotNull(accounts.authenticate("temporary",temporary.password()));
        accounts.clear();
        accounts.initialize("newadmin","new@example.org","12345678","12345678");
    }
    @Test void anonymousCannotReadOrMutateAndCsrfIsRequiredEvenForLogin() throws Exception {
        for(String path:List.of("/api/catalog/books","/api/settings/torrent","/api/security/users","/api/events/stream"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json(Map.of("username","admin","password",initial.password())))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andExpect(jsonPath("$.registrationEnabled").value(false));
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void temporarySessionOnlyChangesPasswordAndChangingItInvalidatesOtherSessions() throws Exception {
        var first=login("admin",initial.password());var second=login("admin",initial.password());
        mvc.perform(get("/api/catalog/books").session(first)).andExpect(status().isForbidden());
        mvc.perform(get("/api/security/users").session(first)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password").session(first).with(csrf()).contentType("application/json")
            .content(json(Map.of("currentPassword",initial.password(),"newPassword",PASSWORD)))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());
        assertThat(accounts.authenticate("admin",initial.password())).isNull();
        assertThat(accounts.authenticate("admin",PASSWORD).mustChangePassword()).isFalse();
    }
    @Test void expiredTemporaryCredentialAndItsSessionCannotBeUsed() throws Exception {
        var session=login("admin",initial.password());
        jdbc.update("UPDATE users SET temporary_password_expires_at=? WHERE id=?",Instant.now().minusSeconds(1).toString(),initial.user().id());
        assertThat(accounts.authenticate("admin",initial.password())).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        var replacement=accounts.recover("admin",true);
        assertThat(replacement.expiresAt()).isBetween(Instant.now().plusSeconds(295),Instant.now().plusSeconds(305));
        assertThat(accounts.authenticate("admin",replacement.password())).isNotNull();
    }
    @Test void registrationDefaultsClosedPendingApprovalAndNeverAcceptsAdminRole() throws Exception {
        admin();
        assertThatThrownBy(()->accounts.register("alice","alice@example.org",PASSWORD)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        accounts.policy(new AccountStore.Policy(true,true,30,12));
        accounts.register("alice","alice@example.org",PASSWORD);
        var user=accounts.users().stream().filter(a->a.username().equals("alice")).findFirst().orElseThrow();
        assertThat(user.status()).isEqualTo("PENDING");assertThat(user.role()).isEqualTo("USER");assertThat(user.emailVerifiedAt()).isNull();
        assertThat(accounts.authenticate("alice",PASSWORD)).isNull();
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        assertThat(accounts.view(user.id()).status()).isEqualTo("PENDING");
        accounts.register("bob","alice@example.org",PASSWORD); // Unverified email does not reserve ownership.
        assertThat(accounts.authenticate("bob",PASSWORD).role()).isEqualTo("USER");
        accounts.update(user.id(),"USER","ACTIVE",Map.of(),initial.user().id());
        assertThat(accounts.authenticate("alice",PASSWORD)).isNotNull();
    }
    @Test void effectiveOverridesRevokeSessionsAndAdministrativePrivilegesCannotBeGranted() throws Exception {
        admin();accounts.policy(new AccountStore.Policy(true,false,30,12));accounts.register("alice","alice@example.org",PASSWORD);
        var user=accounts.authenticate("alice",PASSWORD);var session=login("alice",PASSWORD);
        mvc.perform(get("/api/catalog/books").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/settings/torrent").session(session)).andExpect(status().isForbidden());
        accounts.update(user.id(),"USER","ACTIVE",Map.of("CATALOG_READ","DENY","SETTINGS_MANAGE","ALLOW"),initial.user().id());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        session=login("alice",PASSWORD);
        mvc.perform(get("/api/catalog/books").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/torrent").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/security/users").session(session)).andExpect(status().isForbidden());
        assertThatThrownBy(()->accounts.update(user.id(),"USER","ACTIVE",Map.of("ROLE_ADMIN","ALLOW"),initial.user().id())).isInstanceOf(IllegalArgumentException.class);
        accounts.update(user.id(),"USER","DISABLED",Map.of(),initial.user().id());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void historyIsRedactedInCatalogResponsesUntilExplicitlyGranted() throws Exception {
        admin();accounts.policy(new AccountStore.Policy(true,false,30,12));accounts.register("alice","alice@example.org",PASSWORD);
        books.saveAndFlush(CatalogBook.builder().eplId(1L).revision(1.0).author("Author").title("Book").build());
        var row=new DownloadRecord();row.setEplId(1L);row.setRevision(1.0);row.setHash("A".repeat(40));row.setClient("qbittorrent");row.setClientInstanceId("test");row.setStatus(DownloadStatus.SUBMITTED);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());downloads.saveAndFlush(row);
        var session=login("alice",PASSWORD);
        mvc.perform(get("/api/catalog/books/1").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.download.items").isEmpty());
        mvc.perform(get("/api/torrent/downloads").session(session)).andExpect(status().isForbidden());
        var user=accounts.authenticate("alice",PASSWORD);accounts.update(user.id(),"USER","ACTIVE",Map.of("BOOK_HISTORY_READ","ALLOW"),initial.user().id());session=login("alice",PASSWORD);
        mvc.perform(get("/api/catalog/books/1").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.download.items.length()").value(1));
    }
    @Test void lastAdminIsProtectedAndNewEndpointsDefaultToDenied() throws Exception {
        var session=admin();
        assertThatThrownBy(()->accounts.update(initial.user().id(),"USER","ACTIVE",Map.of(),initial.user().id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->accounts.update(initial.user().id(),"ADMIN","DISABLED",Map.of(),initial.user().id())).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(get("/api/unprotected-test").session(session)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_ACCESS_POLICY"));
        mvc.perform(get(java.net.URI.create("/a%70i/unprotected-test")).session(session)).andExpect(status().isForbidden());
        assertThatThrownBy(()->accounts.initialize("other","other@example.org",PASSWORD,PASSWORD)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void passwordHashesNeverAppearInUserResponsesAndEmailChangesClearVerification() throws Exception {
        var session=admin();
        String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,initial.user().id());
        assertThat(hash).startsWith("{argon2id}$argon2id$").doesNotContain(PASSWORD);
        mvc.perform(get("/api/security/users").session(session)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password_hash"))));
        jdbc.update("UPDATE users SET email_verified_at=? WHERE id=?",Instant.now().toString(),initial.user().id());
        mvc.perform(put("/api/auth/email").session(session).with(csrf()).contentType("application/json").content("{\"email\":\"new@example.org\"}")).andExpect(status().isOk());
        assertThat(accounts.view(initial.user().id()).emailVerifiedAt()).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.email").value("new@example.org"));
    }
    @Test void normalResetPreservesAccountsSettingsAndFullResetRequiresConfirmation() throws Exception {
        var session=admin();
        mvc.perform(put("/api/settings/events").session(session).with(csrf()).contentType("application/json").content("{\"events.retention.max-count\":1234}")).andExpect(status().isOk());
        mvc.perform(post("/api/maintenance/reset").session(session).with(csrf()).contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isOk());
        assertThat(accounts.initialized()).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings",Integer.class)).isPositive();
        mvc.perform(post("/api/maintenance/reset").session(session).with(csrf()).contentType("application/json").content("{\"confirm\":true,\"eraseUsersAndSettings\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/maintenance/reset").session(session).with(csrf()).contentType("application/json").content("{\"confirm\":true,\"eraseUsersAndSettings\":true,\"fullResetConfirmation\":\"BORRAR TODO\"}")).andExpect(status().isOk());
        assertThat(accounts.initialized()).isFalse();assertThat(accounts.policy().registrationEnabled()).isFalse();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(accounts.initialize("newadmin","new@example.org",PASSWORD,PASSWORD)).isNotNull();
    }
    @Test void realCsrfTokensWorkAcrossLoginAndMultipartAndLogoutInvalidatesTheSession() throws Exception {
        var token=mvc.perform(get("/api/auth/csrf")).andReturn();
        var tree=tools.jackson.databind.json.JsonMapper.builder().build().readTree(token.getResponse().getContentAsString());
        var login=mvc.perform(post("/api/auth/login").session((MockHttpSession)token.getRequest().getSession(false))
            .header(tree.path("headerName").asString(),tree.path("token").asString()).contentType("application/json")
            .content(json(Map.of("username","admin","password",initial.password())))).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType("application/json").content(json(Map.of("currentPassword",initial.password(),"newPassword",PASSWORD)))).andExpect(status().isOk());
        session=login("admin",PASSWORD);
        token=mvc.perform(get("/api/auth/csrf").session(session)).andReturn();tree=tools.jackson.databind.json.JsonMapper.builder().build().readTree(token.getResponse().getContentAsString());
        var file=new org.springframework.mock.web.MockMultipartFile("file","empty.zip","application/zip",new byte[]{1});
        var options=new org.springframework.mock.web.MockMultipartFile("options","","application/json","{\"dryRun\":true}".getBytes());
        mvc.perform(multipart("/api/catalog/import/run").file(file).file(options).session(session)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/catalog/import/run").file(file).file(options).session(session)
            .header(tree.path("headerName").asString(),tree.path("token").asString())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/books")).andExpect(status().isUnauthorized());
    }
    @Test void sessionAbsoluteLifetimeAndRequiredHttpsAreEnforced() throws Exception {
        var session=admin();
        var security=(org.springframework.security.core.context.SecurityContext) session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var authentication=security.getAuthentication(); var a=(Account)authentication.getPrincipal();
        var old=new Account(a.id(),a.username(),a.email(),a.role(),a.status(),a.mustChangePassword(),a.temporaryExpiresAt(),a.securityVersion(),a.permissions(),Instant.now().minusSeconds(13*3600));
        security.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(old,null,authentication.getAuthorities()));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        var env=(org.springframework.core.env.ConfigurableEnvironment)context.getEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("https-test",Map.of("eplsync.security.require-https",true)));
        try {
            mvc.perform(get("/api/auth/status")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTPS_REQUIRED"));
            mvc.perform(get("/").header("Accept","text/html").header("Accept-Language","es"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Se requiere una conexión segura")));
            mvc.perform(get("/api/auth/status").secure(true)).andExpect(status().isOk());
        } finally { env.getPropertySources().remove("https-test"); }
    }
    @Test void cleanupPermissionsAreCheckedBeforeAnyClientOperationAndPreviewHidesHistory() throws Exception {
        admin(); accounts.policy(new AccountStore.Policy(true,false,30,12)); accounts.register("alice","alice@example.org",PASSWORD);
        var user=accounts.authenticate("alice",PASSWORD);
        accounts.update(user.id(),"USER","ACTIVE",Map.of("TORRENT_SEND","ALLOW"),initial.user().id());
        var session=login("alice",PASSWORD);
        mvc.perform(post("/api/torrent/updates").session(session).with(csrf()).contentType("application/json")
            .content("{\"dryRun\":false,\"previousVersions\":\"removeTorrent\"}")).andExpect(status().isForbidden());
        accounts.update(user.id(),"USER","ACTIVE",Map.of("TORRENT_SEND","ALLOW","TORRENT_CLEANUP","ALLOW"),initial.user().id());
        session=login("alice",PASSWORD);
        mvc.perform(post("/api/torrent/updates").session(session).with(csrf()).contentType("application/json")
            .content("{\"dryRun\":false,\"previousVersions\":\"removeTorrentAndFiles\"}")).andExpect(status().isForbidden());
        var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("user",null,List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var candidate=new com.rlibanez.eplsync.torrent.updates.UpdatePlanner.Candidate(1L,"Book",2.0,List.of(new com.rlibanez.eplsync.torrent.updates.UpdatePlanner.Existing("secret-history",1.0,"A".repeat(40),DownloadStatus.SUBMITTED)),List.of("B".repeat(40)));
            assertThat(com.rlibanez.eplsync.torrent.updates.UpdatePlanner.visible(List.of(candidate)).getFirst().existingDownloads()).isEmpty();
            assertThat(candidate.existingDownloads()).hasSize(1);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void administratorCreatesTemporaryUsersAndPublicRegistrationCannotEscalateRoles() throws Exception {
        var adminSession=admin();
        var result=mvc.perform(post("/api/security/users").session(adminSession).with(csrf()).contentType("application/json")
            .content("{\"username\":\"created-user\",\"email\":\"created@example.org\",\"role\":\"USER\"}")).andExpect(status().isOk()).andReturn();
        var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        var secret=body.path("password").asString();
        assertThat(Instant.parse(body.path("expiresAt").asString())).isBetween(Instant.now().plusSeconds(86390),Instant.now().plusSeconds(86410));
        var userSession=login("created-user",secret);
        mvc.perform(get("/api/catalog/books").session(userSession)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password").session(userSession).with(csrf()).contentType("application/json")
            .content(json(Map.of("currentPassword",secret,"newPassword",PASSWORD)))).andExpect(status().isOk());
        userSession=login("created-user",PASSWORD);
        mvc.perform(get("/api/catalog/books").session(userSession)).andExpect(status().isOk());
        mvc.perform(post("/api/security/users").session(userSession).with(csrf()).contentType("application/json")
            .content("{\"username\":\"intruder\",\"email\":\"intruder@example.org\",\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
        accounts.policy(new AccountStore.Policy(true,true,30,12));
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content(json(Map.of("username","public-user","email","public@example.org","password",PASSWORD,"role","ADMIN"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(accounts.users().stream().filter(u->u.username().equals("public-user")).findFirst().orElseThrow().role()).isEqualTo("USER");
    }

    @Test void webSetupRequiresCsrfMatchingPasswordsAndOnlyWorksOnEmptyInstallations() throws Exception {
        accounts.clear();
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andExpect(jsonPath("$.initialized").value(false));
        String input=json(Map.of("username","webadmin","email","owner@example.org","password",PASSWORD,"passwordConfirmation",PASSWORD));
        mvc.perform(post("/api/auth/setup").contentType("application/json").content(input)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json")
            .content(json(Map.of("username","webadmin","email","owner@example.org","password",PASSWORD,"passwordConfirmation","another valid password"))))
            .andExpect(status().isBadRequest());
        assertThat(accounts.initialized()).isFalse();
        var result=mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json").content(input))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.mustChangePassword").value(false)).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/api/security/users").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json").content(input)).andExpect(status().isConflict());
        assertThat(accounts.users()).hasSize(1);
        jdbc.update("UPDATE users SET status='DISABLED'");
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andExpect(jsonPath("$.initialized").value(true));
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json").content(input)).andExpect(status().isConflict());
        assertThat(accounts.users()).hasSize(1);
    }
    @Test void terminalRecoveryUsesAdminUsernameOrEmailAndRejectsAmbiguousEmails() throws Exception {
        var session=admin();
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        accounts.register("normal-user","admin@example.org",PASSWORD);
        var temporary=accounts.recover("ADMIN@EXAMPLE.ORG",true);
        assertThat(temporary.user().username()).isEqualTo("admin");
        assertThat(temporary.expiresAt()).isBetween(Instant.now().plusSeconds(295),Instant.now().plusSeconds(305));
        assertThat(accounts.authenticate("admin",PASSWORD)).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        accounts.create("second-admin","admin@example.org","ADMIN",initial.user().id());
        assertThatThrownBy(()->accounts.recover("admin@example.org",true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("varios administradores");
        assertThat(accounts.authenticate("admin",temporary.password())).isNotNull();
        assertThat(accounts.recover("admin",true).user().username()).isEqualTo("admin");
        assertThatThrownBy(()->accounts.recover("normal-user",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->accounts.recover("missing@example.org",true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void simultaneousSetupRequestsOnlyCreateOneAdministrator() throws Exception {
        accounts.clear();
        var start=new java.util.concurrent.CountDownLatch(1);
        try (var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts=java.util.stream.IntStream.range(0,2).mapToObj(i -> threads.submit(() -> {
                start.await();
                return mvc.perform(post("/api/auth/setup").with(csrf()).contentType("application/json")
                    .content(json(Map.of("username","owner"+i,"email","owner"+i+"@example.org","password",PASSWORD,"passwordConfirmation",PASSWORD))))
                    .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            var responses=new ArrayList<Integer>();
            for (var attempt:attempts) responses.add(attempt.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).containsExactlyInAnyOrder(200,409);
        }
        assertThat(accounts.users()).hasSize(1);
        assertThat(accounts.users().getFirst().role()).isEqualTo("ADMIN");
    }

}
