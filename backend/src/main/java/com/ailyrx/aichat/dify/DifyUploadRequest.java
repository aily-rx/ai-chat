package com.ailyrx.aichat.dify;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DifyUploadRequest(
        @JsonProperty("indexing_technique") String indexingTechnique,
        @JsonProperty("doc_form") String docForm,
        @JsonProperty("doc_language") String docLanguage,
        @JsonProperty("process_rule") DifyProcessRule processRule) {

    public static DifyUploadRequest text(String docLanguage, DifyProcessRule processRule) {
        return new DifyUploadRequest("high_quality", "text_model", docLanguage, processRule);
    }
}
