package com.brunosong.springai.common.config;

import com.brunosong.springai.advisor.MaskingAdvisor;
import com.brunosong.springai.advisor.TimingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiClientConfig {

    @Bean
    public ChatClient geminiChatClient(GoogleGenAiChatModel googleGenAiChatModel) {
        return ChatClient.builder(googleGenAiChatModel)
                .defaultSystem("항상 한국말로 답해")
                .defaultOptions(ChatOptions.builder().temperature(0.0))
                .build();
    }

    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel)
                .defaultSystem("항상 한국말로 답해")
                .defaultAdvisors(new MaskingAdvisor(), new TimingAdvisor())
                .build();
    }
}
