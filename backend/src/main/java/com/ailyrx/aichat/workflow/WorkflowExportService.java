package com.ailyrx.aichat.workflow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

/**
 * 将已确认的公文导出为 Markdown 或 DOCX（P1）。
 */
@Service
public class WorkflowExportService {

    public ExportFile export(WorkflowRunState state, String format) {
        String normalized = format == null ? "md" : format.strip().toLowerCase();
        String baseName = sanitizeFilename(state.getDocument().title());
        if ("md".equals(normalized) || "markdown".equals(normalized)) {
            return new ExportFile(
                    markdown(state).getBytes(StandardCharsets.UTF_8),
                    "text/markdown; charset=UTF-8",
                    baseName + ".md");
        }
        if ("docx".equals(normalized)) {
            return new ExportFile(
                    docx(state),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    baseName + ".docx");
        }
        throw WorkflowRunException.exportFailed("不支持的导出格式");
    }

    String markdown(WorkflowRunState state) {
        WorkflowDocumentMeta document = state.getDocument();
        String content = state.getConfirmation().content();
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(document.title()).append("\n\n");
        if (!document.recipient().isBlank()) {
            builder.append(document.recipient()).append("\n\n");
        }
        builder.append(content).append("\n\n");
        builder.append(document.signature()).append("\n");
        builder.append(document.date()).append("\n\n");
        builder.append("---\n\n");
        builder.append("## 校验摘要\n\n");
        builder.append("- 格式检查：").append(formatSummary(state)).append("\n");
        builder.append("- 术语检查：").append(termsSummary(state)).append("\n");
        builder.append("- 确认结果：").append(actionLabel(state.getConfirmation().action())).append("\n");
        return builder.toString();
    }

    private byte[] docx(WorkflowRunState state) {
        WorkflowDocumentMeta document = state.getDocument();
        String content = state.getConfirmation().content();

        try (XWPFDocument output = new XWPFDocument();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            addParagraph(output, document.title(), 18, true, ParagraphAlignment.CENTER);
            addParagraph(output, "", 12, false, ParagraphAlignment.LEFT);
            if (!document.recipient().isBlank()) {
                addParagraph(output, document.recipient(), 12, false, ParagraphAlignment.LEFT);
            }
            for (String line : content.split("\r?\n", -1)) {
                addParagraph(output, line, 12, false, ParagraphAlignment.LEFT);
            }
            addParagraph(output, "", 12, false, ParagraphAlignment.LEFT);
            addParagraph(output, document.signature(), 12, false, ParagraphAlignment.RIGHT);
            addParagraph(output, document.date(), 12, false, ParagraphAlignment.RIGHT);
            addParagraph(output, "", 12, false, ParagraphAlignment.LEFT);
            addParagraph(output, "校验摘要", 13, true, ParagraphAlignment.LEFT);
            addParagraph(output, "格式检查：" + formatSummary(state), 11, false, ParagraphAlignment.LEFT);
            addParagraph(output, "术语检查：" + termsSummary(state), 11, false, ParagraphAlignment.LEFT);
            addParagraph(output, "确认结果：" + actionLabel(state.getConfirmation().action()), 11, false, ParagraphAlignment.LEFT);
            output.write(buffer);
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw WorkflowRunException.exportFailed("导出文件生成失败");
        }
    }

    private void addParagraph(
            XWPFDocument document,
            String text,
            int fontSize,
            boolean bold,
            ParagraphAlignment alignment) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily("宋体");
        run.setFontSize(fontSize);
        run.setBold(bold);
    }

    private String formatSummary(WorkflowRunState state) {
        Map<String, Object> format = map(state.getToolResults().get("format"));
        if (format == null) {
            return "未返回结果";
        }
        if (Boolean.TRUE.equals(format.get("fallback"))) {
            return "工具不可用，已兜底并保留草稿";
        }
        Object passed = format.get("passed");
        if (Boolean.TRUE.equals(passed) || "true".equalsIgnoreCase(String.valueOf(passed))) {
            return "通过";
        }
        String issueText = stringValue(format.get("issue_text"));
        return issueText.isBlank() ? "未通过" : issueText;
    }

    private String termsSummary(WorkflowRunState state) {
        Map<String, Object> terms = map(state.getToolResults().get("terms"));
        if (terms == null) {
            return "未返回结果";
        }
        if (Boolean.TRUE.equals(terms.get("fallback"))) {
            return "工具不可用，已兜底并保留草稿";
        }
        Object hasHits = terms.get("has_hits");
        if (Boolean.FALSE.equals(hasHits) || "false".equalsIgnoreCase(String.valueOf(hasHits))) {
            return "未发现需调整项";
        }
        String hitText = stringValue(terms.get("hit_text"));
        return hitText.isBlank() ? "发现需调整项" : hitText;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String actionLabel(String action) {
        return switch (action == null ? "" : action) {
            case "approve" -> "通过";
            case "modify" -> "修改后通过";
            case "reject" -> "驳回";
            default -> action;
        };
    }

    private String sanitizeFilename(String title) {
        String cleaned = title == null ? "" : title
                .replaceAll("[\\/:*?\"<>|\r\n]", "_")
                .replaceAll("\s+", " ")
                .strip();
        if (cleaned.isBlank()) {
            cleaned = "公文";
        }
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }
}
