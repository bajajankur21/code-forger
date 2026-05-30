package com.codeforger.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "codeforger.orchestrator.enabled=false",
    "APP_SECRET_PASSCODE=test-secret"
})
@AutoConfigureMockMvc
class GeneratorControllerTest {

    private static final String JOB_PATH_REGEX =
            "/api/jobs/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String TEST_PASSCODE = "test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void postGenerate_returns202_withLocationHeaderAndQueuedJob() throws Exception {
        mockMvc.perform(post("/api/generate")
                        .header("X-API-Key", TEST_PASSCODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specUrl\":\"https://example.com/spec.json\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesPattern(JOB_PATH_REGEX)))
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void postGenerate_rejectsBlankSpecUrl_with400() throws Exception {
        mockMvc.perform(post("/api/generate")
                        .header("X-API-Key", TEST_PASSCODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specUrl\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getJob_returnsQueuedStatusAfterSubmit() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/generate")
                        .header("X-API-Key", TEST_PASSCODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specUrl\":\"https://example.com/spec.json\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String jobUrl = submitted.getResponse().getHeader("Location");

        mockMvc.perform(get(jobUrl)
                        .header("X-API-Key", TEST_PASSCODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(content().string(not(containsString("\"files\""))))
                .andExpect(content().string(not(containsString("\"error\""))));
    }

    @Test
    void getJob_returns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/jobs/does-not-exist")
                        .header("X-API-Key", TEST_PASSCODE))
                .andExpect(status().isNotFound());
    }
}
