package com.brunosong.springai.review;

import com.brunosong.springai.agent.LoopLogAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;

@Slf4j
public class CodeReviewer {

    static final String SYSTEM = """
            너는 꼼꼼한 자바 코드 리뷰어다. 이번 변경을 리뷰한다.
            - 순서 : listChangedFiles 로 바뀐 파일 보기 -> readDiff 로 파일마다 바뀐 부분 보기
                -> 필요하면 readFile 로 앞뒤 맥락 보기, searchCode 로 다른 곳에서 쓰는지 확인
            - 바뀐 줄(+ 표시)만 리뷰한다. 바뀌지 않은 코드의 문제는 쓰지 않는다.
            - 지적마다 : 파일 경로, 줄 번호(diff 앞에 붙은 번호), 그 줄의 코드 그대로, 심각도(HIGH/MEDIUM/LOW), 이유
            - 문제가 없으면 없다고 쓴다. 억지로 만들지 않는다.
            """;

    private final int maxToolCalls;

    public CodeReviewer(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    /** ① 조사: 에이전트가 도구로 코드를 보며 리뷰 메모를 쓴다 */
    public String investigate(ChatClient client, ReviewTools tools, String focus) {
        ToolCallingManager manager = ToolCallingManager.builder()
                .maxTotalToolCalls(maxToolCalls)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();

        return client.prompt()
                .system(focus == null ? SYSTEM : SYSTEM + "\n - 이번에는 " + focus)
                .user("리뷰 범위: " + tools.range())
                .advisors(new LoopLogAdvisor(manager))
                .tools(tools)
                .call().content();
    }
}
