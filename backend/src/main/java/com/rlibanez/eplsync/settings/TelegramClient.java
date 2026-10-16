package com.rlibanez.eplsync.settings;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Only outbound HTTPS to Telegram. Neither tokens nor provider responses are logged. */
@Service
public class TelegramClient {
    private final HttpClient http;
    private final JsonMapper json=JsonMapper.builder().build();
    public TelegramClient() {this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());}
    TelegramClient(HttpClient http) {this.http=http;}
    public enum Failure { NONE,INVALID_TOKEN,BOT_AS_RECIPIENT,CHAT_NOT_FOUND,BOT_BLOCKED,CHAT_MOVED,RATE_LIMIT,PROVIDER,TIMEOUT,DNS,TLS,CONNECTION,INVALID_RESPONSE,INTERRUPTED,UNKNOWN }
    public record Result(boolean success,boolean retryable,int retryAfter,Failure failure,int httpStatus,int errorCode) {
        public Result(boolean success,boolean retryable,int retryAfter) {this(success,retryable,retryAfter,success?Failure.NONE:Failure.UNKNOWN,0,0);}
        public String safeMessage() {
            return switch(failure) {
                case INVALID_TOKEN -> "Telegram ha rechazado el token del bot; comprueba que corresponde al bot con el que has iniciado la conversación";
                case BOT_AS_RECIPIENT -> "El ID configurado es el del bot. Introduce el ID de tu conversación privada (message.chat.id), no el identificador del bot que devuelve getMe";
                case CHAT_NOT_FOUND -> "Telegram no encuentra ese chat; comprueba el ID numérico y envía /start al mismo bot al que pertenece el token";
                case BOT_BLOCKED -> "Telegram no permite al bot enviar mensajes a ese chat; comprueba que no lo has bloqueado y que tiene acceso";
                case CHAT_MOVED -> "El chat de Telegram ha cambiado de identificador; actualiza el ID del chat";
                case RATE_LIMIT -> "Telegram ha limitado temporalmente los envíos; espera "+retryAfter+" segundos antes de volver a probar";
                case PROVIDER -> "Telegram ha rechazado la solicitud (HTTP "+httpStatus+", código "+errorCode+"); revisa el acceso del bot al chat";
                case TIMEOUT -> "Se agotó el tiempo de espera al conectar con Telegram; revisa la salida a Internet del servidor o contenedor";
                case DNS -> "No se puede resolver api.telegram.org; revisa el DNS del servidor o contenedor";
                case TLS -> "No se pudo establecer una conexión HTTPS segura con Telegram; revisa los certificados, la hora y cualquier proxy de la conexión";
                case CONNECTION -> "No se pudo completar la conexión con Telegram; revisa la salida HTTPS del servidor o contenedor";
                case INVALID_RESPONSE -> "Telegram devolvió una respuesta no válida; revisa si hay un proxy o filtro en la conexión";
                case INTERRUPTED -> "La prueba de Telegram fue interrumpida; vuelve a intentarlo";
                default -> "No se pudo enviar la prueba de Telegram; revisa el token, el ID del chat y que hayas iniciado el bot";
            };
        }
    }
    private static Failure transportFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException) return Failure.TLS;
            if(cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException) return Failure.DNS;
            if(cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) return Failure.TIMEOUT;
        }
        return Failure.CONNECTION;
    }
    private static Failure rejected(int status,int code,tools.jackson.databind.JsonNode data) {
        if(status==401 || status==404 || code==401 || code==404) return Failure.INVALID_TOKEN;
        if(status==429 || code==429) return Failure.RATE_LIMIT;
        String description=data.path("description").asText("").toLowerCase(java.util.Locale.ROOT);
        if(description.contains("send messages to bots")) return Failure.BOT_AS_RECIPIENT;
        if(status==403 || code==403) return Failure.BOT_BLOCKED;
        if(data.path("parameters").has("migrate_to_chat_id")) return Failure.CHAT_MOVED;
        // Match known provider descriptions, but never expose arbitrary response content.
        if(description.contains("chat not found")) return Failure.CHAT_NOT_FOUND;
        return Failure.PROVIDER;
    }
    private static HttpResponse.BodySubscriber<byte[]> boundedBody() {
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long remaining=65536;
            @Override public CompletionStage<byte[]> getBody() {return delegate.getBody();}
            @Override public void onSubscribe(Flow.Subscription value) {subscription=value;delegate.onSubscribe(value);}
            @Override public void onNext(List<ByteBuffer> buffers) {
                for(var buffer:buffers) remaining-=buffer.remaining();
                if(remaining<0) {subscription.cancel();delegate.onError(new java.io.IOException("Telegram response too large"));return;}
                delegate.onNext(buffers);
            }
            @Override public void onError(Throwable error) {delegate.onError(error);}
            @Override public void onComplete() {delegate.onComplete();}
        };
    }
    public Result send(String token,String chat,String message) {
        int separator=token.indexOf(':');
        if(separator<1) return new Result(false,false,0,Failure.INVALID_TOKEN,0,0);
        if(token.substring(0,separator).equals(chat)) return new Result(false,false,0,Failure.BOT_AS_RECIPIENT,0,0);
        var payload=new LinkedHashMap<String,Object>();
        payload.put("chat_id",chat);payload.put("text",message);payload.put("link_preview_options",Map.of("is_disabled",true));
        // Telegram entity offsets use UTF-16, matching Java String indices. No markup is parsed.
        if(message.startsWith("✅ ") || message.startsWith("⚠️ ") || message.startsWith("❌ ")) {
            int start=message.indexOf(' ')+1,end=message.indexOf('\n');
            if(end>start) payload.put("entities",List.of(Map.of("type","bold","offset",start,"length",end-start)));
        }
        var request=HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot"+token+"/sendMessage"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            future=http.sendAsync(request,info -> boundedBody());
            var response=future.get(10,TimeUnit.SECONDS);
            tools.jackson.databind.JsonNode data;
            try {data=json.readTree(response.body());if(data==null || !data.isObject()) throw new IllegalArgumentException();}
            catch(RuntimeException ex) {return new Result(false,false,0,Failure.INVALID_RESPONSE,response.statusCode(),0);}
            boolean ok=response.statusCode()==200 && data.path("ok").asBoolean(false);
            int wait=Math.max(1,Math.min(3600,data.path("parameters").path("retry_after").asInt(30)));
            int code=data.path("error_code").asInt(0);
            // Ambiguous transport failures are not retried: Telegram has no idempotency key.
            return new Result(ok,response.statusCode()==429 || response.statusCode()>=500,wait,
                ok?Failure.NONE:rejected(response.statusCode(),code,data),response.statusCode(),code);
        } catch(InterruptedException ex) {Thread.currentThread().interrupt();return new Result(false,false,0,Failure.INTERRUPTED,0,0);}
        catch(Exception ex) {return new Result(false,false,0,transportFailure(ex),0,0);}
        finally {if(future!=null && !future.isDone()) future.cancel(true);}
    }
    @jakarta.annotation.PreDestroy void close() {http.shutdownNow();}
}
