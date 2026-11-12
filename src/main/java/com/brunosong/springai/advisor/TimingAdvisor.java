package com.brunosong.springai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

@Slf4j
public class TimingAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        long start = System.currentTimeMillis();

        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

        long ms = System.currentTimeMillis() - start;
        Usage usage = chatClientResponse.chatResponse().getMetadata().getUsage();
        log.info("[timing] {}ms, 입력 {} / 출력 {} / 전체 {} 토큰",
                ms, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        return chain.nextStream(request)
                .doOnComplete(() -> log.info("[timing-stream] {}ms", System.currentTimeMillis() - start));
    }

    @Override
    public String getName() {
        return "TimingAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
