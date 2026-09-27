package com.rlibanez.eplsync.model;

import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.config.LanguageWebConfiguration;
import com.rlibanez.eplsync.controller.CatalogBookController;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import tools.jackson.databind.json.JsonMapper;
import java.util.Optional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LanguageApiTests {
    @Test
    void serializesAndReadsAllLanguageCodesAndLegacyNames() {
        var mapper = JsonMapper.builder().build();
        for (var language : Language.values()) {
            assertThat(mapper.writeValueAsString(language)).isEqualTo("\"" + language.getIsoCode() + "\"");
            assertThat(mapper.readValue("\"" + language.getIsoCode() + "\"", Language.class)).isEqualTo(language);
            assertThat(mapper.readValue("\"" + language.name() + "\"", Language.class)).isEqualTo(language);
        }
        assertThatThrownBy(() -> mapper.readValue("\"invalid\"", Language.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void bookEndpointReturnsCodeAndSearchBindsCodesAndLegacyNames() throws Exception {
        var service = mock(CatalogBookService.class);
        var book = CatalogBook.builder().eplId(32L).language(Language.ESPANOL).build();
        when(service.getByEplId(32L)).thenReturn(Optional.of(book));
        when(service.searchAll(any())).thenReturn(List.of(book));
        var conversion = new DefaultFormattingConversionService();
        new LanguageWebConfiguration().addFormatters(conversion);
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogBookController(service))
                .setConversionService(conversion)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/catalog/books/32")).andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("es"));
        for (String value : List.of("es", "ESPANOL")) {
            mvc.perform(get("/api/catalog/books").param("language", value)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].language").value("es"));
        }
        var captor = org.mockito.ArgumentCaptor.forClass(CatalogBookFilter.class);
        verify(service, times(2)).searchAll(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(filter -> assertThat(filter.getLanguage()).isEqualTo(Language.ESPANOL));
        mvc.perform(get("/api/catalog/books").param("language", "invalid")).andExpect(status().isBadRequest());
    }
}
