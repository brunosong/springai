package com.brunosong.system.digitaltwin.chat;

import com.brunosong.system.digitaltwin.persona.PersonaPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TwinChatService {

    /** 프롬프트에 실어 보낼 직전 대화 개수(user/assistant 합산). */
    private static final int HISTORY_LIMIT = 50;

    private final ChatClient chatClient;
    private final PersonaPrompt personaPrompt;
    private final ConversationStore store;

    public TwinChatService(ChatClient.Builder chatClientBuilder, PersonaPrompt personaPrompt, ConversationStore store) {
        this.chatClient = chatClientBuilder.build();
        this.personaPrompt = personaPrompt;
        this.store = store;
    }

    public ChatResponse chat(ChatRequest request) {
        String sessionId = newSessionIfBlank(request.sessionId());
        List<ChatMessage> conversation = store.load(sessionId);

        String answer = ask(conversation, request.message());

        conversation.add(new ChatMessage("user", request.message(), LocalDateTime.now().toString()));
        conversation.add(new ChatMessage("assistant", answer, LocalDateTime.now().toString()));
        store.save(sessionId, conversation);

        return new ChatResponse(answer, sessionId);
    }

    public List<ChatMessage> conversation(String sessionId) {
        return store.load(sessionId);
    }

    private String ask(List<ChatMessage> conversation, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(personaPrompt.render()));
        for (ChatMessage past : recent(conversation)) {
            messages.add("user".equals(past.role())
                    ? new UserMessage(past.content())
                    : new AssistantMessage(past.content()));
        }
        messages.add(new UserMessage(userMessage));

        return chatClient.prompt(new Prompt(messages)).call().content();
    }

    private List<ChatMessage> recent(List<ChatMessage> conversation) {
        int from = Math.max(0, conversation.size() - HISTORY_LIMIT);
        return conversation.subList(from, conversation.size());
    }

    private String newSessionIfBlank(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
    }
}
