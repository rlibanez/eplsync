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
    @Test void timeoutAndBusyErrorsExposeSafeSpecificDetails() throws Exception {
        var service=mock(CatalogImportService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new CatalogImportController(service,mock(com.rlibanez.eplsync.repository.CatalogMetadataRepository.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.importCatalog(null)).thenThrow(new com.rlibanez.eplsync.exception.CatalogOperationException(org.springframework.http.HttpStatus.REQUEST_TIMEOUT,"La operación de catálogo ha superado el tiempo máximo"));
        mvc.perform(post("/api/catalog/import/reset")).andExpect(status().isRequestTimeout())
            .andExpect(jsonPath("$.details").value("La operación de catálogo ha superado el tiempo máximo"));
        reset(service);
        when(service.importCatalog(null)).thenThrow(new com.rlibanez.eplsync.exception.CatalogOperationException(org.springframework.http.HttpStatus.CONFLICT,"Hay otra operación de catálogo en curso"));
        mvc.perform(post("/api/catalog/import/reset")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.details").value("Hay otra operación de catálogo en curso"));
        reset(service);
        when(service.importCatalog(null)).thenThrow(new org.springframework.web.multipart.MultipartException("Internal multipart error",
            new com.rlibanez.eplsync.exception.CatalogOperationException(org.springframework.http.HttpStatus.REQUEST_TIMEOUT,"La carga ha superado el tiempo máximo")));
        mvc.perform(post("/api/catalog/import/reset")).andExpect(status().isRequestTimeout())
            .andExpect(jsonPath("$.details").value("La carga ha superado el tiempo máximo"));
        reset(service);
        when(service.importCatalog(null)).thenThrow(new org.springframework.transaction.TransactionTimedOutException("private query detail"));
        try(var budget=new com.rlibanez.eplsync.importer.CatalogOperationBudget(java.time.Duration.ofSeconds(10))) {
            mvc.perform(post("/api/catalog/import/reset")).andExpect(status().isRequestTimeout())
                .andExpect(jsonPath("$.details").value("La operación de catálogo superó el tiempo disponible para la base de datos; los cambios no se han confirmado"));
        }
    }

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
        mvc.perform(post("/api/catalog/import/run").contentType("application/json").content("{\"source\":\"URL\",\"dryRun\":false,\"url\":\"https://example.com/catalog.zip\"}"))
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
        mvc.perform(post("/api/catalog/import/run").contentType("application/json").content("{\"source\":\"URL\",\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsProcessed").value(3))
                .andExpect(jsonPath("$.recordsCreated").value(1))
                .andExpect(jsonPath("$.recordsUpdated").value(1))
                .andExpect(jsonPath("$.createdBooks").doesNotExist())
                .andExpect(jsonPath("$.updatedBooks").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
        mvc.perform(post("/api/catalog/import/run").contentType("application/json").content("{\"source\":\"URL\",\"dryRun\":true,\"includeDetails\":true}"))
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
            mvc.perform(post("/api/catalog/import/run").contentType("application/json").content("{\"source\":\"URL\",\"dryRun\":true,\"" + parameter + "\":\"invalid\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Solicitud inválida"));
        }
        verifyNoInteractions(service);
    }
    @Test void wizardRoutesJsonAndMultipartAndRejectsInvalidSources() throws Exception {
        var service = mock(CatalogImportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CatalogImportController(service, mock(com.rlibanez.eplsync.repository.CatalogMetadataRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var result = new ImportResult(true, "OK", 1, 0, 0, 1, 0);
        when(service.updateCatalog("https://example.test/custom.zip")).thenReturn(result);
        when(service.runSaved("archive",CatalogImportService.Mode.UPDATE)).thenReturn(result);
        when(service.runUpload(any(),eq(CatalogImportService.Mode.PREVIEW))).thenReturn(result);
        mvc.perform(post("/api/catalog/import/run").contentType("application/json")
            .content("{\"source\":\"URL\",\"dryRun\":false,\"url\":\"https://example.test/custom.zip\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.recordsCreated").value(1));
        mvc.perform(post("/api/catalog/import/run").contentType("application/json")
            .content("{\"source\":\"SAVED\",\"dryRun\":false,\"archiveId\":\"archive\"}"))
            .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/catalog/import/run")
            .file(new org.springframework.mock.web.MockMultipartFile("file","books.zip","application/zip",new byte[]{1})).file(new org.springframework.mock.web.MockMultipartFile("options","","application/json","{\"dryRun\":true}".getBytes())))
            .andExpect(status().isOk());
        clearInvocations(service);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/catalog/import/run")
            .file(new org.springframework.mock.web.MockMultipartFile("options","","application/json","{\"dryRun\":true}".getBytes()))).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/catalog/import/run")
            .file(new org.springframework.mock.web.MockMultipartFile("file","books.zip","application/zip",new byte[]{1})))
            .andExpect(status().isBadRequest());

        for (String invalid : List.of(
                "{\"source\":\"SAVED\",\"dryRun\":false}",
                "{\"source\":\"OTHER\",\"dryRun\":false}",
                "{\"source\":\"URL\"}",
                "{\"source\":\"URL\",\"dryRun\":false,\"url\":\"file:///tmp/books.zip\"}",
                "{\"source\":\"URL\",\"dryRun\":false,\"url\":\"http:books.zip\"}")) {
            mvc.perform(post("/api/catalog/import/run").contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

}
