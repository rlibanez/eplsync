package com.rlibanez.eplsync.settings;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramClientTests {
    private TelegramClient.Result response(int status,String body) throws Exception {return response(status,body,"hello");}
    private TelegramClient.Result response(int status,String body,String message) throws Exception {
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenAnswer(call->{
                HttpRequest request=call.getArgument(0);
                assertThat(request.uri().getScheme()).isEqualTo("https");assertThat(request.uri().getHost()).isEqualTo("api.telegram.org");
                assertThat(request.uri().getPath()).endsWith("/sendMessage");assertThat(request.method()).isEqualTo("POST");
                assertThat(request.timeout().orElseThrow()).isEqualTo(java.time.Duration.ofSeconds(10));
                var subscriber=HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
                request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {subscriber.onSubscribe(subscription);}
                    public void onNext(java.nio.ByteBuffer buffer) {subscriber.onNext(java.util.List.of(buffer));}
                    public void onError(Throwable error) {subscriber.onError(error);}
                    public void onComplete() {subscriber.onComplete();}
                });
                var payload=tools.jackson.databind.json.JsonMapper.builder().build().readTree(subscriber.getBody().toCompletableFuture().join());
                assertThat(payload.path("text").asString()).isEqualTo(message);
                assertThat(payload.has("parse_mode")).isFalse();
                if(message.startsWith("⚠️ ")) {
                    var entity=payload.path("entities").get(0);
                    assertThat(entity.path("type").asString()).isEqualTo("bold");
                    assertThat(entity.path("offset").asInt()).isEqualTo(3);
                    assertThat(entity.path("length").asInt()).isEqualTo(message.indexOf('\n')-3);
                } else assertThat(payload.has("entities")).isFalse();
                return CompletableFuture.completedFuture(response);
            });
        return new TelegramClient(http).send("123456789:abcdefghijklmnopqrstuvwxyz","12345",message);
    }
    @Test void boldTaskNameUsesEntitiesWithoutInterpretingUserMarkup() throws Exception {
        assertThat(response(200,"{\"ok\":true}","⚠️ Libros <nuevos> & 📚 _lecturas_\nResultado: Requiere atención").success()).isTrue();
    }
    @Test void validatesTelegramSuccessAndKnownRetryableErrors() throws Exception {
        assertThat(response(200,"{\"ok\":true}").success()).isTrue();
        assertThat(response(200,"{\"ok\":false}").success()).isFalse();
        assertThat(response(400,"{\"ok\":false}").retryable()).isFalse();
        var limited=response(429,"{\"ok\":false,\"parameters\":{\"retry_after\":42}}");
        assertThat(limited.retryable()).isTrue();assertThat(limited.retryAfter()).isEqualTo(42);
        assertThat(response(503,"{\"ok\":false}").retryable()).isTrue();
        assertThat(response(302,"{}").success()).isFalse();
        assertThat(response(200,"malformed").success()).isFalse();
    }
    @Test void privateChatMustNotBeTheBotItself() {
        var http=mock(HttpClient.class);
        var result=new TelegramClient(http).send("123456789:abcdefghijklmnopqrstuvwxyz","123456789","hello");
        assertThat(result.failure()).isEqualTo(TelegramClient.Failure.BOT_AS_RECIPIENT);
        assertThat(result.safeMessage()).contains("message.chat.id").doesNotContain("abcdefghijklmnopqrstuvwxyz");
        verifyNoInteractions(http);
    }
    @Test void providerErrorsHaveSpecificDiagnosticsWithoutEchoingDescriptions() throws Exception {
        assertThat(response(401,"{\"ok\":false,\"error_code\":401}").failure()).isEqualTo(TelegramClient.Failure.INVALID_TOKEN);
        var missing=response(400,"{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found secret\"}");
        assertThat(missing.failure()).isEqualTo(TelegramClient.Failure.CHAT_NOT_FOUND);
        assertThat(missing.safeMessage()).doesNotContain("secret");
        assertThat(response(403,"{\"ok\":false,\"description\":\"Forbidden: bots can't send messages to bots\"}").failure()).isEqualTo(TelegramClient.Failure.BOT_AS_RECIPIENT);
        assertThat(response(403,"{\"ok\":false}").failure()).isEqualTo(TelegramClient.Failure.BOT_BLOCKED);
        assertThat(response(200,"malformed").failure()).isEqualTo(TelegramClient.Failure.INVALID_RESPONSE);
    }
    @Test void connectionDiagnosticsDistinguishTlsDnsAndTimeout() {
        for(var failure:java.util.Map.<Exception,TelegramClient.Failure>of(
                new javax.net.ssl.SSLHandshakeException("secret"),TelegramClient.Failure.TLS,
                new java.net.UnknownHostException("secret"),TelegramClient.Failure.DNS,
                new java.net.http.HttpTimeoutException("secret"),TelegramClient.Failure.TIMEOUT).entrySet()) {
            var http=mock(HttpClient.class);
            when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(failure.getKey()));
            var result=new TelegramClient(http).send("123456789:abcdefghijklmnopqrstuvwxyz","12345","hello");
            assertThat(result.failure()).isEqualTo(failure.getValue());
            assertThat(result.safeMessage()).doesNotContain("secret");assertThat(result.retryable()).isFalse();
        }
    }
    @Test void transportErrorsHaveNoAutomaticResendAndNoProviderMessage() {
        var http=mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("secret token")));
        var result=new TelegramClient(http).send("123456789:abcdefghijklmnopqrstuvwxyz","12345","hello");
        assertThat(result.success()).isFalse();assertThat(result.retryable()).isFalse();assertThat(result.toString()).doesNotContain("secret");
    }
}
