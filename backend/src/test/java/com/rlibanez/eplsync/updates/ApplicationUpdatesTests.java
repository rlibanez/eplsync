package com.rlibanez.eplsync.updates;

import java.time.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationUpdatesTests {
    private final ApplicationVersion version=mock(ApplicationVersion.class);
    private final ApplicationUpdateSettings settings=mock(ApplicationUpdateSettings.class);
    private final GitHubReleaseClient releases=mock(GitHubReleaseClient.class);
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-10T00:00:00Z"));
    private ApplicationUpdates service() {
        when(version.get()).thenReturn(new ApplicationVersion.Info("0.0.1",null,"https://github.com/rlibanez/eplsync/releases"));
        var clock=mock(Clock.class);when(clock.instant()).thenAnswer(ignored -> now.get());
        return new ApplicationUpdates(version,settings,releases,clock);
    }
    @Test void comparesNumericVersionsAndDistinguishesSnapshots() {
        assertThat(ApplicationUpdates.newer("0.0.1","0.0.2")).isTrue();
        assertThat(ApplicationUpdates.newer("1.9.0","1.10.0")).isTrue();
        assertThat(ApplicationUpdates.newer("2.0.0","1.10.0")).isFalse();
        assertThat(ApplicationUpdates.newer("1.0.0","1.0.0")).isFalse();
        assertThat(ApplicationUpdates.newer("1.0.0-SNAPSHOT","1.0.0")).isTrue();
        assertThat(ApplicationUpdates.newer("unknown","1.0.0")).isNull();
    }
    @Test void versionUsesBuildMetadataAndOmitsUnknownCommit() {
        var properties=new Properties();properties.setProperty("version","0.0.1-SNAPSHOT");properties.setProperty("applicationVersion","1.2.3");
        properties.setProperty("commit","0ef2d7b"+"a".repeat(33));
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("build",new BuildProperties(properties));
        var info=new ApplicationVersion(factory.getBeanProvider(BuildProperties.class)).get();
        assertThat(info.version()).isEqualTo("1.2.3");assertThat(info.commit()).isEqualTo("0ef2d7b");
        var empty=new ApplicationVersion(new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class)).get();
        assertThat(empty.version()).isEqualTo("unknown");assertThat(empty.commit()).isNull();
    }
    @Test void cachedGetDoesNotContactGitHubAndAutomaticCanBeDisabled() throws Exception {
        var service=service();assertThat(service.status().state()).isEqualTo("NOT_CHECKED");service.check(true);
        verifyNoInteractions(releases);
        when(releases.latest()).thenReturn(new GitHubReleaseClient.Release("1.0.0","https://github.com/rlibanez/eplsync/releases/tag/v1.0.0"));
        assertThat(service.check(false).state()).isEqualTo("AVAILABLE");
        service.check(false);verify(releases,times(1)).latest();
    }
    @Test void automaticChecksAreSharedAndCachedFor24Hours() throws Exception {
        var service=service();when(settings.automatic()).thenReturn(true);when(releases.latest()).thenReturn(new GitHubReleaseClient.Release("0.0.1","https://github.com/rlibanez/eplsync/releases/tag/v0.0.1"));
        assertThat(service.check(true).state()).isEqualTo("UP_TO_DATE");
        now.set(now.get().plusSeconds(86399));service.check(true);verify(releases,times(1)).latest();
        now.set(now.get().plusSeconds(1));service.check(true);verify(releases,times(2)).latest();
    }
    @Test void errorsAndNoReleaseHaveControlledCachedResults() throws Exception {
        var service=service();when(releases.latest()).thenThrow(new java.io.IOException("private internals"));
        assertThat(service.check(false).state()).isEqualTo("UNAVAILABLE");service.check(false);verify(releases,times(1)).latest();
        now.set(now.get().plusSeconds(60));doReturn(null).when(releases).latest();
        assertThat(service.check(false).state()).isEqualTo("NO_RELEASE");
    }
    @Test void concurrentChecksDoNotCreateMultipleRemoteRequests() throws Exception {
        var service=service();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(releases.latest()).thenAnswer(ignored -> {entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return null;});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> service.check(false));
            try {assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.check(false).checking()).isTrue();}
            finally {release.countDown();}
            first.get(5,TimeUnit.SECONDS);verify(releases,times(1)).latest();
        }
    }
    @Test void oversizedBodiesCancelReception() {
        var body=new GitHubReleaseClient.LimitedBody();var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);body.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(1024*1024+1)));
        verify(subscription).cancel();assertThatThrownBy(() -> body.getBody().toCompletableFuture().join()).hasCauseInstanceOf(java.io.IOException.class);
    }
}
