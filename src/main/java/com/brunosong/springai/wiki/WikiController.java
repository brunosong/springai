package com.brunosong.springai.wiki;

import com.brunosong.springai.agent.LoopLogAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/wiki")
public class WikiController {

    private static final String SYSTEM = """
            너는 사용자의 개발 위키를 읽고 답하는 학습 도우미다.
            - 답은 반드시 위키 내용으로만 한다. 네가 원래 아는 지식으로 채우지 않는다.
            - 순서: searchWiki 로 문서 찾기 → listSections 로 목차 보기 → readSection 으로 필요한 절만 읽기
            - 문서를 통째로 읽으려 하지 말고, 필요한 절만 골라 읽는다.
            - 검색어는 질문에 나온 단어를 그대로 먼저 쓴다. 한글 질문이면 한글로 찾는다 (번역하지 않는다).
            - 결과가 없거나 질문과 관계없어 보이면, 다른 표현(한글↔영어, 더 짧은 단어)으로 다시 찾는다.
            - 문서 이름은 searchWiki 결과에 나온 이름만 쓴다. 이름을 지어내지 않는다.
            - 위키에서 찾지 못하면 "위키에 없다"고 말한다. 지어내지 않는다.
            - 답 끝에 근거를 '출처: [[문서 이름]] > 절 제목' 형식으로 단다.
            """;

    private final ChatClient gemini;
    private final ChatClient ollama;
    private final Path root;
    private final WikiQuiz quiz;

    public WikiController(@Qualifier("geminiChatClient") ChatClient gemini,
                          @Qualifier("ollamaChatClient") ChatClient ollama,
                          @Value("${wiki.root}") String wikiRoot) {
        this.gemini = gemini;
        this.ollama = ollama;
        this.root = Path.of(wikiRoot);
        this.quiz = new WikiQuiz(new WikiTools(root));
    }

    private ChatClient client(String model) {
        return model.equals("gemini") ? gemini : ollama;
    }

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam String q,
                      @RequestParam(defaultValue = "ollama") String model) {
        ToolCallingManager manager = ToolCallingManager.builder()
                .maxTotalToolCalls(8)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
        WikiTools tools = new WikiTools(root);      // 요청마다 새로 → 읽은 기록이 섞이지 않는다
        String answer = client(model).prompt()
                .system(SYSTEM)
                .user(q)
                .tools(tools)
                .advisors(new LoopLogAdvisor(manager))
                .call()
                .content();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("read", tools.readLog());
        result.put("citation", checkCitation(answer, tools.readLog()));
        return result;
    }

    /** 절을 읽고 LLM 이 문제 생성 */
    @GetMapping("/quiz/generate")
    public Map<String, Object> generate(@RequestParam String doc,
                                        @RequestParam String section,
                                        @RequestParam(defaultValue = "ollama") String model) {
        return show(quiz.generate(client(model), doc, section));
    }

    /** 문제를 보여줄 때 정답은 숨긴다 */
    private Map<String, Object> show(WikiQuiz.Quiz q) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", q.id());
        result.put("doc", q.doc());
        result.put("source", q.source());
        result.put("question", q.question());
        return result;
    }

    /** 문서의 확인 문제를 코드로 꺼내 출제 (LLM 0번) */
    @GetMapping("/quiz")
    public Map<String, Object> quiz(@RequestParam String doc) {
        return show(quiz.pick(doc));
    }

    /** 답에 단 출처가 실제로 읽은 절인지 코드로 확인 */
    private static final Pattern CITATION = Pattern.compile("\\[\\[(.+?)]]\\s*>\\s*(.+)");

    private String checkCitation(String answer, List<String> read) {
        Matcher m = CITATION.matcher(answer);
        if (!m.find()) {
            return answer.contains("위키에 없") ? "출처 없음 (위키에 없다고 답함)" : "⚠️ 출처를 달지 않음";
        }
        String doc = m.group(1).strip();
        String section = m.group(2).replaceAll("[*`]", "").strip();    // 굵게(**)·코드(`) 표시 제거
        boolean ok = read.stream().anyMatch(r ->
                r.startsWith(doc + " > ") && r.replaceAll("[*`]", "").contains(section));
        return ok ? "✅ 실제로 읽은 절" : "⚠️ 읽지 않은 절을 출처로 댐: " + doc + " > " + section;
    }
}
