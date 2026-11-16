package com.brunosong.springai.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ParallelWorkflow {

    record Reviewer(String name, String prompt) {}

    private static final List<Reviewer> REVIEWERS = List.of(
            new Reviewer("보안", """
                    너는 보안 리뷰어다. 보안 문제만 본다.
                    (SQL 인젝션, 하드코딩된 비밀번호, 입력 검증)
                    문제를 최대 3개, 한 줄씩 쓴다."""),
            new Reviewer("성능", """
                    너는 성능 리뷰어다. 성능 문제만 본다.
                    (반복문 안의 쿼리, 자원 미해제, 불필요한 작업)
                    문제를 최대 3개, 한 줄씩 쓴다."""),
            new Reviewer("가독성", """
                    너는 가독성 리뷰어다. 읽기 쉬운지만 본다.
                    (이름, 메서드 길이, 중복)
                    문제를 최대 3개, 한 줄씩 쓴다."""));

    private final ChatClient chatClient;

    public ParallelWorkflow(@Qualifier("ollamaChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Map<String, Object> run(String code, boolean parallel) {
        long start = System.currentTimeMillis();
        Map<String,String> reviews = new LinkedHashMap<>();

        if (parallel) {
            // 1. 나누기 : 리뷰어 마다 가상 스레드 하나씩 동시에 출발
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();

                for (Reviewer r : REVIEWERS) {
                    futures.put(r.name(), CompletableFuture.supplyAsync(() -> review(r,code), executor));
                }

                // 2. 기다리기 : 모두 끝날때까지
                futures.forEach((name, f) -> reviews.put(name, f.join()));
            }
        } else {
            // 비교용: 하나씩 차례로
            for (Reviewer r : REVIEWERS) {
                reviews.put(r.name(), review(r, code));
            }
        }

        long reviewMs = System.currentTimeMillis() - start;
        log.info("[parallel] 리뷰 {}개, {} 방식, {}ms", reviews.size(), parallel ? "병렬" : "순차", reviewMs);

        // 3. 모으기
        String summary = chatClient.prompt().system("""
                        여러 리뷰어의 결과를 하나로 정리한다.
                        - 심각한 것부터 순서대로 최대 5개
                        - 각 줄 앞에 [보안][성능][가독성] 표시
                        """)
                .user(reviews.toString())
                .call().content();


        return Map.of("mode", parallel ? "병렬" : "순차", "reviewMs", reviewMs,
                "reviews", reviews, "summary", summary);
    }

    private String review(Reviewer r, String code) {
        log.info("[parallel] {} 리뷰 시작 ({})", r.name(), Thread.currentThread());
        String result = chatClient.prompt()
                .system(r.prompt())
                .user(code)
                .call()
                .content();
        log.info("[parallel] {} 리뷰 끝", r.name());
        return result;
    }
}
