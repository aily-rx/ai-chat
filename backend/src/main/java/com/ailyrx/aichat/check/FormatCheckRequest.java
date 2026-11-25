package com.ailyrx.aichat.check;

import jakarta.validation.constraints.Size;

/**
 * 公文格式校验入参。字段允许为空，由校验规则返回问题列表，而不是直接拒绝。
 */
public record FormatCheckRequest(
        @Size(max = 100, message = "标题不能超过100字") String title,
        @Size(max = 20, message = "文种不能超过20字") String docType,
        @Size(max = 200, message = "主送机关不能超过200字") String recipient,
        @Size(max = 100, message = "落款不能超过100字") String signature,
        @Size(max = 40, message = "日期不能超过40字") String date,
        @Size(max = 50000, message = "正文不能超过50000字") String body) {
}
