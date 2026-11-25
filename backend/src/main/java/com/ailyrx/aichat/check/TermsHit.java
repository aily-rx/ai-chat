package com.ailyrx.aichat.check;

/**
 * 术语/错别字命中项。position 为从 0 开始的字符偏移，line/column 均从 1 开始。
 */
public record TermsHit(
        String term,
        String suggestion,
        String category,
        int position,
        int line,
        int column,
        String context) {
}
