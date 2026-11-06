package com.brunosong.springai.basic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/basic")
public class BasicController {

    private final ChatClient gemini;
    private final ChatClient ollama;

    public BasicController(@Qualifier("geminiChatClient") ChatClient gemini,
                           @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping("/gemini")
    public String gemini(@RequestParam String q) {

        ChatResponse chatResponse = gemini.prompt()
                .system("너는 초등학생에게 설명하는 선생님이야. 3줄 이내로 답해.")
                .user(q)
                .options(ChatOptions.builder().temperature(0.0))
                .call()
                .chatResponse();

        // 입력 출력 토큰 수
        Usage usage = chatResponse.getMetadata().getUsage();
        log.info("[gemini] 토큰 사용량 - 입력: {}, 출력: {}, 전체: {}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());

        // 답변 텍스트
        String text = chatResponse.getResult().getOutput().getText();

        return text;
    }

    @GetMapping("/ollama")
    public String ollama(@RequestParam String q) {
        return ollama.prompt()
                .user(q)
                .call()
                .content();
    }
}
