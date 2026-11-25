package com.ailyrx.aichat.dify;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DifyProcessRule(String mode, Rules rules) {

    public static DifyProcessRule custom(String separator, int maxTokens, int chunkOverlap) {
        return new DifyProcessRule("custom", new Rules(
                List.of(
                        new PreProcessingRule("remove_extra_spaces", true),
                        new PreProcessingRule("remove_urls_emails", false)),
                new Segmentation(separator, maxTokens, chunkOverlap)));
    }

    public record Rules(
            @JsonProperty("pre_processing_rules") List<PreProcessingRule> preProcessingRules,
            Segmentation segmentation) {
    }

    public record PreProcessingRule(String id, boolean enabled) {
    }

    public record Segmentation(
            String separator,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("chunk_overlap") int chunkOverlap) {
    }
}
