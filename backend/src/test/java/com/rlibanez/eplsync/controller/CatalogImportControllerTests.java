package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.dto.ImportResult;
import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.dto.ImportPreviewResult;
import java.util.List;
import com.rlibanez.eplsync.service.CatalogImportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogImportControllerTests {
    @Test
    void routesReplacementAndUpdateAndExposesCounters() throws Exception {
        var service = mock(CatalogImportService.class);
        when(service.importCatalog(null)).thenReturn(new ImportResult(true, "Importación completada", 3, 0, 0, 3, 0));
        when(service.updateCatalog("https://example.com/catalog.zip"))
                .thenReturn(new ImportResult(true, "Importación completada", 3, 0, 1, 1, 1));
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogImportController(service, org.mockito.Mockito.mock(com.rlibanez.eplsync.repository.CatalogMetadataRepository.class))).build();
        mvc.perform(post("/api/catalog/import"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
        mvc.perform(post("/api/catalog/import/update").param("url", "https://example.com/catalog.zip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsProcessed").value(3))
                .andExpect(jsonPath("$.recordsUpdated").value(1))
                .andExpect(jsonPath("$.recordsCreated").value(1))
                .andExpect(jsonPath("$.recordsUnchanged").value(1));
        mvc.perform(post("/api/catalog/import/reset"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordsCreated").value(3));
        when(service.previewCatalog(null, 0, 50)).thenReturn(new ImportPreviewResult(
                new ImportResult(true, "Previsualización completada", 3, 0, 1, 1, 1),
                0, 50, List.of(), List.of()));
        mvc.perform(post("/api/catalog/import/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsProcessed").value(3))
                .andExpect(jsonPath("$.recordsCreated").value(1))
                .andExpect(jsonPath("$.recordsUpdated").value(1))
                .andExpect(jsonPath("$.createdBooks").doesNotExist())
                .andExpect(jsonPath("$.updatedBooks").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
        mvc.perform(post("/api/catalog/import/preview").param("includeDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.recordsUpdated").value(1))
                .andExpect(jsonPath("$.createdBooks").isArray())
                .andExpect(jsonPath("$.updatedBooks").isArray());
        verify(service, times(2)).previewCatalog(null, 0, 50);
        verify(service).importCatalog(null);
        verify(service).updateCatalog("https://example.com/catalog.zip");
    }
    @Test
    void invalidParameterTypesReturn400WithoutDownloading() throws Exception {
        var service = mock(CatalogImportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogImportController(service, org.mockito.Mockito.mock(com.rlibanez.eplsync.repository.CatalogMetadataRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String parameter : List.of("page", "size", "includeDetails")) {
            mvc.perform(post("/api/catalog/import/preview").param(parameter, "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Solicitud inválida"));
        }
        verifyNoInteractions(service);
    }
}
