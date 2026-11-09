package com.brunosong.springai.structured;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/structured")
public class StructuredController {

    private final ChatClient gemini;
    private final ChatClient ollama;

    public StructuredController(@Qualifier("geminiChatClient") ChatClient gemini,
                                @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping("/movie")
    public Movie movie(@RequestParam String genre) {
        return gemini.prompt()
                .user(u -> u.text("{genre} 장르 영화 하나 추천해줘.").param("genre", genre))
                .call()
                .entity(Movie.class);
    }

    // responseEntity 사용
    @GetMapping("/movie2")
    public Movie movie2(@RequestParam String genre) {

        ResponseEntity<ChatResponse, Movie> res = gemini.prompt()
                .user(u -> u.text("{genre} 장르 영화 하나 추천해줘.").param("genre", genre))
                .call()
                .responseEntity(Movie.class);

        Movie movie = res.entity();
        Usage usage = res.response().getMetadata().getUsage();

        // 스키마 검증 + 실패 시 에러 내용을 알려 주고 재시도 (기본 3번)
        //.entity(Movie.class, spec -> spec.validateSchema())

        // 프롬프트가 아니라 AI API 차원에서 JSON 형식을 강제
        //.entity(Movie.class, spec -> spec.useProviderStructuredOutput())

        // 둘 다 사용
        //.entity(Movie.class, spec -> spec.useProviderStructuredOutput().validateSchema())

        return movie;
    }

    @GetMapping("/ollama/movie")
    public List<Movie> ollamaMovie(@RequestParam String genre) {
        return ollama.prompt()
                .user(u -> u.text("{genre} 장르 영화 5개 추천해줘.").param("genre", genre))
                .call()
                .entity(new ParameterizedTypeReference<List<Movie>>() {});
    }

    @GetMapping("/ollama/review")
    public String review(@RequestParam String text) {

        ReviewAnalysis result = ollama.prompt()
                .system("너는 쇼핑몰 리뷰 분석가야. 리뷰를 읽고 감정을 분석해.")
                .user(u -> u.text("리뷰: {text}").param("text", text))
                .options(ChatOptions.builder().temperature(0.0))    // 분류는 일관성이 중요
                .call()
                .entity(ReviewAnalysis.class);

        return switch (result.sentiment()) {
            case POSITIVE -> "감사 쿠폰 발송";
            case NEGATIVE -> "CS 담당자 배정 (불만 항목 : " + negativeAspects(result) + ")";
            case NEUTRAL -> "처리 없음";
        };
    }

    private String negativeAspects(ReviewAnalysis result) {
        return result.aspects().stream()
                .filter(a -> a.sentiment() == Sentiment.NEGATIVE)
                .map(Aspect::name)
                .collect(Collectors.joining(", "));
    }
}
