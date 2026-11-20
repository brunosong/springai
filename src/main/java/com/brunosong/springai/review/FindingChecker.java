package com.brunosong.springai.review;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모델이 낸 지적(Finding)이 실제 코드와 맞는지 코드로 확인한다. LLM 호출 0번.
 */
public class FindingChecker {

    public enum Severity { HIGH, MEDIUM, LOW }

    /** 지적 하나. code 는 지적한 줄의 코드를 그대로 옮긴 것 (검증에 쓴다) */
    public record Finding(String file, int line, String code, Severity severity, String category, String message) {}

    /** 모델이 채우는 보고서 */
    public record Report(List<Finding> findings) {}

    /** 검증 결과를 붙인 지적 */
    public record Checked(Finding finding, Status status, String note) {}

    public enum Status {
        OK,             // 바뀐 줄이고, 인용한 코드도 그 줄에 있다
        LINE_SHIFTED,   // 인용한 코드가 다른 줄에 있다 (줄 번호만 틀림)
        NOT_CHANGED,    // 실제 코드지만 이번에 바뀐 줄이 아니다
        NOT_FOUND       // 파일·줄·코드가 없다 (지어낸 지적)
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final ReviewTools tools;
    private final Map<String, Set<Integer>> addedLines;   // 파일 → 이번에 추가·수정된 줄 번호

    public FindingChecker(ReviewTools tools) {
        this.tools = tools;
        this.addedLines = parseAddedLines(tools.git("diff", "-U0", tools.range()));
    }

    /**
     * diff -U0 에서 "새 버전 기준으로 추가된 줄 번호"를 모은다.
     * @@ -10,2 +12,3 @@ → 새 버전 12, 13, 14줄이 추가(수정)된 줄
     */
    static Map<String, Set<Integer>> parseAddedLines(String diff) {
        Map<String, Set<Integer>> result = new HashMap<>();
        String file = null;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ ")) {
                file = line.startsWith("+++ b/") ? line.substring(6) : null;   // 지워진 파일은 "+++ /dev/null"
                if (file != null) result.putIfAbsent(file, new HashSet<>());
                continue;
            }
            Matcher m = HUNK.matcher(line);
            if (file != null && m.find()) {
                int start = Integer.parseInt(m.group(1));
                int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                for (int i = 0; i < count; i++) result.get(file).add(start + i);
            }
        }
        return result;
    }

    public Set<Integer> addedLines(String file) {
        return addedLines.getOrDefault(file, Set.of());
    }

    public List<Checked> check(List<Finding> findings) {
        return findings.stream().map(this::check).toList();
    }

    Checked check(Finding f) {
        if (!addedLines.containsKey(f.file())) {
            List<String> lines = f.file() == null ? null : tools.fileLines(f.file());
            return lines == null
                    ? new Checked(f, Status.NOT_FOUND, "없는 파일")
                    : new Checked(f, Status.NOT_CHANGED, "이번에 바뀌지 않은 파일");
        }
        List<String> lines = tools.fileLines(f.file());
        if (f.line() < 1 || f.line() > lines.size()) {
            return new Checked(f, Status.NOT_FOUND, "파일은 " + lines.size() + "줄까지 있다");
        }

        String quote = firstLine(f.code());
        if (quote.isEmpty()) {
            return new Checked(f, Status.NOT_FOUND, "인용한 코드가 비었다");
        }

        // 인용한 코드가 파일 어디에 있나. 여러 곳이면 모델이 말한 줄에서 가장 가까운 곳
        int actual = -1;
        for (int i = 1; i <= lines.size(); i++) {
            if (normalize(lines.get(i - 1)).contains(quote)
                    && (actual < 0 || Math.abs(i - f.line()) < Math.abs(actual - f.line()))) {
                actual = i;
            }
        }
        if (actual < 0) {
            return new Checked(f, Status.NOT_FOUND, "인용한 코드가 파일에 없다");
        }

        boolean changed = addedLines(f.file()).contains(actual);
        if (!changed) {
            return new Checked(f, Status.NOT_CHANGED, actual + "줄은 이번에 바뀐 줄이 아니다");
        }
        return actual == f.line()
                ? new Checked(f, Status.OK, "")
                : new Checked(f, Status.LINE_SHIFTED, "실제로는 " + actual + "줄 (" + (actual - f.line()) + "줄 차이)");
    }

    /** 모델이 diff 를 그대로 옮겨 적으면 앞에 "101 + " 나 "+" 가 붙는다 */
    private static final Pattern DIFF_PREFIX = Pattern.compile("^(\\d+\\s+)?\\+\\s?");

    /**
     * 인용한 코드에서 비교할 한 줄을 뽑는다.
     * - 여러 줄이면 첫 번째 내용 있는 줄만
     * - diff 표시(+, 줄 번호)는 뗀다
     * - 공백 차이는 무시한다
     */
    static String firstLine(String code) {
        if (code == null) return "";
        return code.lines()
                .map(l -> DIFF_PREFIX.matcher(l.strip()).replaceFirst(""))
                .map(FindingChecker::normalize)
                .filter(s -> !s.isEmpty())
                .findFirst().orElse("");
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
