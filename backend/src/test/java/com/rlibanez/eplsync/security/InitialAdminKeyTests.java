package com.rlibanez.eplsync.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialAdminKeyTests {
    private final AccountStore accounts=mock(AccountStore.class);
    private final SessionAccess sessions=mock(SessionAccess.class);
    private final LoginThrottle throttle=new LoginThrottle();
    private AuthController controller(String key) { return new AuthController(accounts,sessions,throttle,key); }
    private AuthController.Setup input(String key) {
        return new AuthController.Setup("admin","admin@example.org","password123","password123",key);
    }
    private void setup(AuthController controller,String key) {
        controller.setup(input(key),new MockHttpServletRequest(),new MockHttpServletResponse());
    }
    @Test void rejectsMissingAndIncorrectKeysWithoutCreatingAccounts() {
        var controller=controller("installation-secret");
        for(String supplied : new String[]{null,"","wrong"}) {
            assertThatThrownBy(() -> setup(controller,supplied))
                .isInstanceOfSatisfying(ResponseStatusException.class,ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("Clave de configuración inicial incorrecta");
                });
        }
        verify(accounts,never()).initialize(any(),any(),any(),any());
        verifyNoInteractions(sessions);
    }
    @Test void acceptsExactKeyWithoutARequiredFormat() {
        setup(controller("mi clave personal"),"mi clave personal");
        verify(accounts).initialize("admin","admin@example.org","password123","password123");
        verify(sessions).login(any(),any(),any());
    }
    @Test void blankConfigurationDoesNotRequireAKey() {
        for(String key : new String[]{"","   "}) setup(controller(key),null);
        verify(accounts,times(2)).initialize(any(),any(),any(),any());
    }
    @Test void statusOnlyExposesWhetherKeyIsRequiredBeforeInitialization() {
        when(accounts.policy()).thenReturn(new AccountStore.Policy(false,true,30,12));
        var controller=controller("installation-secret");
        assertThat(controller.status()).containsEntry("initialAdminKeyRequired",true);
        assertThat(controller.status().toString()).doesNotContain("installation-secret");
        when(accounts.initialized()).thenReturn(true);
        assertThat(controller.status()).containsEntry("initialAdminKeyRequired",false);
        assertThatThrownBy(() -> setup(controller,"installation-secret"))
            .isInstanceOfSatisfying(ResponseStatusException.class,ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(accounts,never()).initialize(any(),any(),any(),any());
    }
    @Test void wrongKeysAreSubjectToSetupRateLimit() {
        var controller=controller("installation-secret");
        for(int i=0;i<5;i++) assertThatThrownBy(() -> setup(controller,"wrong"))
            .isInstanceOfSatisfying(ResponseStatusException.class,ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> setup(controller,"installation-secret"))
            .isInstanceOfSatisfying(ResponseStatusException.class,ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
