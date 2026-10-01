package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.torrent.downloads.CatalogDownloadViewService;
import org.junit.jupiter.api.Test;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogBookFilterParameterTests {
    @Test void rejectsMisspelledAndEmptyFiltersBeforeQueryingCatalog() throws Exception {
        var service = mock(CatalogBookService.class);
        var downloads = mock(CatalogDownloadViewService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogBookController(service, downloads))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String parameter : new String[]{"EplId", "titel"})
            mvc.perform(get("/api/catalog/books").param(parameter, "32"))
                    .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books").param("eplId", ""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, downloads);
        mvc.perform(get("/api/catalog/books").param("eplId", "32"))
                .andExpect(status().isOk());
        verify(service).searchAll(argThat(filter -> Long.valueOf(32).equals(filter.getEplId())));
    }
    @Test void acceptsAliasAndMatchingIdsButRejectsInvalidOrConflictingValues() throws Exception {
        var service = mock(CatalogBookService.class);
        var downloads = mock(CatalogDownloadViewService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogBookController(service, downloads))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/catalog/books").param("eplid", "32")).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/books").param("eplId", "32").param("eplid", "32"))
                .andExpect(status().isOk());
        verify(service, times(2)).searchAll(argThat(filter -> Long.valueOf(32).equals(filter.getEplId())));
        clearInvocations(service, downloads);
        mvc.perform(get("/api/catalog/books").param("eplId", "32").param("eplid", "45"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books").param("eplid", "32", "45"))
                .andExpect(status().isBadRequest());
        for (String invalid : new String[]{"", " ", "0", "-1", "abc", "1.5", "9223372036854775808"})
            mvc.perform(get("/api/catalog/books").param("eplid", invalid))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(service, downloads);
    }
}
