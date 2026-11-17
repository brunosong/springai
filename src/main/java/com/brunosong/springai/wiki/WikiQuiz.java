package com.brunosong.springai.wiki;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 퀴즈 출제와 채점. 에이전트가 아니라 워크플로다 (순서를 코드가 정한다).
 */
@Slf4j
public class WikiQuiz {

    /** 문제 하나. answer 는 채점 기준(정답) — 사용자에게는 보여주지 않는다 */
    public record Quiz(String id, String doc, String question, String answer, String source) {}

    /** LLM 이 만든 문제 */
    public record GeneratedQuiz(String question, String answer) {}

    private final WikiTools wiki;
    private final Map<String, Quiz> issued = new ConcurrentHashMap<>();   // 낸 문제 보관 (id → 문제)

    public WikiQuiz(WikiTools wiki) {
        this.wiki = wiki;
    }

    /** ① 문서에 이미 있는 확인 문제(> [!question]-)를 코드로 꺼낸다. LLM 호출 0번 */
    public List<Quiz> parseQuestions(String doc) {
        Path file = wiki.resolve(doc);
        if (file == null) throw new IllegalArgumentException("문서 없음: " + doc);
        List<String> lines = wiki.readLines(file);
        List<Quiz> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("> [!question]-")) continue;
            String question = line.substring("> [!question]-".length()).strip();
            StringBuilder answer = new StringBuilder();
            int j = i + 1;
            while (j < lines.size() && lines.get(j).startsWith(">")) {
                answer.append(lines.get(j).substring(1).strip()).append('\n');
                j++;
            }
            result.add(new Quiz(null, wiki.docName(file), question, answer.toString().strip(), "문서의 확인 문제"));
        }
        return result;
    }

    /** 문서의 확인 문제 중 하나를 골라 낸다 */
    public Quiz pick(String doc) {
        List<Quiz> all = parseQuestions(doc);
        if (all.isEmpty()) throw new IllegalArgumentException(doc + " 에 확인 문제가 없다");
        Quiz q = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        return save(q);
    }

    /** ② 문서의 한 절을 읽고 LLM 이 문제와 정답을 만든다 */
    public Quiz generate(ChatClient client, String doc, String section) {
        String text = wiki.readSection(doc, section);
        GeneratedQuiz g = client.prompt()
                .system("""
                        너는 개발 공부를 돕는 출제자다.
                        주어진 본문만 보고 서술형 확인 문제 1개와 모범 답안을 만든다.
                        - 본문에 답이 있는 문제만 낸다. 본문에 없는 내용은 묻지 않는다.
                        - 외우기보다 이해를 묻는다 ("왜", "무엇이 다른가").
                        - 모범 답안은 문제가 묻는 것에만 답한다. 2~3문장, 채점에 쓸 핵심 키워드를 포함한다.
                        """)
                .user("본문:\n" + text)
                .advisors(StructuredOutputValidationAdvisor.builder()   // 12장: JSON이 틀리면 다시 묻기
                        .outputType(GeneratedQuiz.class)
                        .maxRepeatAttempts(2)
                        .build())
                .call()
                .entity(GeneratedQuiz.class);
        log.info("[quiz] 생성: {}", g.question());
        return save(new Quiz(null, doc, g.question(), g.answer(), "LLM 생성 (" + section + ")"));
    }

    public Quiz get(String quizId) {
        return issued.get(quizId);
    }

    private Quiz save(Quiz q) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Quiz saved = new Quiz(id, q.doc(), q.question(), q.answer(), q.source());
        issued.put(id, saved);
        return saved;
    }

    // 채점(grade)은 실습 5에서 추가한다

}
