package com.ailyrx.aichat.kb;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryDocumentStore documentStore;

    @BeforeEach
    void clearStore() {
        documentStore.clear();
    }

    @Test
    void uploadReturnsPendingDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notice.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/kb/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.fileName").value("notice.txt"))
                .andExpect(jsonPath("$.data.contentType").value("text/plain"))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.chunkCount").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.errorMessage").value(nullValue()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void uploadRejectsUnsupportedExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notice.exe", "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/kb/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value(containsString("不支持的文件类型")))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void uploadRejectsOversizeFile() throws Exception {
        byte[] content = new byte[20 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.pdf", "application/pdf", content);

        mockMvc.perform(multipart("/api/kb/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value(containsString("20MB")));
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/kb/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value(containsString("文件不能为空")));
    }

    @Test
    void uploadRejectsMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/kb/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andExpect(jsonPath("$.message").value(containsString("请选择要上传的文件")));
    }

    @Test
    void uploadSanitizesFileName() throws Exception {
        String pathLikeName = ".." + (char) 92 + ".." + (char) 92 + "notice.txt";
        MockMultipartFile file = new MockMultipartFile(
                "file", pathLikeName, "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/kb/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("notice.txt"));
    }

    @Test
    void listReturnsDocumentsNewestFirst() throws Exception {
        MockMultipartFile first = new MockMultipartFile(
                "file", "first.txt", "text/plain", "1".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile second = new MockMultipartFile(
                "file", "second.txt", "text/plain", "2".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/kb/documents").file(first))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/kb/documents").file(second))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/kb/documents"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].fileName").value("second.txt"))
                .andExpect(jsonPath("$.data[1].fileName").value("first.txt"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].chunkCount").value(nullValue()))
                .andExpect(jsonPath("$.data[0].errorMessage").value(nullValue()));
    }
}
