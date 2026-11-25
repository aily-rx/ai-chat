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
public class FormatCheckController {

    private final FormatChecker formatChecker;

    public FormatCheckController(FormatChecker formatChecker) {
        this.formatChecker = formatChecker;
    }

    @PostMapping("/format/check")
    public ApiResponse<FormatCheckResult> check(
            @Valid @RequestBody FormatCheckRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE) String traceId) {
        return ApiResponse.ok(formatChecker.check(request), traceId);
    }
}
