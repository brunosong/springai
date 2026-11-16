package com.brunosong.springai.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class OrchestratorWorkflow {

    public record Section(String title, String instruction) {}

    public record Outline(String analysis, List<Section> sections) {}

    private final ChatClient chatClient;

    public OrchestratorWorkflow(@Qualifier("ollamaChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Map<String, Object> run(String topic) {

        // 1. 오케스트레이터: 일을 몇 개로, 어떻게 나눌지 모델이 정한다.
        Outline outline = chatClient.prompt().system("""
                        너는 학습 노트 편집장이다. 주제를 보고 노트를 몇 개의 절로 나눌지 정한다.
                        - 절은 2-4개. 주제가 단순하면 적게, 넓으면 많게
                        - title : 절 제목
                        - instruction: 그 절을 쓸 작성자에게 줄 구체적인 지시
                        - analysis : 왜 이렇게 나눴는지 한두 문장
                        """)
                .user(topic)
                .call()
                .entity(Outline.class);
        log.info("[orchestrator] {}개로 나눔: {}", outline.sections().size(), outline.analysis());

        // 2. 워커 : 절마다 한 명씩 동시에 쓴다.
        List<String> parts;
        try(ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> futures = outline.sections().stream()
                    .map(s -> CompletableFuture.supplyAsync(() -> write(topic, s), executor))
                    .toList();

            parts = futures.stream().map(CompletableFuture::join).toList();
        }

        // 3. 종합 : 조각을 한 문서로
        String note = chatClient.prompt().system("""
                        여러 작성자가 쓴 절을 하나의 학습 노트로 합친다.
                        - 맨 앞에 세 줄 요약
                        - 절 순서는 유지하고, 겹치는 설명은 한 번만
                        - 합친 문서만 출력한다
                        """)
                .user(String.join("\n\n", parts))
                .call()
                .content();

        return Map.of("analysis", outline.analysis(), "sections", outline.sections(), "note", note);
    }

    private String write(String topic, Section section) {
        log.info("[worker] '{}' 작성 시작", section.title());
        return chatClient.prompt()
                .system("""
                        너는 학습 노트 작성자다. 맡은 절 하나만 쓴다.
                        - '## 절 제목'으로 시작하는 마크다운
                        - 400자 이내
                        """)
                .user("전체 주제: %s\n절 제목: %s\n지시: %s"
                        .formatted(topic, section.title(), section.instruction()))
                .call()
                .content();
    }
}
