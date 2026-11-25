package com.ailyrx.aichat.check;

/**
 * 单个格式问题。severity 为 ERROR 或 WARN；ERROR 会导致 passed=false。
 */
public record FormatIssue(
        String field,
        String severity,
        String message,
        String location,
        String suggestion) {

    public static final String ERROR = "ERROR";
    public static final String WARN = "WARN";

    public static FormatIssue error(String field, String message, String location, String suggestion) {
        return new FormatIssue(field, ERROR, message, location, suggestion);
    }

    public static FormatIssue warn(String field, String message, String location, String suggestion) {
        return new FormatIssue(field, WARN, message, location, suggestion);
    }
}
