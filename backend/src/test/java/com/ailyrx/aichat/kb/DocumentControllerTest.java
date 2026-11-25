package com.ailyrx.aichat.kb;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ailyrx.aichat.dify.DifyClient;
import com.ailyrx.aichat.dify.DifyException;
import com.ailyrx.aichat.dify.DifyProperties;
import com.ailyrx.aichat.dify.DifyUploadResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.dify.base-url=http://localhost/v1",
        "app.dify.api-key=test-key",
        "app.dify.default-dataset-id=dataset-b",
        "app.dify.poll-interval-ms=60000"
})
@AutoConfigureMockMvc
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryDocumentStore documentStore;

    @MockBean
    private DifyClient difyClient;

    @MockBean
    private DifyIndexingPoller indexingPoller;

    @Autowired
    private DifyProperties difyProperties;

    @Test
    void separatorDefaultsToParagraphBreaks() {
        assertEquals("\n\n", difyProperties.separator());
    }

    @BeforeEach
    void clearStore() {
        documentStore.clear();
        when(difyClient.uploadDocument(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(new DifyUploadResult("dify-doc-1", "notice.txt", "batch-1", "waiting"));
    }

    @Test
    void uploadReturnsIndexingDocument() throws Exception {
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
                .andExpect(jsonPath("$.data.status").value("INDEXING"))
                .andExpect(jsonPath("$.data.chunkCount").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.errorMessage").value(nullValue()))
                .andExpect(jsonPath("$.data.difyDatasetId").value("dataset-b"))
                .andExpect(jsonPath("$.data.difyDocumentId").value("dify-doc-1"))
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
                .andExpect(jsonPath("$.data[0].status").value("INDEXING"))
                .andExpect(jsonPath("$.data[0].chunkCount").value(nullValue()))
                .andExpect(jsonPath("$.data[0].errorMessage").value(nullValue()));
    }

    @Test
    void uploadMarksFailedWhenDifyUnavailable() throws Exception {
        when(difyClient.uploadDocument(anyString(), anyString(), anyString(), any(byte[].class), anyString()))
                .thenThrow(new DifyException("知识库服务暂不可用，请稍后重试", 503));
        MockMultipartFile file = new MockMultipartFile(
                "file", "notice.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/kb/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(5002))
                .andExpect(jsonPath("$.message").value("知识库服务暂不可用，请稍后重试"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get("/api/kb/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("知识库服务暂不可用，请稍后重试"));
    }
}
