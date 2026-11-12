package com.brunosong.springai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

@Slf4j
public class BlockAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String text = request.prompt().getUserMessage().getText();

        if (text.contains("주민번호")) {
            log.info("[block] LLM 호출 안함");
            ChatResponse blocked = ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage("주민번호 처리 할 수 없습니다."))))
                    .build();

            return ChatClientResponse.builder()
                    .chatResponse(blocked)
                    .context(request.context())
                    .build();
        }
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "BlockAdvisor";
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
