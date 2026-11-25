package com.ailyrx.aichat.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 规范用语/错别字检查。内置小词表，并通过 app.terms.overrides 允许本地扩展或覆盖。
 */
@Service
public class TermsChecker {

    private static final int CONTEXT_RADIUS = 12;

    private final Map<String, TermRule> rules;

    public TermsChecker(TermsProperties properties) {
        Map<String, TermRule> merged = new LinkedHashMap<>();
        merged.put("做为", new TermRule("作为", "错别字"));
        merged.put("按排", new TermRule("安排", "错别字"));
        merged.put("布署", new TermRule("部署", "错别字"));
        merged.put("查觉", new TermRule("察觉", "错别字"));
        merged.put("竟见", new TermRule("意见", "错别字"));
        merged.put("即然", new TermRule("既然", "错别字"));
        merged.put("截止目前", new TermRule("截至目前", "规范用语"));
        merged.put("截止到", new TermRule("截至", "规范用语"));
        merged.put("一把手", new TermRule("主要负责人", "规范用语"));
        merged.put("二把手", new TermRule("分管负责人", "规范用语"));
        merged.put("敲定", new TermRule("确定", "规范用语"));
        merged.put("搞定", new TermRule("完成", "规范用语"));
        properties.overrides().forEach((term, suggestion) -> {
            if (term != null && !term.isBlank() && suggestion != null && !suggestion.isBlank()) {
                merged.put(term.strip(), new TermRule(suggestion.strip(), "自定义"));
            }
        });
        this.rules = Map.copyOf(merged);
    }

    public TermsCheckResult check(String text) {
        if (text == null || text.isEmpty()) {
            return new TermsCheckResult(true, 0, List.of());
        }
        List<TermsHit> hits = new ArrayList<>();
        for (Map.Entry<String, TermRule> entry : rules.entrySet()) {
            String term = entry.getKey();
            int from = 0;
            while (from <= text.length() - term.length()) {
                int position = text.indexOf(term, from);
                if (position < 0) {
                    break;
                }
                hits.add(new TermsHit(
                        term,
                        entry.getValue().suggestion(),
                        entry.getValue().category(),
                        position,
                        lineOf(text, position),
                        columnOf(text, position),
                        contextOf(text, position, term.length())));
                from = position + term.length();
            }
        }
        hits.sort((left, right) -> Integer.compare(left.position(), right.position()));
        return new TermsCheckResult(hits.isEmpty(), hits.size(), List.copyOf(hits));
    }

    private int lineOf(String text, int position) {
        int line = 1;
        for (int i = 0; i < position && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int columnOf(String text, int position) {
        int lastBreak = text.lastIndexOf('\n', Math.max(0, position - 1));
        return position - lastBreak;
    }

    private String contextOf(String text, int position, int termLength) {
        int start = Math.max(0, position - CONTEXT_RADIUS);
        int end = Math.min(text.length(), position + termLength + CONTEXT_RADIUS);
        String context = text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
        return (start > 0 ? "…" : "") + context + (end < text.length() ? "…" : "");
    }

    private record TermRule(String suggestion, String category) {
    }
}
