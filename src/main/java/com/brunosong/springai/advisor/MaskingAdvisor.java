package com.brunosong.springai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;

@Slf4j
public class MaskingAdvisor implements BaseAdvisor {

    private static final String PHONE = "01[0-9]-?[0-9]{3,4}-?[0-9]{4}";

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {

        Prompt masked = request.prompt().augmentUserMessage(user ->
                user.mutate().text(user.getText().replaceAll(PHONE, "***-****-****"))
                        .build());

        log.info("[mask] {}", masked.getUserMessage().getText());
        return request.mutate().prompt(masked).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;    // 응답은 그대로
    }

    @Override
    public int getOrder() {
        return -100;      // order 0인 Advisor들보다 먼저
        // ⚠️ 메모리 Advisor(HIGHEST_PRECEDENCE + 200)보다는 뒤다. 메모리에 저장되기 전에 가리려면
        //    Ordered.HIGHEST_PRECEDENCE + 100 처럼 더 작은 값을 쓴다.
    }
}
