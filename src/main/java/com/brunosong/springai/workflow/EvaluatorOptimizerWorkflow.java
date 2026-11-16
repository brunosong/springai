package com.brunosong.springai.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EvaluatorOptimizerWorkflow {

    public enum Verdict { PASS, NEEDS_IMPROVEMENT }

    public record Evaluation(Verdict verdict, String feedback) {}

    public record Round(int round, Verdict verdict, String feedback) {}

    private static final int MAX_ROUNDS = 3;

    private final ChatClient gemini;
    private final ChatClient ollama;

    public EvaluatorOptimizerWorkflow(@Qualifier("geminiChatClient") ChatClient gemini,
                                      @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    public Map<String, Object> run(String task, String generatorModel, String evaluatorModel) {
        ChatClient generator = generatorModel.equals("gemini") ? gemini : ollama;   // 만드는 쪽
        ChatClient evaluator = evaluatorModel.equals("gemini") ? gemini : ollama;   // 평가하는 쪽

        List<Round> history = new ArrayList<>();
        String code = null;
        String feedback = null;

        for (int round = 1; round <= MAX_ROUNDS; round++) {

            // ① 만들기 (두 번째부터는 이전 코드 + 피드백을 같이 준다)
            String request = (feedback == null)
                    ? "요구사항: " + task
                    : "요구사항: %s\n\n이전 코드:\n%s\n\n리뷰어 피드백:\n%s".formatted(task, code, feedback);
            code = generator.prompt()
                    .system("""
                            너는 Java 개발자다. 요구사항에 맞는 Java 메서드 하나를 쓴다.
                            - 코드 블록 하나만 출력한다
                            - 피드백이 있으면 모두 반영해서 전체 코드를 다시 쓴다
                            """)
                    .user(request)
                    .call()
                    .content();

            // ② 평가
            Evaluation eval = evaluator.prompt()
                    .system("""
                            너는 엄격한 Java 코드 리뷰어다. 아래 기준을 모두 만족할 때만 PASS 한다.
                            1. 요구사항을 빠짐없이 정확하게 구현했다
                            2. null, 빈 문자열 같은 경계 입력을 처리한다
                            3. 변수와 메서드 이름이 의미를 드러낸다
                            하나라도 어긋나면 NEEDS_IMPROVEMENT, feedback에 고칠 점을 구체적으로 쓴다.
                            """)
                    .user("요구사항: %s\n\n코드:\n%s".formatted(task, code))
                    .advisors(StructuredOutputValidationAdvisor.builder()   // JSON이 틀리면 다시 묻기
                            .outputType(Evaluation.class)
                            .maxRepeatAttempts(2)
                            .build())
                    .call()
                    .entity(Evaluation.class);
            log.info("[evaluate] {}회차 → {} : {}", round, eval.verdict(), eval.feedback());
            history.add(new Round(round, eval.verdict(), eval.feedback()));

            // ③ 멈출지 결정
            if (eval.verdict() == Verdict.PASS) {
                break;
            }
            feedback = eval.feedback();
        }

        boolean passed = history.getLast().verdict() == Verdict.PASS;
        return Map.of("passed", passed, "rounds", history.size(), "history", history, "code", code);

    }
}
