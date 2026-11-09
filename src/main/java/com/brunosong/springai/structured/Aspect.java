package com.brunosong.springai.structured;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record Aspect(
        @JsonPropertyDescription("평가 대상 항목. 예 : 배송, 가격, 품질, 포장")
        String name,

        @JsonPropertyDescription("이 항목에 대한 감정")
        Sentiment sentiment
) {
}
