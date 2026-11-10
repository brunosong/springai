package com.brunosong.springai.tool;

import org.checkerframework.checker.units.qual.C;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/tool")
public class ToolController {

    private final ChatClient gemini;
    private final ChatClient ollama;
    private final ChatClient todoClient;

    public ToolController(@Qualifier("geminiChatClient") ChatClient gemini,
                          @Qualifier("ollamaChatClient") ChatClient ollama,
                          TodoTools todoTools) {
        this.gemini = gemini;
        this.ollama = ollama;
//        this.todoClient = gemini.mutate()
//                .defaultSystem("너는 할 일 관리 비서야. 할 일 관련 요청은 반드시 도구를 사용해. 한국말로 답해")
//                .defaultTools(todoTools)
//                .build();

        this.todoClient = ollama.mutate()
                    .defaultSystem("너는 할 일 관리 비서야. 할 일 관련 요청은 반드시 도구를 사용해. 한국말로 답해")
                    .defaultTools(todoTools)
                    .build();
    }

    // 도구 없이
    @GetMapping("/no-tool")
    public String noTool(@RequestParam(defaultValue = "오늘 무슨 요일이고 지금 몇시야?") String q) {
        return gemini.prompt().user(q).call().content();
    }

    // 도구 없이
    @GetMapping("/time")
    public String time(@RequestParam(defaultValue = "오늘 무슨 요일이고 지금 몇시야?") String q) {
        return gemini.prompt().user(q)
                .tools(new DateTimeTools())
                .call().content();
    }

    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "서울 날씨 어때?") String q) {
        return gemini.prompt().user(q)
                .tools(new DateTimeTools(), new WeatherTools())
                .call().content();
    }

    @GetMapping("/multi")
    public String multi(@RequestParam(defaultValue = "지금 몇 시고, 제주 날씨는 어때?") String q) {
        return ollama.prompt()
                .user(q)
                .tools(new DateTimeTools(), new WeatherTools())   // 가변 인자
                .call()
                .content();
    }

    @GetMapping("/todo")
    public String todo(@RequestParam String q) {
        return todoClient.prompt().user(q).call().content();
    }

    @GetMapping("/my-todo")
    public String myTodo(@RequestParam String userId, @RequestParam String q) {
        return todoClient.prompt().user(q)
                .toolContext(Map.of("userId", userId))
                .call()
                .content();
    }

}
