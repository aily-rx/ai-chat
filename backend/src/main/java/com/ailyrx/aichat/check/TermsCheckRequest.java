package com.ailyrx.aichat.check;

import jakarta.validation.constraints.Size;

public record TermsCheckRequest(
        @Size(max = 50000, message = "文本不能超过50000字") String text) {
}
