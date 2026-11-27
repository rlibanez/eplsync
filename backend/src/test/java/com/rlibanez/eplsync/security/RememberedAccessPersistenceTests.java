package com.rlibanez.eplsync.security;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RememberedAccessPersistenceTests {
    @TempDir Path root;
    @AfterEach void clear() {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void hashedCredentialAndAdminPolicySurviveRecreatingAllServicesOnTheSameDatabaseFile() {
        String url="jdbc:sqlite:"+root.resolve("persistent.db");
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(url));
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        var environment=new MockEnvironment().withProperty("eplsync.security.remember-me-days","21");
        var encoder=new SecurityConfiguration().passwordEncoder();
        var journal=mock(com.rlibanez.eplsync.events.EventJournal.class);
        var jdbc=new JdbcTemplate(source);var accounts=new AccountStore(jdbc,encoder,environment,journal);
        var account=accounts.initialize("owner","","permanent password","permanent password");
        assertThat(accounts.policy().rememberMeDays()).isEqualTo(21);
        accounts.policy(new AccountStore.Policy(false,true,30,12,8,60));
        var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        var access=new RememberedAccess(jdbc,accounts,environment);var sessions=new SessionAccess(accounts,access);
        sessions.login(request,response,account,true);
        String header=response.getHeaders("Set-Cookie").stream().filter(v->v.startsWith(RememberedAccess.COOKIE+"=")).findFirst().orElseThrow();
        String secret=header.substring(header.indexOf('=')+1,header.indexOf(';'));
        long expiry=jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class);
        request.getSession().invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();
        // A new datasource and new service instances have no in-memory session or token cache.
        var restartedJdbc=new JdbcTemplate(new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(url)));
        var restartedEnvironment=new MockEnvironment().withProperty("eplsync.security.remember-me-days","5");
        var restartedAccounts=new AccountStore(restartedJdbc,encoder,restartedEnvironment,journal);
        assertThat(restartedAccounts.policy().rememberMeDays()).isEqualTo(60);
        var restartedAccess=new RememberedAccess(restartedJdbc,restartedAccounts,restartedEnvironment);
        var restartedSessions=new SessionAccess(restartedAccounts,restartedAccess);
        var next=new MockHttpServletRequest();next.setCookies(new jakarta.servlet.http.Cookie(RememberedAccess.COOKIE,secret));
        assertThat(restartedSessions.restore(next,new MockHttpServletResponse(),null)).isTrue();
        assertThat(restartedSessions.current().id()).isEqualTo(account.id());
        assertThat(restartedJdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class)).isEqualTo(expiry);
        assertThat(expiry-restartedJdbc.queryForObject("SELECT issued_at FROM remembered_access",Long.class)).isEqualTo(Duration.ofDays(60).toMillis());
        restartedSessions.logout(next);
        assertThat(restartedJdbc.queryForObject("SELECT count(*) FROM remembered_access",Integer.class)).isZero();
        assertThat(restartedAccess.authenticate(next,new MockHttpServletResponse())).isNull();
    }
    @Test void environmentIsUsedUntilAdminSavesAndInvalidDurationsAreRejected() {
        var jdbc=mock(JdbcTemplate.class);var journal=mock(com.rlibanez.eplsync.events.EventJournal.class);
        var encoder=mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        for(int days:new int[]{0,366,-5}) assertThatThrownBy(()->new AccountStore(jdbc,encoder,new MockEnvironment().withProperty("eplsync.security.remember-me-days",Integer.toString(days)),journal)).hasMessageContaining("1 y 365");
    }
    @Test void reducingTheEnvironmentDurationOnRestartPermanentlyClampsOldGrants() {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("environment.db")));
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        var jdbc=new JdbcTemplate(source);var encoder=new SecurityConfiguration().passwordEncoder();var journal=mock(com.rlibanez.eplsync.events.EventJournal.class);
        var environment=new MockEnvironment().withProperty("eplsync.security.remember-me-days","21");
        var accounts=new AccountStore(jdbc,encoder,environment,journal);
        var account=accounts.initialize("owner","","permanent password","permanent password");
        new RememberedAccess(jdbc,accounts,environment).issue(new MockHttpServletRequest(),new MockHttpServletResponse(),account);
        long issued=jdbc.queryForObject("SELECT issued_at FROM remembered_access",Long.class);
        new AccountStore(jdbc,encoder,new MockEnvironment().withProperty("eplsync.security.remember-me-days","5"),journal);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class)).isEqualTo(issued+Duration.ofDays(5).toMillis());
        new AccountStore(jdbc,encoder,new MockEnvironment().withProperty("eplsync.security.remember-me-days","365"),journal);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM remembered_access",Long.class)).isEqualTo(issued+Duration.ofDays(5).toMillis());
    }
}
