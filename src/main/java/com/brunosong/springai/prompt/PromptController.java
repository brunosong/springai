package com.brunosong.springai.prompt;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prompt")
public class PromptController {

    private final ChatClient gemini;
    private final ChatClient ollama;

    @Value("classpath:prompts/translate.st")
    private Resource translatePrompt;

    public PromptController(@Qualifier("geminiChatClient") ChatClient gemini,
                            @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping("/explain")
    public String explain(@RequestParam String topic,
                          @RequestParam(defaultValue = "3") int line) {
        return gemini.prompt()
                .user(u -> u.text("{topic}에 대해 {lines}줄로 설명해줘")
                        .param("topic", topic)
                        .param("lines", line))
                .call()
                .content();
    }

    @GetMapping("/role")
    public String role(@RequestParam String role,
                       @RequestParam String q) {
        return gemini.prompt()
                .system(s -> s.text("너는 {role}이야. 그 말투로 답해").param("role", role))
                .user(q)
                .call()
                .content();
    }

    @GetMapping("/translate")
    public String translate(@RequestParam String language, @RequestParam String text) {
        return ollama.prompt()
                .user(u -> u.text(translatePrompt)
                        .param("language",language)
                        .param("text", text)
                )
                .call()
                .content();
    }

    @GetMapping("/message")
    public String message(@RequestParam String text) {
        return ollama.prompt()
                .messages(
                        new UserMessage("사과"),   new AssistantMessage("🍎 사과 - 빨갛고 아삭한 과일"),
                        new UserMessage("바나나"), new AssistantMessage("🍌 바나나 - 노랗고 길쭉한 과일")
                )
                .user(text)
                .call()
                .content();
    }


}
