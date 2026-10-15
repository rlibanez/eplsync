package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.exception.*;
import com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EpubConcurrencyTests {
    EpubFiles files=mock(EpubFiles.class);
    EpubBatches batches=new EpubBatches(files);
    EpubController controller=new EpubController(files,batches);
    EpubFiles.Planned entry=new EpubFiles.Planned("record",new TorrentFile(0,"book.epub",5,1,"/downloads"));
    EpubFiles.Plan plan=new EpubFiles.Plan("server","/downloads","/local",true,List.of(entry));
    AtomicInteger closed=new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        when(files.prepare(anyList())).thenReturn(plan);
        when(files.download(anyString(),anyInt())).thenAnswer(call->opened());
        when(files.open(entry)).thenAnswer(call->opened());
        when(files.list("record")).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",5)));
    }
    EpubFiles.Opened opened() {
        return new EpubFiles.Opened("book.epub",5,new ByteArrayInputStream("bytes".getBytes()) {
            @Override public void close() throws IOException { closed.incrementAndGet();super.close(); }
        });
    }

    @Test void slowEpubAndZipShareTheLimitWithoutBlockingOtherRequestsOrConsumingRetryLinks() throws Exception {
        assertBoundedAndReusable();
        assertThat(closed.get()).isEqualTo(11);
    }

    @Test void failuresOpeningFilesAndDisconnectedClientsReleaseCapacity() throws Exception {
        when(files.download("missing",0)).thenThrow(new TorrentOperationException(HttpStatus.CONFLICT,"Missing"));
        assertThatThrownBy(()->controller.download("missing",0,new MockHttpServletResponse())).isInstanceOf(TorrentOperationException.class);
        assertThatThrownBy(()->controller.download("record",0,disconnected())).isInstanceOf(IOException.class);
        assertThat(closed.get()).isEqualTo(1);
        assertBoundedAndReusable();
    }

    @Test void batchValidationAndZipFailuresAndExpiredTokensReleaseCapacity() throws Exception {
        var session=new MockHttpSession();
        assertThatThrownBy(()->controller.batch("expired",session,new MockHttpServletResponse())).isInstanceOf(TorrentOperationException.class);
        var ready=batches.prepare(session.getId(),List.of("one"));
        String invalid=token(ready);
        doThrow(new TorrentOperationException(HttpStatus.CONFLICT,"Changed")).when(files).validate(plan);
        assertThatThrownBy(()->controller.batch(invalid,session,new MockHttpServletResponse())).hasMessage("Changed");
        doNothing().when(files).validate(plan);
        ready=batches.prepare(session.getId(),List.of("one"));
        String token=token(ready);
        assertThatThrownBy(()->controller.batch(token,session,disconnected())).isInstanceOf(IOException.class);
        assertBoundedAndReusable();
    }

    private void assertBoundedAndReusable() throws Exception {
        var session=new MockHttpSession();
        var zip=batches.prepare(session.getId(),List.of("one"));
        var retry=batches.prepare(session.getId(),List.of("two"));
        var responses=new java.util.ArrayList<SlowResponse>();
        for(int i=0;i<10;i++) responses.add(new SlowResponse());
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        try(var workers=Executors.newFixedThreadPool(10)) {
            var transfers=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<responses.size();i++) {
                var response=responses.get(i).response;
                boolean archive=i==0;
                transfers.add(workers.submit(()->{
                    if(archive) controller.batch(token(zip),session,response);
                    else controller.download("record",0,response);
                    return null;
                }));
            }
            try {
                for(var response:responses) assertThat(response.entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertTimeoutPreemptively(Duration.ofSeconds(2),()-> {
                    mvc.perform(get("/api/torrent/downloads/extra/epubs/0"))
                            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","5"))
                            .andExpect(header().doesNotExist("Content-Disposition"));
                    mvc.perform(get(retry.url()).session(session)).andExpect(status().isTooManyRequests());
                    mvc.perform(get("/api/torrent/downloads/record/epubs")).andExpect(status().isOk());
                });
                verify(files,never()).download("extra",0);
            } finally { responses.forEach(response->response.release.countDown()); }
            for(var transfer:transfers) transfer.get(5,TimeUnit.SECONDS);
        }
        mvc.perform(get(retry.url()).session(session)).andExpect(status().isOk()).andExpect(content().contentType("application/zip"));
    }

    private static String token(EpubBatches.Ready ready) { return ready.url().substring(ready.url().lastIndexOf('/')+1); }
    private static HttpServletResponse disconnected() throws Exception {
        var response=mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("Disconnected client"));return response;
    }
    private static final class SlowResponse {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final HttpServletResponse response=mock(HttpServletResponse.class);
        SlowResponse() throws Exception {
            when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener listener) {}
                @Override public void write(int value) throws IOException {
                    entered.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS)) throw new IOException("Slow client timed out"); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IOException(ex); }
                }
            });
        }
    }
}
