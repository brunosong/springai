package com.brunosong.springai.streaming;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/streaming")
@Slf4j
public class StreamingController {

    private final ChatClient gemini;
    private final ChatClient ollama;

    public StreamingController(@Qualifier("geminiChatClient") ChatClient gemini,
                            @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String q) {
        return ollama.prompt()
                .user(q)
                .stream()      // ← call() 대신
                .content();    // ← Flux<String>
    }

    @GetMapping(value = "/chat/log", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithLog(@RequestParam String q) {
        return ollama.prompt()
                .user(q)
                .stream()
                .content()
                .doOnNext(chunk -> log.info("chunk = [{}]", chunk))
                .doOnComplete(() -> log.info("완료"));
    }

    @GetMapping(value = "/chat/usage", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithUsage(@RequestParam String q) {
        return ollama.prompt()
                .user(q)
                .stream()
                .chatResponse()
                .doOnNext(r -> log.info("usage : {}", r.getMetadata().getUsage()))
                .map(r -> r.getResult() == null ? "" : r.getResult().getOutput().getText());
    }

    @GetMapping(value = "/chat/collect")
    public Mono<String> chatWithCollect(@RequestParam String q) {
        return ollama.prompt()
                .user(q)
                .stream()
                .content()
                .doOnCancel(() -> log.info("클라이언트가 연결을 끊음"))
                .collectList()
                .map(chunks -> String.join("", chunks));
    }

}
