package com.brunosong.springai.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChainWorkflow {

    public enum Type {
        FEATURE, FIX, REFACTOR, DOCS, CHORE
    }

    public record Change(Type type, String summary) {}

    public record Changes(List<Change> changes) {}

    private final ChatClient chatClient;

    public ChainWorkflow(@Qualifier("ollamaChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Map<String, Object> run(String commits) {
        // 1. 분류 : 커밋 메시지 -> 구조화된 변경 목록
        Changes changes = chatClient.prompt().system("""
                        커밋 메시지 목록을 읽고 변경 사항을 하나씩 분류한다.
                        - type : FEATURE(새 기능), FIX(버그 수정), REFACTOR(구조 개선), DOCS(문서), CHORE(빌드/설정)
                        - summary : 서비스 사용자가 이해할 수 있는 한국어 한 문장
                        """)
                .user(commits)
                .call().entity(Changes.class);
        log.info("[chain 1 분류] {}", changes.changes());

        // 2. 게이트 : 코드로 검사한다. 사용자에게 알릴 변경만 남긴다.
        List<Change> visible = changes.changes().stream()
                .filter(c -> c.type == Type.FEATURE || c.type() == Type.FIX).toList();
        log.info("[chain 2 게이트] {}개 중 {}개 통과", changes.changes().size(), visible.size());

        if (visible.isEmpty()) {
            return Map.of("stoppedAt", "2 게이트", "reason", "사용자에게 알릴 변경이 없음",
                    "changes", changes.changes());
        }

        // 3 작성: 변경 목록 → 릴리스 노트 초안
        String draft = chatClient.prompt()
                .system("""
                        릴리스 노트를 마크다운으로 쓴다.
                        - '## 새 기능', '## 버그 수정' 두 절로 나눈다
                        - 항목은 불릿으로 쓴다
                        """)
                .user(visible.toString())
                .call()
                .content();
        log.info("[chain ③ 작성]\n{}", draft);


        // 4 다듬기: 초안 → 최종본
        String polished = chatClient.prompt()
                .system("""
                        릴리스 노트를 다듬는다.
                        - 모든 항목을 '~했습니다' 체로 통일한다
                        - 각 항목은 한 줄
                        - 내용을 더하거나 빼지 않는다
                        - 다듬은 문서만 출력한다
                        """)
                .user(draft)
                .call()
                .content();
        log.info("[chain ④ 다듬기]\n{}", polished);

        return Map.of("changes", changes.changes(), "draft", draft, "final", polished);
    }

}
