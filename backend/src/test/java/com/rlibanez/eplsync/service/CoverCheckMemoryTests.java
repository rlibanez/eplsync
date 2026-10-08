package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CoverCheckProperties;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.reports.ReportSnapshots;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverCheckMemoryTests {
    record Cover(Long eplId,String coverUrl,Boolean coverAvailable) implements CatalogBookRepository.CoverIdentity {
        public Long getEplId() {return eplId;}
        public String getCoverUrl() {return coverUrl;}
        public Boolean getCoverAvailable() {return coverAvailable;}
    }
    @Test void scansOneHundredThousandCoversWithBoundedPagesAndDiskDeduplication() {
        var repository=mock(CatalogBookRepository.class);
        when(repository.countCovers(0,null,false)).thenReturn(100000L);
        when(repository.findCovers(anyLong(),isNull(),eq(false),any())).thenAnswer(call -> {
            long after=call.getArgument(0);
            int limit=((org.springframework.data.domain.Pageable)call.getArgument(3)).getPageSize();
            var rows=new ArrayList<CatalogBookRepository.CoverIdentity>();
            for(long id=after+1;id<=Math.min(100000,after+limit);id++) rows.add(new Cover(id,"https://example.org/"+(id%10000)+".jpg",null));
            return rows;
        });
        var options=new CoverCheckProperties();
        var calls=new AtomicInteger();
        var probe=new CoverProbe(options) {
            @Override public Result check(String url) {calls.incrementAndGet();return new Result(true,200,"AVAILABLE");}
        };
        try(var snapshots=new ReportSnapshots()) {
            var service=new CoverCheckService(repository,probe,mock(TransactionTemplate.class),options);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"snapshots",snapshots);
            long start=System.nanoTime();
            var report=service.check(true,0,null,null,false);
            assertThat(report.checked()).isEqualTo(100000);
            assertThat(report.items()).hasSize(20);
            assertThat(calls).hasValue(10000);
            var last=service.details(report.detailsId(),999,100,null);
            assertThat(last.meta().totalItems()).isEqualTo(100000);
            assertThat(last.items().getLast().eplId()).isEqualTo(100000);
            verifyNoInteractions(serviceTransactions(service));
            System.out.println("100000 covers scanned; 10000 unique URLs; first page 20; "+java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()+" ms");
        } finally {probe.shutdown();}
    }
    private TransactionTemplate serviceTransactions(CoverCheckService service) {
        return (TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(service,"transactions");
    }
}
