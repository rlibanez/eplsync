package com.rlibanez.eplsync.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UiConfigControllerTests {
    @Test
    void exposesOnlyPublicLanguageAndDisablesCaching() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UiConfigController("es")).build();
        mvc.perform(get("/api/ui/config")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"defaultLanguage\":\"es\"}"));
    }

    @Test
    void leavesTranslationAvailabilityToFrontend() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UiConfigController("unknown")).build();
        mvc.perform(get("/api/ui/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultLanguage").value("unknown"));
    }
}
