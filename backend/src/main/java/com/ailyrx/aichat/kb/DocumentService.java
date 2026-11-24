package com.ailyrx.aichat.kb;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain",
            "md", "text/markdown");

    private final DocumentStore documentStore;

    public DocumentService(DocumentStore documentStore) {
        this.documentStore = documentStore;
    }

    public DocumentRecord upload(MultipartFile file) {
        if (file == null) {
            throw new DocumentValidationException("请选择要上传的文件");
        }
        if (file.isEmpty()) {
            throw new DocumentValidationException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new DocumentValidationException("文件大小不能超过20MB");
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new DocumentValidationException("不支持的文件类型，仅支持 pdf/docx/txt/md");
        }

        DocumentRecord document = new DocumentRecord(
                UUID.randomUUID().toString(),
                fileName,
                resolveContentType(file.getContentType(), extension),
                file.getSize(),
                DocumentStatus.PENDING,
                null,
                Instant.now(),
                null);
        return documentStore.save(document);
    }

    public List<DocumentRecord> list() {
        return documentStore.findAll();
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new DocumentValidationException("文件名不能为空");
        }
        String fileName = originalFileName.replace((char) 92, '/');
        int slashIndex = fileName.lastIndexOf('/');
        if (slashIndex >= 0) {
            fileName = fileName.substring(slashIndex + 1);
        }
        StringBuilder cleaned = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char ch = fileName.charAt(i);
            if (!Character.isISOControl(ch)) {
                cleaned.append(ch);
            }
        }
        fileName = cleaned.toString().trim();
        if (fileName.isBlank()) {
            throw new DocumentValidationException("文件名不能为空");
        }
        return fileName;
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveContentType(String providedContentType, String extension) {
        if (providedContentType != null && !providedContentType.isBlank()) {
            return providedContentType;
        }
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
