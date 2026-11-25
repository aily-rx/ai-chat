package com.ailyrx.aichat.check;

import com.ailyrx.aichat.common.ApiResponse;
import com.ailyrx.aichat.config.TraceIdFilter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TermsCheckController {

    private final TermsChecker termsChecker;

    public TermsCheckController(TermsChecker termsChecker) {
        this.termsChecker = termsChecker;
    }

    @PostMapping("/terms/check")
    public ApiResponse<TermsCheckResult> check(
            @Valid @RequestBody TermsCheckRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        return ApiResponse.ok(termsChecker.check(request.text()), traceId);
    }
}
