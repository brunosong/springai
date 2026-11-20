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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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

    /** 실습 3: 지적 목록으로 받고, 코드로 검증 */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(defaultValue = "HEAD~1") String from,
                                      @RequestParam(defaultValue = "HEAD") String to,
                                      @RequestParam(defaultValue = "ollama") String model) {
        ReviewTools tools = new ReviewTools(repo, from, to);
        long start = System.currentTimeMillis();
        String notes = reviewer.investigate(client(model), tools, null);
        List<FindingChecker.Finding> findings = reviewer.toFindings(client(model), notes);
        List<FindingChecker.Checked> checked = new FindingChecker(tools).check(findings);
        return result(tools, start, checked, Map.of("notes", notes));
    }

    private Map<String, Object> result(ReviewTools tools, long start, List<FindingChecker.Checked> checked, Map<String, Object> extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", tools.range());
        result.put("ms", System.currentTimeMillis() - start);
        result.put("count", checked.stream()
                .collect(Collectors.groupingBy(c -> c.status().name(), TreeMap::new, Collectors.counting())));
        result.put("findings", checked);
        result.putAll(extra);
        return result;
    }

    /** 실습 4: 관점별 리뷰어를 동시에 돌리고, 코드로 합친다 */
    @GetMapping("/parallel")
    public Map<String, Object> parallel(@RequestParam(defaultValue = "HEAD~1") String from,
                                        @RequestParam(defaultValue = "HEAD") String to,
                                        @RequestParam(defaultValue = "ollama") String model) {
        ReviewTools.checkRef(from);
        ReviewTools.checkRef(to);
        long start = System.currentTimeMillis();
        ChatClient client = client(model);

        Map<String, List<FindingChecker.Finding>> byPerspective = new LinkedHashMap<>();
        Map<String, String> errors = new ConcurrentHashMap<>();      // 실패한 리뷰어 → 이유
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<List<FindingChecker.Finding>>> futures = new LinkedHashMap<>();
            for (CodeReviewer.Perspective p : CodeReviewer.PERSPECTIVES) {
                futures.put(p.name(), CompletableFuture.supplyAsync(() -> {
                    ReviewTools own = new ReviewTools(repo, from, to);   // 리뷰어마다 따로
                    String notes = reviewer.investigate(client, own, p.focus());
                    return reviewer.toFindings(client, notes);
                }, executor).exceptionally(e -> {
                    // 리뷰어 하나가 실패해도(예: 429 한도 초과) 나머지 결과는 살린다
                    log.warn("[review] {} 리뷰어 실패: {}", p.name(), e.getMessage());
                    errors.put(p.name(), rootMessage(e));
                    return List.of();
                }));
            }
            futures.forEach((name, f) -> byPerspective.put(name, f.join()));
        }

        List<FindingChecker.Finding> all = new ArrayList<>();
        byPerspective.values().forEach(all::addAll);
        List<FindingChecker.Finding> merged = CodeReviewer.merge(all);

        ReviewTools tools = new ReviewTools(repo, from, to);
        List<FindingChecker.Checked> checked = new FindingChecker(tools).check(merged);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("perPerspective", byPerspective.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size(),
                        (a, b) -> a, LinkedHashMap::new)));
        extra.put("beforeMerge", all.size());
        if (!errors.isEmpty()) extra.put("errors", errors);
        return result(tools, start, checked, extra);
    }

    /** 예외 사슬의 맨 안쪽 메시지 첫 줄 (CompletionException 이 감싸고 있다) */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = String.valueOf(t.getMessage());
        return t.getClass().getSimpleName() + ": " + msg.lines().findFirst().orElse("");
    }
}
