package com.ailyrx.aichat.check;

import java.util.List;

public record TermsCheckResult(boolean passed, int count, List<TermsHit> hits) {
}
