package com.ailyrx.aichat.check;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void formatCheckReportsIssuesWithoutModifyingText() throws Exception {
        mockMvc.perform(post("/api/format/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "关于开展检查的通知",
                                  "docType": "通知",
                                  "recipient": "各部门：",
                                  "signature": "XX办公室",
                                  "date": "2026-02-30",
                                  "body": "第一段没有缩进。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.issues[0].field").value("date"))
                .andExpect(jsonPath("$.data.issues[1].message").value("段落缺少首行缩进"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void formatCheckHandlesEmptyObjectAsIssues() throws Exception {
        mockMvc.perform(post("/api/format/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.issues.length()").value(6));
    }

    @Test
    void termsCheckReturnsHitsAndPositions() throws Exception {
        mockMvc.perform(post("/api/terms/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"不能做为旁观者。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.hits[0].term").value("做为"))
                .andExpect(jsonPath("$.data.hits[0].suggestion").value("作为"))
                .andExpect(jsonPath("$.data.hits[0].position").value(2))
                .andExpect(jsonPath("$.data.hits[0].line").value(1))
                .andExpect(jsonPath("$.data.hits[0].column").value(3));
    }

    @Test
    void termsCheckHandlesEmptyText() throws Exception {
        mockMvc.perform(post("/api/terms/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void rejectsOversizedTextWith4000() throws Exception {
        String text = "字".repeat(50001);
        mockMvc.perform(post("/api/terms/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000));
    }
}
