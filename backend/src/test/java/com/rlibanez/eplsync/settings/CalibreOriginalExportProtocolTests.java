package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CalibreOriginalExportProtocolTests {
    @TempDir Path root;
    @Test void deniedOrUnsupportedExportNeverFallsBackToTransformedDownload() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var status=new AtomicInteger(403);
        server.createContext("/",r->{calls.incrementAndGet();assertThat(r.getRequestMethod()).isEqualTo("POST");assertThat(r.getRequestURI().getPath()).isEqualTo("/cdb/cmd/export/0");assertThat(r.getRequestURI().getQuery()).isEqualTo("library_id=main");assertThat(new String(r.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("[\"fmt\",9,\"EPUB\",null]");r.sendResponseHeaders(status.get(),-1);r.close();});server.start();
        try {
            var client=new CalibreClient();var c=new CalibreSettings.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"","","main");
            assertThatThrownBy(()->client.downloadOriginalEpub(c,9,root.resolve("forbidden.epub"),1000)).hasMessageContaining("restricciones por biblioteca");
            status.set(404);
            assertThatThrownBy(()->client.downloadOriginalEpub(c,9,root.resolve("unsupported.epub"),1000)).hasMessageContaining("exportación original compatible");
            assertThat(calls.get()).isEqualTo(2);
        } finally {server.stop(0);}
    }
    @Test void cancellationInterruptsAnExportThatIsWaitingForItsBody() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new AtomicBoolean();
        var handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);
        server.createContext("/",r->{
            r.getRequestBody().readAllBytes();r.sendResponseHeaders(200,1037);
            try(var output=new java.io.DataOutputStream(r.getResponseBody())) {
                output.writeByte(0x81);output.writeByte(0xa6);output.writeBytes("result");output.writeByte(0xc6);output.writeInt(1024);output.flush();started.countDown();
                try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex) {Thread.currentThread().interrupt();}
            } catch(java.io.IOException ignored) { /* The client deliberately cancels the exchange. */ }
            finally {r.close();}
        });server.start();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var client=new CalibreClient();var c=new CalibreSettings.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"","","main");
            var task=executor.submit(()->client.withCancellation(cancelled::get,()->{client.downloadOriginalEpub(c,9,root.resolve("cancelled.epub"),2000);return null;}));
            try {
                assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();cancelled.set(true);
                assertThatThrownBy(()->task.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CalibreClient.PermanentFailure.class);
            } finally {release.countDown();executor.shutdownNow();}
        } finally {server.stop(0);handlers.shutdownNow();}
    }
}
