package com.ailyrx.aichat.check;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 公文格式规则检查。只返回问题，不修改原文，避免工具调用方丢失用户内容。
 */
@Service
public class FormatChecker {

    private static final Set<String> DOC_TYPES = Set.of(
            "通知", "通报", "报告", "请示", "批复", "意见", "决定", "公告", "通告", "函", "纪要", "方案");
    private static final Pattern DATE_DASH = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern DATE_CHINESE = Pattern.compile("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日$");
    private static final Pattern LEVEL_ONE = Pattern.compile("^([一二三四五六七八九十百]+)、.*$");
    private static final Pattern LEVEL_TWO = Pattern.compile("^（([一二三四五六七八九十百]+)）.*$");
    private static final Pattern LEVEL_THREE = Pattern.compile("^(\\d+)[.、].*$");
    private static final int MAX_BODY_ISSUES = 50;

    public FormatCheckResult check(FormatCheckRequest request) {
        List<FormatIssue> issues = new ArrayList<>();
        checkTitle(request, issues);
        checkDocType(request, issues);
        checkRecipient(request, issues);
        checkSignature(request, issues);
        checkDate(request, issues);
        checkBody(request.body(), issues);
        boolean passed = issues.stream().noneMatch(issue -> FormatIssue.ERROR.equals(issue.severity()));
        return new FormatCheckResult(passed, List.copyOf(issues));
    }

    private void checkTitle(FormatCheckRequest request, List<FormatIssue> issues) {
        String title = clean(request.title());
        if (title.isEmpty()) {
            issues.add(FormatIssue.error("title", "标题不能为空", "title", "补充公文标题"));
            return;
        }
        if (title.contains("\n") || title.contains("\r")) {
            issues.add(FormatIssue.error("title", "标题不能包含换行", "title", "将标题整理为单行"));
        }
        if (title.length() > 50) {
            issues.add(FormatIssue.warn("title", "标题超过50字，建议精简", "title", "压缩标题，保留事由和文种"));
        }
        String docType = clean(request.docType());
        if (!docType.isEmpty() && !title.endsWith(docType)) {
            issues.add(FormatIssue.warn(
                    "title", "标题末尾未使用指定文种“" + docType + "”", "title",
                    "按“关于……的" + docType + "”组织标题"));
        }
    }

    private void checkDocType(FormatCheckRequest request, List<FormatIssue> issues) {
        String docType = clean(request.docType());
        if (docType.isEmpty()) {
            issues.add(FormatIssue.error("docType", "文种不能为空", "docType", "填写通知、报告、请示等规范文种"));
        } else if (!DOC_TYPES.contains(docType)) {
            issues.add(FormatIssue.warn(
                    "docType", "“" + docType + "”不是常用法定文种", "docType",
                    "从常用文种中选择，或在业务约定中说明"));
        }
    }

    private void checkRecipient(FormatCheckRequest request, List<FormatIssue> issues) {
        String recipient = clean(request.recipient());
        if (recipient.isEmpty()) {
            issues.add(FormatIssue.error("recipient", "主送机关不能为空", "recipient", "补充主送机关"));
            return;
        }
        if (recipient.contains("\n") || recipient.contains("\r")) {
            issues.add(FormatIssue.error("recipient", "主送机关不能包含换行", "recipient", "将主送机关整理为一行"));
        }
        if (!recipient.endsWith("：") && !recipient.endsWith(":")) {
            issues.add(FormatIssue.warn(
                    "recipient", "主送机关建议以冒号结尾", "recipient", "在主送机关末尾补全角冒号"));
        }
    }

    private void checkSignature(FormatCheckRequest request, List<FormatIssue> issues) {
        String signature = clean(request.signature());
        if (signature.isEmpty()) {
            issues.add(FormatIssue.error("signature", "落款不能为空", "signature", "补充发文机关或署名"));
        } else if (signature.length() > 50) {
            issues.add(FormatIssue.warn("signature", "落款超过50字，建议精简", "signature", "使用规范机关全称或规范简称"));
        }
    }

    private void checkDate(FormatCheckRequest request, List<FormatIssue> issues) {
        String date = clean(request.date());
        if (date.isEmpty()) {
            issues.add(FormatIssue.error("date", "成文日期不能为空", "date", "补充成文日期"));
            return;
        }
        if (parseDate(date) == null) {
            issues.add(FormatIssue.error(
                    "date", "日期格式不合法或日期不存在", "date", "使用 YYYY-MM-DD 或 YYYY年M月D日"));
        }
    }

