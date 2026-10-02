package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.service.*;
import com.rlibanez.eplsync.torrent.updates.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OperationContractTests {
    @Test void explicitBooleanAndBodyOnlyAreRequiredBeforeAnyOperation() throws Exception {
        var covers = mock(CoverCheckService.class);
        var tasks = mock(CoverTaskService.class);
        var planner = mock(UpdatePlanner.class);
        var bulk = mock(BulkStore.class);
        var imports = mock(CatalogImportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CoverCheckController(covers),new CoverTaskController(tasks),
                new UpdateController(planner,mock(UpdateCleanupService.class),bulk),new SelectionController(planner,bulk),
                new CatalogImportController(imports,mock(com.rlibanez.eplsync.repository.CatalogMetadataRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : java.util.List.of("/api/catalog/covers/check","/api/catalog/covers/task",
                "/api/torrent/books","/api/torrent/updates","/api/torrent/refresh","/api/catalog/import/run")) {
            for (String body : java.util.List.of("{}","{\"dryRun\":null}","{\"dryRun\":\"true\"}","{\"dryRun\":1}",
                    "{\"dryRun\":false,\"typo\":true}"))
                mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(post(path+"?dryRun=true").contentType("application/json").content("{\"dryRun\":false}"))
                    .andExpect(status().isBadRequest());
        }
        for (String path : java.util.List.of("/api/catalog/covers/check","/api/torrent/books","/api/torrent/updates","/api/torrent/refresh"))
            mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        for (String path : java.util.List.of("/api/catalog/import/preview","/api/catalog/import/update"))
            mvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(covers,tasks,planner,bulk,imports);
    }
}
