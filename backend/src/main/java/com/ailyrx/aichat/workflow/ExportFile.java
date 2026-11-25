package com.ailyrx.aichat.workflow;

public record ExportFile(
        byte[] content,
        String contentType,
        String filename) {
}
