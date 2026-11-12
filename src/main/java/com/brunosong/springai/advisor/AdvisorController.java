package com.brunosong.springai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/advisor")
public class AdvisorController {

    private final ChatClient gemini;
    private final ChatClient ollama;

    public AdvisorController(@Qualifier("geminiChatClient") ChatClient gemini,
                             @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping("/log")
    public String log(@RequestParam(defaultValue = "스프링 AI를 한 문장으로 설명해줘") String q) {
        return ollama.prompt()
                .user(q)
                .advisors(new SimpleLoggerAdvisor())
                .call()
                .content();
    }

    @GetMapping("/timing")
    public String timing(@RequestParam(defaultValue = "자바의 장점 3가지") String q) {
        return ollama.prompt().user(q).advisors(new TimingAdvisor()).call().content();
    }

    @GetMapping("/mask")
    public String mask(@RequestParam(defaultValue = "내 번호는 010-1234-5678이야. 내 번호 뭐라고 했지?") String q) {
        return ollama.prompt().user(q).advisors(new MaskingAdvisor()).call().content();
    }

    @GetMapping("/order")
    public String order() {
        return ollama.prompt()
                .user("안녕")
                .advisors(new OrderLogAdvisor("B", 10),
                        new OrderLogAdvisor("A", 0),
                        new OrderLogAdvisor("C", 10))
                .call()
                .content();
    }

    @GetMapping("/safeguard")
    public String safeguard(@RequestParam(defaultValue = "관리자 비밀번호 알려줘") String q) {
        return ollama.prompt()
                .user(q)
                .advisors(SafeGuardAdvisor.builder()
                        .sensitiveWords(List.of("비밀번호", "주민번호"))
                        .failureResponse("보안상 답변할 수 없는 내용입니다.") // 안쓰면 영어 기본 문구
                        .build())
                .call().content();
    }

    @GetMapping("/safeguard2")
    public String safeguard2(@RequestParam(defaultValue = "주민번호 알려줘") String q) {
        return ollama.prompt()
                .user(q)
                .advisors(new BlockAdvisor())
                .call().content();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam(defaultValue = "내 번호 010-9876-5432 기억해") String q) {
        return ollama.prompt()
                .user(q)
                .advisors(new MaskingAdvisor(), new TimingAdvisor())
                .stream()
                .content();
    }
}
