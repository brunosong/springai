package com.brunosong.springai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

@Slf4j
public class OrderLogAdvisor implements CallAdvisor {

    private final String name;
    private final int order;

    public OrderLogAdvisor(String name, int order) {
        this.name = name;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

        log.info("before {} context={}", name, request.context().keySet());

        ChatClientResponse response = chain.nextCall(
                request.mutate().context(name, true).build()
        ); // context에 내 이름을 남긴다

        log.info("after  {} context={}", name, response.context().keySet());

        return response;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
