package com.ailyrx.aichat.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FormatCheckerTest {

    private final FormatChecker checker = new FormatChecker();

    @Test
    void passesWellFormedDocument() {
        FormatCheckResult result = checker.check(new FormatCheckRequest(
                "关于开展安全生产检查的通知",
                "通知",
                "各部门：",
                "XX办公室",
                "2026-11-25",
                "　　为进一步加强安全生产工作，现将有关事项通知如下：\n"
                        + "一、总体要求\n"
                        + "　　（一）提高认识\n"
                        + "　　1. 落实责任"));

        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void reportsAllMissingFields() {
        FormatCheckResult result = checker.check(new FormatCheckRequest(null, null, null, null, null, null));

        assertThat(result.passed()).isFalse();
        Set<String> fields = result.issues().stream()
                .filter(issue -> FormatIssue.ERROR.equals(issue.severity()))
                .map(FormatIssue::field)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder("title", "docType", "recipient", "signature", "date", "body");
        assertThat(result.issues()).allMatch(issue -> issue.suggestion() != null && !issue.suggestion().isBlank());
    }

    @Test
    void rejectsInvalidDateAndDiscontinuousHeadings() {
        FormatCheckResult result = checker.check(new FormatCheckRequest(
                "关于开展检查的通知",
                "通知",
                "各部门：",
                "XX办公室",
                "2026-02-30",
                "　　第一段。\n二、工作要求\n（二）落实\n2. 完成"));

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(issue -> "date".equals(issue.field())
                && FormatIssue.ERROR.equals(issue.severity()));
        assertThat(result.issues()).anyMatch(issue -> issue.message().contains("一级标题序号不连续"));
        assertThat(result.issues()).anyMatch(issue -> issue.message().contains("二级标题序号不连续"));
        assertThat(result.issues()).anyMatch(issue -> issue.message().contains("三级标题序号不连续"));
        assertThat(result.issues()).allMatch(issue -> issue.location() != null && !issue.location().isBlank());
    }

    @Test
    void warnsOnMissingIndentWithoutFailing() {
        FormatCheckResult result = checker.check(new FormatCheckRequest(
                "关于开展检查的通知",
                "通知",
                "各部门：",
                "XX办公室",
                "2026-11-25",
                "第一段没有缩进。\n　　第二段有缩进。"));

        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).anyMatch(issue -> "body".equals(issue.field())
                && FormatIssue.WARN.equals(issue.severity())
                && issue.location().contains("第 1 行"));
    }

    @Test
    void acceptsChineseDateAndWarnsOnUncommonDocType() {
        FormatCheckResult result = checker.check(new FormatCheckRequest(
                "关于检查情况的说明",
                "说明",
                "各部门",
                "XX办公室",
                "2026年11月25日",
                "　　正文。"));

        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).anyMatch(issue -> issue.message().contains("不是常用法定文种"));
        assertThat(result.issues()).anyMatch(issue -> issue.message().contains("冒号"));
    }
}
