package com.brunosong.system.digitaltwin.chat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class TwinChatController {

    private final TwinChatService chatService;

    public TwinChatController(TwinChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @GetMapping("/conversation/{sessionId}")
    public Map<String, Object> conversation(@PathVariable String sessionId) {
        List<ChatMessage> messages = chatService.conversation(sessionId);
        return Map.of("session_id", sessionId, "messages", messages);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }
}
