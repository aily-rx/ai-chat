package com.ailyrx.aichat.check;

import java.util.List;

public record FormatCheckResult(boolean passed, List<FormatIssue> issues) {
}
