package com.rlibanez.eplsync.updates;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
class GitHubReleaseClient {
    record Release(String version,String url) {}
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    Release latest() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/rlibanez/eplsync/releases/latest"))
            .timeout(Duration.ofSeconds(8)).header("Accept","application/vnd.github+json")
            .header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","EPL-Sync-update-check").GET().build();
        var pending=client.sendAsync(request,ignored -> new LimitedBody());
        try {
            var response=pending.get(8,TimeUnit.SECONDS);
            if(response.statusCode()==404) return null;
            if(response.statusCode()!=200) throw new java.io.IOException("GITHUB_HTTP_"+response.statusCode());
            var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
            String tag=json.path("tag_name").asString("");
            if(json.path("prerelease").asBoolean() || json.path("draft").asBoolean() || !tag.matches("v?\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}"))
                throw new java.io.IOException("INVALID_RELEASE");
            return new Release(tag.replaceFirst("^v",""),"https://github.com/rlibanez/eplsync/releases/tag/"+tag);
        } finally {if(!pending.isDone()) pending.cancel(true);}
    }
    /** Limits received bytes, including chunked responses; the future deadline covers the body. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long count;
        public CompletionStage<byte[]> getBody() {return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value) {subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) count+=buffer.remaining();
            if(count>1024*1024) {subscription.cancel();delegate.onError(new java.io.IOException("RELEASE_TOO_LARGE"));}
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) {delegate.onError(error);}
        public void onComplete() {delegate.onComplete();}
    }
}