    private LocalDate parseDate(String value) {
        Matcher dash = DATE_DASH.matcher(value);
        if (dash.matches()) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        Matcher chinese = DATE_CHINESE.matcher(value);
        if (chinese.matches()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(chinese.group(1)),
                        Integer.parseInt(chinese.group(2)),
                        Integer.parseInt(chinese.group(3)));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private void checkBody(String body, List<FormatIssue> issues) {
        if (body == null || body.isBlank()) {
            issues.add(FormatIssue.error("body", "正文不能为空", "body", "补充公文正文"));
            return;
        }

        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int expectedLevelOne = 1;
        int expectedLevelTwo = 1;
        int expectedLevelThree = 1;
        boolean levelOneSeen = false;
        boolean levelTwoSeen = false;
        int bodyIssueCount = 0;

        for (int index = 0; index < lines.length && bodyIssueCount < MAX_BODY_ISSUES; index++) {
            String line = lines[index];
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String location = "body:第 " + (index + 1) + " 行";
            boolean heading = isHeading(trimmed);

            Matcher levelOne = LEVEL_ONE.matcher(trimmed);
            if (levelOne.matches()) {
                int number = chineseNumber(levelOne.group(1));
                if (number != expectedLevelOne) {
                    issues.add(FormatIssue.warn(
                            "body", "一级标题序号不连续，期望“" + chineseNumber(expectedLevelOne) + "、”",
                            location, "按一、二、三的顺序连续编号"));
                    bodyIssueCount++;
                }
                expectedLevelOne = number + 1;
                expectedLevelTwo = 1;
                expectedLevelThree = 1;
                levelOneSeen = true;
                levelTwoSeen = false;
                continue;
            }

            Matcher levelTwo = LEVEL_TWO.matcher(trimmed);
            if (levelTwo.matches()) {
                if (!levelOneSeen) {
                    issues.add(FormatIssue.warn(
                            "body", "二级标题出现在一级标题之前", location, "先设置一级标题，再使用（一）（二）"));
                    bodyIssueCount++;
                }
                int number = chineseNumber(levelTwo.group(1));
                if (number != expectedLevelTwo) {
                    issues.add(FormatIssue.warn(
                            "body", "二级标题序号不连续，期望“（" + chineseNumber(expectedLevelTwo) + "）”",
                            location, "在当前一级标题内按（一）（二）连续编号"));
                    bodyIssueCount++;
                }
                expectedLevelTwo = number + 1;
                expectedLevelThree = 1;
                levelTwoSeen = true;
                continue;
            }

            Matcher levelThree = LEVEL_THREE.matcher(trimmed);
            if (levelThree.matches()) {
                if (!levelTwoSeen) {
                    issues.add(FormatIssue.warn(
                            "body", "三级标题出现在二级标题之前", location, "先设置二级标题，再使用 1. 2. 编号"));
                    bodyIssueCount++;
                }
                int number = Integer.parseInt(levelThree.group(1));
                if (number != expectedLevelThree) {
                    issues.add(FormatIssue.warn(
                            "body", "三级标题序号不连续，期望“" + expectedLevelThree + ".”",
                            location, "在当前二级标题内按 1. 2. 3. 连续编号"));
                    bodyIssueCount++;
                }
                expectedLevelThree = number + 1;
                continue;
            }

            if (!heading && !hasFirstLineIndent(line)) {
                issues.add(FormatIssue.warn(
                        "body", "段落缺少首行缩进", location, "段首使用两个全角空格或四个半角空格"));
                bodyIssueCount++;
            }
        }
        if (bodyIssueCount >= MAX_BODY_ISSUES) {
            issues.add(FormatIssue.warn(
                    "body", "正文问题较多，仅显示前 " + MAX_BODY_ISSUES + " 条", "body", "先修复前部问题后重新校验"));
        }
    }

    private boolean isHeading(String trimmed) {
        return LEVEL_ONE.matcher(trimmed).matches()
                || LEVEL_TWO.matcher(trimmed).matches()
                || LEVEL_THREE.matcher(trimmed).matches();
    }

    private boolean hasFirstLineIndent(String line) {
        return line.startsWith("　　") || line.startsWith("    ");
    }

    private int chineseNumber(String raw) {
        int result = 0;
        int current = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '十') {
                result += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (ch == '百') {
                result += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else {
                current = digit(ch);
            }
        }
        return result + current;
    }

    private String chineseNumber(int number) {
        String[] digits = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        if (number <= 10) {
            return digits[number];
        }
        if (number < 20) {
            return "十" + digits[number - 10];
        }
        if (number < 100) {
            int tens = number / 10;
            int ones = number % 10;
            return digits[tens] + "十" + (ones == 0 ? "" : digits[ones]);
        }
        return String.valueOf(number);
    }

    private int digit(char ch) {
        return switch (ch) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    private String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
