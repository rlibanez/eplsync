package com.rlibanez.eplsync.events;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.rlibanez.eplsync.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class EventStreamQuotaTests {
    EventJournal journal=mock(EventJournal.class);
    SessionAccess access=mock(SessionAccess.class);
    EventStream stream=new EventStream(journal);
    List<Runnable> signals=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(stream,"access",access);
        when(access.valid(any())).thenReturn(true);
        when(journal.after(anyLong(),anyInt())).thenReturn(List.of());
        when(journal.listen(any())).thenAnswer(call->{Runnable listener=call.getArgument(0);signals.add(listener);return (AutoCloseable)()->signals.remove(listener);});
    }
    @AfterEach void cleanup() {
        stream.close();SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(slots()).isEqualTo(32));
    }
    MockHttpSession account(String id) {
        var account=new Account(id,id,"","USER","ACTIVE",false,null,0,Set.of(Permission.EVENTS_MANAGE),java.time.Instant.now());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account,null,List.of()));
        var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",SecurityContextHolder.getContext());
        var request=new MockHttpServletRequest();request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));return session;
    }
    int slots() {return ((Semaphore)org.springframework.test.util.ReflectionTestUtils.getField(stream,"slots")).availablePermits();}
    void wake() {signals.forEach((Runnable signal) -> Objects.requireNonNull(signal).run());}
    void awaitSlots(int expected) {await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(slots()).isEqualTo(expected));}
    void signal(SseEmitter emitter,String callback) {
        var invocation=mockingDetails(emitter).getInvocations().stream().filter(i->i.getMethod().getName().equals(callback)).findFirst().orElseThrow();
        if(callback.equals("onError")) {
            @SuppressWarnings("unchecked") var action=(java.util.function.Consumer<Throwable>)invocation.getArgument(0);
            action.accept(new java.io.IOException("Disconnected"));
        } else ((Runnable)invocation.getArgument(0)).run();
    }
    @Test void taskStartsAreDeliveredOnlyToTheirOwnerWithoutEventCursorOrReplay() throws Exception {
        var subscribers=new CopyOnWriteArrayList<java.util.function.Consumer<EventJournal.Entry>>();
        when(journal.listenNotices(any())).thenAnswer(call->{
            java.util.function.Consumer<EventJournal.Entry> subscriber=call.getArgument(0);subscribers.add(subscriber);
            return (AutoCloseable)()->subscribers.remove(subscriber);
        });
        try(var construction=mockConstruction(SseEmitter.class)) {
            account("one");var first=stream.connect(null);
            account("two");var second=stream.connect(null);
            var event=new EventJournal.Entry(0,java.time.Instant.now(),EventJournal.Category.JOB,"SCHEDULED_TASK",EventJournal.Outcome.STARTED,EventContext.Origin.SCHEDULED,"run",Map.of("taskName","Nocturna"),new EventContext.Actor("one","one","USER"));
            subscribers.forEach(subscriber->subscriber.accept(event));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->verify(first).send(argThat((SseEmitter.SseEventBuilder builder)->builder.build().stream().anyMatch(data->data.getData().toString().contains("event:operation-notice")))));
            verify(second,never()).send(argThat((SseEmitter.SseEventBuilder builder)->builder.build().stream().anyMatch(data->data.getData().toString().contains("event:operation-notice"))));
            signal(first,"onCompletion");signal(second,"onCompletion");awaitSlots(32);
            assertThat(subscribers).isEmpty();
        }
    }
    @Test void quotaIsSharedAcrossSessionsButOtherAccountsCanConnect() {
        account("one");for(int i=0;i<4;i++) {account("one");stream.connect(null);}
        assertThatThrownBy(()->stream.connect(null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        account("two");stream.connect(null);assertThat(slots()).isEqualTo(27);
    }
    @Test void globalLimitStillAllowsOnly32AndReleasesBothCountersOnDisconnect() {
        try(var construction=mockConstruction(SseEmitter.class)) {
            for(int user=0;user<8;user++) {account("user"+user);for(int i=0;i<4;i++) stream.connect(null);}
            account("ninth");assertThatThrownBy(()->stream.connect(null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(slots()).isZero();
            var first=construction.constructed().getFirst();signal(first,"onCompletion");awaitSlots(1);
            account("user0");stream.connect(null);assertThat(slots()).isZero();
            signal(first,"onCompletion");signal(first,"onError");assertThat(slots()).isZero();
        }
    }
    @Test void completionTimeoutErrorsAndSessionInvalidationRestoreTheUserQuota() {
        try(var construction=mockConstruction(SseEmitter.class)) {
            MockHttpSession session=null;
            for(int i=0;i<4;i++) {session=account("one");stream.connect(null);}
            signal(construction.constructed().get(0),"onCompletion");awaitSlots(29);
            signal(construction.constructed().get(1),"onTimeout");awaitSlots(30);
            signal(construction.constructed().get(2),"onError");awaitSlots(31);
            Objects.requireNonNull(session).invalidate();wake();awaitSlots(32);
            account("one");for(int i=0;i<4;i++) stream.connect(null);assertThat(slots()).isEqualTo(28);
        }
    }
    @Test void failedSetupAndExecutorShutdownDoNotLeakReservations() {
        account("one");when(journal.listen(any())).thenThrow(new IllegalStateException("setup failed"));
        for(int i=0;i<8;i++) assertThatThrownBy(()->stream.connect(null)).hasMessage("setup failed");
        assertThat(slots()).isEqualTo(32);
        reset(journal);when(journal.listen(any())).thenReturn(()->{});
        stream.close();
        for(int i=0;i<8;i++) assertThatThrownBy(()->stream.connect(null)).isInstanceOf(ResponseStatusException.class);
        assertThat(slots()).isEqualTo(32);
    }
    @Test void concurrentRequestsCannotExceedTheAccountQuota() throws Exception {
        try(var workers=Executors.newFixedThreadPool(12)) {
            var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<48;i++) results.add(workers.submit(()-> {
                account("one");
                try {stream.connect(null);return true;}
                catch(ResponseStatusException ex) {assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);return false;}
                finally {SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
            }));
            int accepted=0;for(var result:results) if(result.get(5,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(4);assertThat(slots()).isEqualTo(28);
        }
    }
}
