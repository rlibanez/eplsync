package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.settings.CalibreSettings;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.calibre.url=http://calibre.test","eplsync.calibre.library=main"})
@WithMockUser(authorities="CALIBRE_READ")
class CalibreDataExportTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired CalibreDataExportController controller;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM catalog_books");
        jdbc.update("INSERT INTO catalog_books(epl_id,title,author,revision) VALUES(42,'Catalog','Author',1.2)");
        book("one",1,42L,"Uno; ñ","=Autor",true,"[\"epub\",\"pdf\"]");
        book("two",2,42L,"Dos","Otro",true,"[\"epub\"]");
        book("three",3,null,"Sin catálogo","Autor",true,null);
        book("inactive",4,42L,"Inactivo","Autor",false,null);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    void book(String id,long calibre,Long epl,String title,String author,boolean active,String formats) {
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors,formats,identifiers) VALUES(?,'export',?,?,?,1.0,?,?,?,'{\"hardcover\":\"slug\",\"hardcover_book\":\"7\"}')",id,active,calibre,epl,title,author,formats);
    }
    String payload(String format,String columns) {return "{\"filters\":{\"ids\":[1,3,4]},\"format\":\""+format+"\",\"separator\":\"SEMICOLON\",\"columns\":"+columns+",\"ordering\":\"calibreId,desc\"}";}
    @Test void csvRespectsExactCalibreIdsOrderHeadersQuotingAndFormulaProtection() throws Exception {
        mvc.perform(post("/api/calibre/data/export/preview").contentType("application/json").content(payload("CSV","[\"calibreId\",\"title\",\"authors\"]")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(2))
            .andExpect(jsonPath("$.text").value("calibreId;title;authors\r\n3;Sin catálogo;Autor\r\n1;\"Uno; ñ\";'=Autor\r\n"));
    }
    @Test void jsonHasTypedIdsNullsFormatsAndCalibreSpecificMetadata() throws Exception {
        var result=mvc.perform(post("/api/calibre/data/export/preview").contentType("application/json").content(payload("JSON","[\"calibreId\",\"eplId\",\"formats\",\"catalogRevision\",\"presence\",\"hardcoverBook\"]")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(2)).andReturn();
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var rows=json.readTree(json.readTree(result.getResponse().getContentAsString()).path("text").asString());
        org.assertj.core.api.Assertions.assertThat(rows.get(0).path("eplId").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(rows.get(0).path("presence").asString()).isEqualTo("UNIDENTIFIED");
        org.assertj.core.api.Assertions.assertThat(rows.get(1).path("formats").size()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(rows.get(1).path("hardcoverBook").asString()).isEqualTo("7");
    }
    @Test void exportStreamsSamePreviewAndAllowsCustomExtension() throws Exception {
        String body=payload("CSV","[\"calibreId\"]").replace("\"ordering\"","\"extension\":\"md\",\"ordering\"");
        var result=mvc.perform(post("/api/calibre/data/export").contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=\"calibre.md\""))
            .andExpect(content().string("calibreId\r\n3\r\n1\r\n"));
    }
    @Test void limitsPreviewToFiveRowsAndRejectsInvalidColumnsIdsAndSorting() throws Exception {
        for(int id=5;id<12;id++) book("book"+id,id,null,"Book","Author",true,null);
        String body=payload("JSON","[\"calibreId\"]").replace("[1,3,4]","[1,2,3,5,6,7,8,9,10,11]");
        mvc.perform(post("/api/calibre/data/export/preview").contentType("application/json").content(body)).andExpect(jsonPath("$.rows").value(5));
        for(String invalid:new String[]{payload("CSV","[\"title\",\"title\"]"),payload("CSV","[\"password\"]"),body.replace("calibreId,desc","title;drop table"),body.replace("[1,2,3,5,6,7,8,9,10,11]","[-1]")})
            mvc.perform(post("/api/calibre/data/export/preview").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
    }
    @Test void metadataExportRequiresCalibreReadButNotFileDownloadOrCatalogPermissions() throws Exception {
        var secured=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String body=payload("JSON","[\"calibreId\"]");
        secured.perform(post("/api/calibre/data/export/preview").with(csrf()).with(user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALIBRE_READ"))).contentType("application/json").content(body)).andExpect(status().isOk());
        secured.perform(post("/api/calibre/data/export/preview").with(csrf()).with(user("catalog").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"))).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
