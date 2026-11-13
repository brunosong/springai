package com.brunosong.springai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

@Slf4j
public class LoopLogAdvisor extends ToolCallingAdvisor {

    public LoopLogAdvisor(ToolCallingManager toolCallingManager) {
        // (실행 매니저, 도구 호출인지 판별기, 순서, 대화 기록 유지)
        super(toolCallingManager, DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, DEFAULT_ORDER, true);
    }

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        log.info("[agent] =========== 루프 시작 ==========");
        return super.doInitializeLoop(request, chain);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        if (output.hasToolCalls()) {
            output.getToolCalls().forEach(tc ->
                    log.info("[agent] 행동 → {}({})", tc.name(), tc.arguments()));
        } else {
            log.info("[agent] 최종 답 → {}", output.getText());
        }

        return super.doAfterCall(response, chain);
    }

    @Override
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse response, CallAdvisorChain chain) {
        log.info("[agent] ===== 루프 끝 =====");
        return super.doFinalizeLoop(response, chain);
    }
}
