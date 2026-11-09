package com.brunosong.springai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/memory")
@Slf4j
public class MemoryController {

    private final ChatClient gemini;
    private final List<Message> history = new ArrayList<>();

    private final ChatClient memoryClient;
    private final ChatMemory chatMemory;

    public MemoryController(@Qualifier("geminiChatClient") ChatClient gemini, ChatMemory chatMemory) {
        this.gemini = gemini;
        this.chatMemory = chatMemory;
        this.memoryClient = gemini.mutate()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();

    }

    @GetMapping("/none")
    public String none(@RequestParam String q) {
        return gemini.prompt().user(q).call().content();
    }

    @GetMapping("/manual")
    public String manual(@RequestParam String q) {
        String answer = gemini.prompt()
                .messages(history)
                .user(q)
                .call()
                .content();

        history.add(new UserMessage(q));
        history.add(new AssistantMessage(answer));

        return answer;
    }

    @GetMapping("/manual/history")
    public List<String> manualHistory() {
        return history.stream()
                .map(m -> m.getMessageType() + ": " + m.getText())
                .toList();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String id, @RequestParam String q) {
        return memoryClient.prompt()
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
                .call()
                .content();
    }

    @GetMapping("/history")
    public List<String> history(@RequestParam String id) {
        return chatMemory.get(id).stream()
                .map(m -> m.getMessageType() + " : " + m.getText())
                .toList();
    }

    @DeleteMapping("/history")
    public void clear(@RequestParam String id) {
        chatMemory.clear(id);
    }

    @GetMapping("/chat/usage")
    public String chatWithUsage(@RequestParam String id, @RequestParam String q) {
        ChatResponse res = memoryClient.prompt()
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
                .call()
                .chatResponse();

        Usage usage = res.getMetadata().getUsage();
        log.info("[{}] 입력 토큰: {}, 출력 토큰: {}", id, usage.getPromptTokens(), usage.getCompletionTokens());
        return res.getResult().getOutput().getText();
    }
}
