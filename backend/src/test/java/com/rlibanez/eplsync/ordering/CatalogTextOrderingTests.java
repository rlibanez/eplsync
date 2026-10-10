package com.rlibanez.eplsync.ordering;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:"})
class CatalogTextOrderingTests {
    @Autowired CatalogBookRepository books;
    @Autowired CatalogBookService catalog;
    @Autowired com.rlibanez.eplsync.torrent.downloads.DownloadRepository downloads;
    @Autowired com.rlibanez.eplsync.torrent.downloads.DownloadQueryService queries;
    @Autowired com.rlibanez.eplsync.torrent.downloads.CatalogDownloadViewService history;
    @Test void appliesLinguisticOrderBeforePagingAndBreaksEquivalentTitlesById() {
        books.deleteAllInBatch();
        for(int i=0;i<TextOrderingTests.INPUT.size();i++) books.save(CatalogBook.builder().eplId((long)i+1).revision(1d).author(TextOrderingTests.INPUT.get(i)).title(TextOrderingTests.INPUT.get(i)).build());
        for(String field:List.of("title","author")) {
            var actual=new ArrayList<String>();
            for(int page=0;page<4;page++) actual.addAll(catalog.search(new CatalogBookFilter(),PageRequest.of(page,4,Sort.by(field))).getContent().stream().map(CatalogBook::getTitle).toList());
            assertThat(actual).isEqualTo(TextOrderingTests.EXPECTED);
            var descending=catalog.search(new CatalogBookFilter(),PageRequest.of(0,20,Sort.by(Sort.Direction.DESC,field))).getContent().stream().map(CatalogBook::getTitle).toList();
            assertThat(descending).isEqualTo(TextOrderingTests.EXPECTED.reversed());
        }
        books.save(CatalogBook.builder().eplId(100L).revision(1d).author("Autor").title("arbol").build());
        assertThat(catalog.search(new CatalogBookFilter(),PageRequest.of(0,4,Sort.by("title"))).getContent()).extracting(CatalogBook::getEplId).startsWith(7L,100L);
        books.deleteAllInBatch();
    }
    @Test void downloadAndHistoryTextOrderingRetainsSecondaryCriteriaBeforePaging() {
        downloads.deleteAllInBatch();books.deleteAllInBatch();
        for(int i=0;i<3;i++) {
            String title=List.of("Zeta","Árbol","¿Quién?").get(i);
            books.save(CatalogBook.builder().eplId((long)i+1).revision(1d).author("Autor").title(title).build());
            var row=new com.rlibanez.eplsync.torrent.downloads.DownloadRecord();
            row.setEplId((long)i+1);row.setRevision(1d);row.setHash("hash-"+i);row.setClient(title);row.setClientInstanceId("test");
            row.setCreatedAt(java.time.Instant.EPOCH);row.setStatus(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.SUBMITTED);
            row.setOrigin(com.rlibanez.eplsync.torrent.downloads.DownloadRecord.Origin.EPLSYNC);downloads.save(row);
        }
        var params=new org.springframework.util.LinkedMultiValueMap<String,String>();
        params.add("size","1");params.add("sort","title,asc");
        assertThat(queries.search(params).items()).extracting(com.rlibanez.eplsync.torrent.downloads.DownloadRecord::getEplId).containsExactly(2L);
        params.set("page","1");
        assertThat(queries.search(params).items()).extracting(com.rlibanez.eplsync.torrent.downloads.DownloadRecord::getEplId).containsExactly(3L);
        for(int i=0;i<2;i++) {
            var row=new com.rlibanez.eplsync.torrent.downloads.DownloadRecord();
            row.setEplId(1L);row.setRevision(2d);row.setHash("new-"+i);row.setClient(i==0?"Árbol":"Zeta");row.setClientInstanceId("test");
            row.setCreatedAt(java.time.Instant.EPOCH);row.setStatus(com.rlibanez.eplsync.torrent.downloads.DownloadStatus.SUBMITTED);
            row.setOrigin(com.rlibanez.eplsync.torrent.downloads.DownloadRecord.Origin.EPLSYNC);downloads.save(row);
        }
        assertThat(history.history(1L,0,1,"revision,desc;client,asc").items()).extracting(com.rlibanez.eplsync.torrent.downloads.CatalogDownloadViewService.HistoryItem::client).containsExactly("Árbol");
        assertThat(history.history(1L,1,1,"revision,desc;client,asc").items()).extracting(com.rlibanez.eplsync.torrent.downloads.CatalogDownloadViewService.HistoryItem::client).containsExactly("Zeta");
        downloads.deleteAllInBatch();books.deleteAllInBatch();
    }

}
