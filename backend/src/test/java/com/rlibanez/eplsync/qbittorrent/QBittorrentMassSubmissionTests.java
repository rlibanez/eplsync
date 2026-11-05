package com.rlibanez.eplsync.qbittorrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.exception.TorrentConnectionException;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.torrent.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

/** Full HTTP protocol and streamed parsing; never connects to a real installation or downloads data. */
class QBittorrentMassSubmissionTests {
    @Test @Timeout(600)
    void largeLibraryUsesOneInitialIndexBatchedIdentityChecksAndNoDuplicatePostsAfterLostAcknowledgements() throws Exception {
        int count=Integer.getInteger("eplsync.test.mass-torrents",2000);
        var remote=ConcurrentHashMap.<String>newKeySet();
        for(int n=count+1;n<=count*2;n++) remote.add(hash(n));
        var posts=new AtomicInteger();var duplicates=new AtomicInteger();var fullReads=new AtomicInteger();var filteredReads=new AtomicInteger();var completed=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var http=Executors.newVirtualThreadPerTaskExecutor();var senders=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(http);
            server.createContext("/",exchange->{
                try(exchange) {
                    String path=exchange.getRequestURI().getPath();int status=200;String body;
                    if(path.endsWith("/torrents/info")) {
                        String query=exchange.getRequestURI().getRawQuery();Collection<String> found;
                        if(query==null) {fullReads.incrementAndGet();found=remote;}
                        else {filteredReads.incrementAndGet();found=Arrays.stream(URLDecoder.decode(query,StandardCharsets.UTF_8).substring("hashes=".length()).split("\\|")).filter(remote::contains).toList();}
                        // Identity checks must not reject an unrelated, temporarily unavailable progress value.
                        body=found.stream().map(value->"{\"hash\":\""+value+"\",\"state\":\"metaDL\",\"progress\":-1,\"amount_left\":-1}").collect(java.util.stream.Collectors.joining(",","[","]"));
                    } else if(path.endsWith("/torrents/add")) {
                        String request=URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),StandardCharsets.UTF_8);
                        var match=java.util.regex.Pattern.compile("urn:btih:([0-9A-F]{40})").matcher(request);
                        if(!match.find()) throw new AssertionError("Missing magnet");String value=match.group(1);
                        posts.incrementAndGet();if(!remote.add(value)) duplicates.incrementAndGet();
                        if(Integer.parseInt(value,16)%997==0) {status=500;body="Acknowledgement lost";}
                        else body="{\"success_count\":1,\"pending_count\":0,\"failure_count\":0,\"added_torrent_ids\":[\""+value+"\"]}";
                    } else if(path.endsWith("/app/version")) body="v5.3.0";
                    else if(path.endsWith("/app/webapiVersion")) body="2.16.0";
                    else {status=404;body="Unexpected endpoint";}
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                }
            });server.start();
            var properties=new TorrentProperties();properties.setEnabled(true);properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var options=new QBittorrentProperties();options.getAuth().setMode(QBittorrentProperties.AuthMode.API_KEY);options.getAuth().setApiKey("mass-test-only");options.getDownload().setCategory("");options.getDownload().setTags(List.of());
            var context=TorrentSubmissionContext.bulk();var next=new AtomicInteger();long started=System.nanoTime();
            try(var client=new QBittorrentClient(properties,options)) {
                var futures=new ArrayList<Future<?>>();
                for(int worker=0;worker<5;worker++) futures.add(senders.submit(()->{
                    for(int n;(n=next.incrementAndGet())<=count;) {
                        String value=hash(n);var command=new TorrentDownload(value,"magnet:?xt=urn:btih:"+value,true,null,"Test",null,CatalogBook.builder().eplId((long)n).language(Language.INGLES).build());
                        TorrentDownloadResult.Status result;
                        try {result=client.addTorrent(command,context);}
                        catch(TorrentConnectionException lost) {context.recovering(value,false);result=client.addTorrent(command,context);}
                        assertThat(result).isIn(TorrentDownloadResult.Status.ACCEPTED,TorrentDownloadResult.Status.ALREADY_EXISTS);
                        int done=completed.incrementAndGet();if(done%10000==0) System.out.printf("Mass HTTP progress: %d/%d%n",done,count);
                    }
                }));
                for(var future:futures) future.get(590,TimeUnit.SECONDS);
            }
            double seconds=(System.nanoTime()-started)/1_000_000_000.0;
            System.out.printf(Locale.ROOT,"Mass HTTP result: submitted=%d existing=%d fullReads=%d filteredReads=%d duplicatePosts=%d seconds=%.3f rate=%.1f/s%n",completed.get(),count,fullReads.get(),filteredReads.get(),duplicates.get(),seconds,count/seconds);
            assertThat(completed).hasValue(count);assertThat(posts).hasValue(count);assertThat(duplicates).hasValue(0);assertThat(remote).hasSize(count*2);
            assertThat(fullReads).hasValue(1);assertThat(filteredReads.get()).isLessThan(count*3/4);
        } finally {server.stop(0);}
    }
    private static String hash(int n) {return String.format(Locale.ROOT,"%040X",n);}
}
