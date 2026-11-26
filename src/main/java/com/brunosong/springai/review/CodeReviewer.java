package com.brunosong.springai.review;

import com.brunosong.springai.agent.LoopLogAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.*;

@Slf4j
public class CodeReviewer {

    static final String SYSTEM = """
            너는 꼼꼼한 자바 코드 리뷰어다. 이번 변경을 리뷰한다.
            - 순서 : listChangedFiles 로 바뀐 파일 보기 -> readDiff 로 파일마다 바뀐 부분 보기
                -> 필요하면 readFile 로 앞뒤 맥락 보기, searchCode 로 다른 곳에서 쓰는지 확인
            - 바뀐 줄(+ 표시)만 리뷰한다. 바뀌지 않은 코드의 문제는 쓰지 않는다.
            - 지적마다 : 파일 경로, 줄 번호(diff 앞에 붙은 번호), 그 줄의 코드 그대로, 심각도(HIGH/MEDIUM/LOW), 이유
            - 문제가 없으면 없다고 쓴다. 억지로 만들지 않는다.
            """;

    private final int maxToolCalls;

    public CodeReviewer(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    /** ① 조사: 에이전트가 도구로 코드를 보며 리뷰 메모를 쓴다 */
    public String investigate(ChatClient client, ReviewTools tools, String focus) {
        ToolCallingManager manager = ToolCallingManager.builder()
                .maxTotalToolCalls(maxToolCalls)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();

        return client.prompt()
                .system(focus == null ? SYSTEM : SYSTEM + "\n - 이번에는 " + focus)
                .user("리뷰 범위: " + tools.range())
                .advisors(new LoopLogAdvisor(manager))
                .tools(tools)
                .call().content();
    }

    /** ② 형식: 자유 글로 쓴 메모를 지적 목록(JSON)으로 옮긴다. 도구 없음 */
    public List<FindingChecker.Finding> toFindings(ChatClient client, String notes) {
        FindingChecker.Report report = client.prompt()
                .system("""
                        코드 리뷰 메모를 지적 목록으로 옮긴다.
                        - 메모에 있는 지적만 옮긴다. 새로 만들지 않는다.
                        - file: 저장소 기준 경로, line: 줄 번호, code: 그 줄의 코드를 메모에 적힌 그대로
                        - severity: HIGH / MEDIUM / LOW, category: 보안·버그·성능·가독성 중 하나
                        - message: 무엇이 문제이고 어떻게 고치면 되는지 한두 문장
                        - 지적이 없으면 빈 목록
                        """)
                .user(notes)
                .advisors(StructuredOutputValidationAdvisor.builder()
                        .outputType(FindingChecker.Report.class)
                        .maxRepeatAttempts(2)
                        .build())
                .call()
                .entity(FindingChecker.Report.class);
        if (report.findings() == null) return List.of();
        // 모델이 칸을 비워 두는 경우가 있어 기본값을 채운다 (정렬·합치기에서 null 이면 터진다)
        // line 이 없으면 0 → FindingChecker 가 NOT_FOUND 로 버린다 (줄 댓글을 달 곳이 없다)
        return report.findings().stream()
                .map(f -> new FindingChecker.Finding(f.file(), f.line() == null ? 0 : f.line(), f.code(),
                        f.severity() == null ? FindingChecker.Severity.LOW : f.severity(),
                        f.category() == null ? "기타" : f.category(),
                        f.message()))
                .toList();
    }

    /** 실습 4: 관점마다 덧붙이는 말 */
    record Perspective(String name, String focus) {}

    static final List<Perspective> PERSPECTIVES = List.of(
            new Perspective("보안", "보안 문제만 본다: 입력 검증, 경로·명령 주입, 비밀 값 노출, 외부 입력을 그대로 믿는 곳"),
            new Perspective("버그", "동작 오류만 본다: null, 경계값, 예외 처리, 동시성, 자원 해제, 잘못된 조건"),
            new Perspective("가독성", "읽기 쉬운지만 본다: 이름, 긴 메서드, 중복, 주석과 코드가 다른 곳, 오타"));

    /** 실습 4: 같은 파일·같은 줄 지적은 하나로 합치고, 심각한 것부터 정렬한다 (코드) */
    static List<FindingChecker.Finding> merge(List<FindingChecker.Finding> all) {
        Map<String, FindingChecker.Finding> byLine = new LinkedHashMap<>();
        for (FindingChecker.Finding f : all) {
            String key = f.file() + ":" + f.line();
            FindingChecker.Finding prev = byLine.get(key);
            if (prev == null) {
                byLine.put(key, f);
            } else {
                FindingChecker.Finding higher = f.severity().ordinal() < prev.severity().ordinal() ? f : prev;   // HIGH 가 ordinal 0
                byLine.put(key, new FindingChecker.Finding(f.file(), f.line(), higher.code(), higher.severity(),
                        prev.category() + "+" + f.category(), prev.message() + " / " + f.message()));
            }
        }
        List<FindingChecker.Finding> merged = new ArrayList<>(byLine.values());
        merged.sort(Comparator.comparing(FindingChecker.Finding::severity)
                .thenComparing(FindingChecker.Finding::file)
                .thenComparingInt(FindingChecker.Finding::line));
        return merged;
    }
}
