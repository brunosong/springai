package com.brunosong.springai.structured;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record ReviewAnalysis(
        @JsonPropertyDescription("리뷰 전체의 감정")
        Sentiment sentiment,

        @JsonPropertyDescription("감정 강도. 0.0(매우 부정) ~ 1.0(매우 긍정)")
        double score,

        @JsonPropertyDescription("리뷰의 핵심 키워드. 3개 이내")
        List<String> keywords,

        @JsonPropertyDescription("항목별 감정. 리뷰에 언급된 항목만")
        List<Aspect> aspects
) {}
