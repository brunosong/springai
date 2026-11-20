package com.brunosong.springai.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/review")
public class ReviewController {

    private final ChatClient gemini;
    private final ChatClient ollama;
    private final Path repo;
    private final CodeReviewer reviewer = new CodeReviewer(15);

    public ReviewController(@Qualifier("geminiChatClient") ChatClient gemini,
                            @Qualifier("ollamaChatClient") ChatClient ollama,
                            @Value("${review.repo:.}") String repo) {     // 설정이 없으면 앱을 실행한 폴더
        this.gemini = gemini;
        this.ollama = ollama;
        this.repo = Path.of(repo);
    }

    private ChatClient client(String model) {
        return model.equals("gemini") ? gemini : ollama;
    }

    /** 실습 1: 도구만 직접 불러 보기 (LLM 0번) */
    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestParam(defaultValue = "HEAD~1") String from,
                                     @RequestParam(defaultValue = "HEAD") String to,
                                     @RequestParam(required = false) String file) {
        ReviewTools tools = new ReviewTools(repo, from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listChangedFiles", tools.listChangedFiles());
        if (file != null) result.put("readDiff", tools.readDiff(file));
        return result;
    }

    /** 실습 2: 에이전트가 자유 글로 리뷰 */
    @GetMapping
    public Map<String, Object> review(@RequestParam(defaultValue = "HEAD~1") String from,
                                      @RequestParam(defaultValue = "HEAD") String to,
                                      @RequestParam(defaultValue = "ollama") String model) {
        ReviewTools tools = new ReviewTools(repo, from, to);     // 요청마다 새로 (viewed 가 섞이지 않게)
        long start = System.currentTimeMillis();
        String notes = reviewer.investigate(client(model), tools, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", tools.range());
        result.put("ms", System.currentTimeMillis() - start);
        result.put("viewed", tools.viewed());
        result.put("review", notes);
        return result;
    }
}
