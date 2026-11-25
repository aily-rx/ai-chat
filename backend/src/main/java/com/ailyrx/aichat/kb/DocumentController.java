package com.ailyrx.aichat.kb;

import com.ailyrx.aichat.common.ApiResponse;
import com.ailyrx.aichat.common.ErrorCodes;
import com.ailyrx.aichat.config.TraceIdFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/kb")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentRecord>> upload(
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        try {
            DocumentRecord document = documentService.upload(file, traceId);
            log.info("kb upload accepted traceId={} documentId={} fileName={} size={} status={}",
                    traceId, document.id(), document.fileName(), document.size(), document.status());
            return ResponseEntity.ok(ApiResponse.ok(document, traceId));
        } catch (DocumentValidationException exception) {
            log.warn("kb upload rejected traceId={} reason={}", traceId, exception.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCodes.INVALID_PARAMETER, exception.getMessage(), traceId));
        }
    }

    @GetMapping("/documents")
    public ApiResponse<List<DocumentRecord>> list(
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        return ApiResponse.ok(documentService.list(), traceId);
    }
}
