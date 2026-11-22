package com.rlibanez.eplsync.settings;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import com.rlibanez.eplsync.torrent.downloads.DownloadQueryService;
import com.rlibanez.eplsync.events.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.torrent.enabled=false"})
@Transactional
@WithMockUser(authorities={"CATALOG_READ","TORRENT_SYNC","TORRENT_JOBS_MANAGE","CALIBRE_READ","CALIBRE_SEND","CALIBRE_JOBS_MANAGE","EVENTS_MANAGE"})
class UnicodeSearchTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DownloadQueryService downloads;
    @Autowired BulkStore bulk;
    @Autowired CalibreTransfers calibre;
    @Autowired CalibreSettings settings;
    @Autowired WorkListings listings;
    @Autowired EventJournal journal;
    @Autowired EventOperations operations;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @BeforeEach void seed() {
        settings.save(new CalibreSettings.Input("http://calibre.test","","",true,"main"));
        jdbc.update("INSERT INTO catalog_books(epl_id,title,author,revision) VALUES(76536,'ÁRBOL 100%_','Fríida Ísberg',1),(76537,'Otro','Otro',1)");
        for(int i=0;i<2;i++) {
            jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,1,?,'qbittorrent','unicode-client','DOWNLOADED','EPLSYNC',0)","unicode-"+i,76536+i,"hash-"+i);
            jdbc.update("INSERT INTO torrent_bulk_jobs(id,state,type,batch_size,concurrency,interval_millis,selected_books,created_at,event_actor_username,name) VALUES(?,'COMPLETED','DOWNLOAD',100,1,0,1,0,?,?)","unicode-"+i,i==0?"ÍÑIGO":"Otro",i==0?"ÁRBOL":"Otro");
            jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,epl_id,hash,revision,state,attempts,message) VALUES(?,'unicode-0',?,?,?,1,'ACCEPTED',0,?)","unicode-item-"+i,i,76536+i,"hash-"+i,i==0?"ÉXITO 100%_":"Otro");
            jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state,name) VALUES(?,'2026-11-22T10:00:00Z','2026-11-22T10:00:00Z',?,'target','main','source','COMPLETED',?)","unicode-"+i,i==0?"ÍÑIGO":"Otro",i==0?"ÁRBOL":"Otro");
            jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message) VALUES(?,'unicode-0',?,?,0,?,10,?,'1','ACCEPTED',?)","unicode-item-"+i,i,"unicode-"+i,i==0?"ÁRBOL 100%_.epub":"Otro.epub",76536+i,i==0?"ÉXITO 100%_":"Otro");
            jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?,'unicode',1,?,?,1,?,?)","unicode-"+i,i+1,76536+i,i==0?"ÁRBOL 100%_":"Otro",i==0?"Fríida Ísberg":"Otro");
        }
    }
    private LinkedMultiValueMap<String,String> filters(String... pairs) {
        var filters=new LinkedMultiValueMap<String,String>();for(int i=0;i<pairs.length;i+=2) filters.add(pairs[i],pairs[i+1]);return filters;
    }
    @Test void downloadFiltersAndSummaryMatchUnicodeAndNegation() {
        for(String name:List.of("Fríida Ísberg","fríida ísberg","FRÍIDA ÍSBERG",java.text.Normalizer.normalize("Fríida Ísberg",java.text.Normalizer.Form.NFD))) {
            var filter=filters("author",name,"title","árbol 100%_");
            assertThat(downloads.search(filter).items()).hasSize(1);assertThat(downloads.summary(filter).total()).isEqualTo(1);
        }
        assertThat(downloads.search(filters("author","FRÍIDA ÍSBERG","authorOperator","ne")).items()).singleElement().satisfies(row->assertThat(row.getEplId()).isEqualTo(76537));
    }
    @Test void downloadJobItemsFilterAuthorsTitlesAndDetails() {
        var filter=filters("author","FRÍIDA ÍSBERG","book","árbol 100%_","detail","éxito 100%_");
        assertThat(bulk.details("unicode-0",0,1,null,"position,asc","eq",filter).items()).hasSize(1);
        assertThat(bulk.details("unicode-0",0,1,null,"position,asc","eq",filters("author","fríida ísberg","authorOperator","ne")).items()).singleElement().satisfies(row->assertThat(row.eplId()).isEqualTo(76537));
    }
    @Test void calibreJobItemsFilterUnicodeThroughSharedSqlAndDetailSearch() {
        var filter=filters("author","FRÍIDA ÍSBERG","file","árbol 100%_");
        assertThat(calibre.items("unicode-0",0,1,"","éxito",List.of("position,asc"),"eq",filter).items()).hasSize(1);
        assertThat(calibre.items("unicode-0",0,1,"","",List.of("position,asc"),"eq",filters("author","fríida ísberg","authorOperator","ne")).items()).singleElement().satisfies(row->assertThat(row.eplId()).isEqualTo(76537));
    }
    @Test void jobNamesAndUsersFilterUnicodeInBothListingsAndLegacyQueries() {
        for(String source:List.of("downloads","calibre")) {
            assertThat(listings.list(source,0,1,null,"","íñigo",null,null,"name,asc","eq","eq","árbol").items()).hasSize(1);
        }
        assertThat(bulk.list(0,1,null,"createdAt,desc",null,"íñigo",null,null).items()).hasSize(1);
        assertThat(bulk.list(0,1,null,"progress,desc",null,"íñigo",null,null).items()).hasSize(1);
        assertThat(calibre.jobs(0,1,"","íñigo",null,null,List.of("createdAt,desc")).items()).hasSize(1);
    }
    @Test void calibreLibraryMatchesUnicodeAndPreservesExclusionPaging() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/calibre/books").param("title","árbol 100%_").param("author","FRÍIDA ÍSBERG"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/calibre/books").param("author","fríida ísberg").param("authorOperator","ne").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].calibreId").value(2));
    }
    @Test void eventUsersFilterUnicodeInHistoryAndGroupedOperations() {
        journal.recordAs(new EventContext.Actor("unicode-actor","ÍÑIGO","USER"),EventJournal.Category.CATALOG,"UNICODE_TEST",EventJournal.Outcome.SUCCEEDED,EventContext.Origin.MANUAL,"unicode-operation",Map.of());
        var filter=new EventJournal.Filter(null,null,null,null,null,null,"íñigo");
        assertThat(journal.search(filter,0,1).items()).hasSize(1);
        assertThat(operations.search(filter,0,1,null).items()).hasSize(1);
    }
}
