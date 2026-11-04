package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TorrentPreparationProgressTests {
    SessionAccess sessions=mock(SessionAccess.class);
    Account owner=mock(Account.class);
    TorrentPreparationProgress progress=new TorrentPreparationProgress(sessions);
    void login() {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner",null,List.of(new SimpleGrantedAuthority("TORRENT_SEND"))));}
    @BeforeEach void setup() {when(owner.id()).thenReturn("owner");when(sessions.current()).thenReturn(owner);login();}
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    MockHttpServletRequest request(String id) {var request=new MockHttpServletRequest("POST","/api/torrent/books");request.addHeader(TorrentPreparationProgress.HEADER,id);return request;}
    @Test void successfulResponseCanBeRecoveredWithoutRepeatingCreation() {
        var calls=new AtomicInteger();var input=Map.of("all",true);
        var result=progress.track(request("one-request"),input,()->{calls.incrementAndGet();TorrentPreparationProgress.report("PREPARING",65100,65283,65099,"job");return ResponseEntity.accepted().body(Map.of("jobId","job"));});
        assertThat(progress.status("one-request").state()).isEqualTo("COMPLETED");
        assertThat(progress.status("one-request").processed()).isEqualTo(65100);
        assertThat(progress.status("one-request").result()).isEqualTo(result.getBody());
        assertThat(progress.track(request("one-request"),input,()->{calls.incrementAndGet();return result;})).isSameAs(result);
        assertThat(calls).hasValue(1);assertThat(TorrentPreparationProgress.tracked()).isFalse();
    }
    @Test void pollingAndDuplicateDetectionDoNotWaitForTheCreationLock() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(()->{login();try{return progress.track(request("busy-request"),Map.of(),()->{
                TorrentPreparationProgress.report("PREPARING",100,65283,110,"job");entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("timeout");}catch(InterruptedException ex){throw new IllegalStateException(ex);}
                return "done";
            });}finally{SecurityContextHolder.clearContext();}});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(progress.status("busy-request").processed()).isEqualTo(100);
                assertThat(progress.status("busy-request").items()).isEqualTo(110);
                assertThatThrownBy(()->progress.track(request("busy-request"),Map.of(),()->"duplicate")).hasMessageContaining("ya está en curso");
                assertThatThrownBy(()->progress.track(request("busy-request"),Map.of("different",true),()->"duplicate")).hasMessageContaining("otra selección");
            } finally {release.countDown();}
            assertThat(running.get(5,TimeUnit.SECONDS)).isEqualTo("done");
        }
    }
    @Test void ownershipPermissionsAndSafeFailureDetailsArePreserved() {
        var failure=new IllegalStateException("password=do-not-expose");
        assertThatThrownBy(()->progress.track(request("failed-request"),Map.of(),()->{throw failure;})).isSameAs(failure);
        assertThat(progress.status("failed-request").state()).isEqualTo("FAILED");
        assertThat(progress.status("failed-request").message()).doesNotContain("do-not-expose");
        assertThatThrownBy(()->progress.track(request("failed-request"),Map.of(),()->"repeat")).isSameAs(failure);
        when(owner.id()).thenReturn("other");assertThatThrownBy(()->progress.status("failed-request")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        SecurityContextHolder.clearContext();assertThatThrownBy(()->progress.status("failed-request")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void cancellationIsOwnerScopedAndStopsBeforeActivation() {
        assertThatThrownBy(()->progress.track(request("cancel-request"),Map.of(),()->{
            TorrentPreparationProgress.allowCancellation();
            TorrentPreparationProgress.report("SNAPSHOT",100,1000,0,null);
            when(owner.id()).thenReturn("other");
            assertThatThrownBy(()->progress.cancel("cancel-request")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            when(owner.id()).thenReturn("owner");progress.cancel("cancel-request");
            TorrentPreparationProgress.report("FINALIZING",1000,1000,1000,"job");return "never";
        })).isInstanceOf(TorrentPreparationProgress.Cancelled.class);
        assertThat(progress.status("cancel-request").state()).isEqualTo("CANCELLED");
        progress.track(request("final-request"),Map.of(),()->{
            TorrentPreparationProgress.allowCancellation();TorrentPreparationProgress.report("FINALIZING",1,1,1,"job");
            assertThatThrownBy(()->progress.cancel("final-request")).hasMessageContaining("finalizando");return "done";
        });
    }

    @Test void oldClientsNeedNoTrackingAndInvalidIdsNeverStartWork() {
        var calls=new AtomicInteger();assertThat(progress.track(new MockHttpServletRequest(),Map.of(),calls::incrementAndGet)).isEqualTo(1);
        assertThatThrownBy(()->progress.track(request("invalid/id"),Map.of(),calls::incrementAndGet)).hasMessageContaining("inválido");
        assertThat(calls).hasValue(1);assertThat(TorrentPreparationProgress.tracked()).isFalse();
    }
}
