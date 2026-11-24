package com.brunosong.springai.gitlab;

import com.brunosong.springai.review.CodeReviewer;
import com.brunosong.springai.review.FindingChecker;
import com.brunosong.springai.review.FindingChecker.Checked;
import com.brunosong.springai.review.FindingChecker.Finding;
import com.brunosong.springai.review.FindingChecker.Severity;
import com.brunosong.springai.review.FindingChecker.Status;
import com.brunosong.springai.review.ReviewTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MR 하나를 리뷰하고 GitLab 에 댓글로 남긴다.
 *   ① 받아 오기 : MR 정보(API) + 커밋(git fetch)
 *   ② 리뷰      : 14장 CodeReviewer / ReviewTools / FindingChecker 그대로
 *   ③ 나누기    : 지적마다 줄 댓글 / 요약 / 버림 을 정한다 (코드 규칙, LLM 0번)
 *   ④ 남기기    : 줄 댓글을 달고, 요약 댓글 하나를 단다
 */
@Slf4j
@Component
public class MrReviewBot {

    /** 한 번에 다는 줄 댓글 최대 개수. 넘는 것은 요약 댓글로 */
    static final int MAX_INLINE = 5;

    /** 14장 지시문에 덧붙이는 말. 사람들이 읽는 곳에 다는 댓글이라 취향 지적은 뺀다 */
    static final String FOCUS = "실제로 문제가 되는 버그·보안·자원 누수만 지적한다. "
            + "설계 취향, import 정리, 이름 짓기 같은 것은 쓰지 않는다.";

    // ========== 돌려주는 값 ==========

    /** 지적 하나를 어떻게 처리했나 (dry-run 에서 "왜 안 달렸나"를 보려고) */
    public record Line(String file, int line, Severity severity, String result, String message) {}

    public record Result(
            String mr,              // "프로젝트id!MR번호"
            String head,            // 리뷰한 커밋
            String status,          // REVIEWED, DRY_RUN, SKIPPED
            String reason,          // SKIPPED 일 때 이유
            long ms,
            List<String> viewed,    // 모델이 본 diff·파일
            List<Line> lines,       // 지적마다 처리 결과
            String summary) {       // 요약 댓글 본문

        static Result skipped(String mr, String reason) {
            return new Result(mr, null, "SKIPPED", reason, 0, List.of(), List.of(), null);
        }
    }

    // ========== 준비 ==========

    private final GitLabApi api;
    private final RepoMirror mirror;
    private final ChatClient client;
    private final String modelName;
    private final CodeReviewer reviewer = new CodeReviewer(15);

    public MrReviewBot(GitLabApi api, RepoMirror mirror,
                       @Qualifier("geminiChatClient") ChatClient gemini,
                       @Qualifier("ollamaChatClient") ChatClient ollama,
                       @Value("${gitlab.model:ollama}") String model) {
        this.api = api;
        this.mirror = mirror;
        this.client = model.equals("gemini") ? gemini : ollama;
        this.modelName = model;
    }

    // ========== 큰 흐름 ==========

    /**
     * @param dryRun true 면 GitLab 에 아무것도 쓰지 않고 "쓸 내용"만 돌려준다
     */
    public Result review(long projectId, long iid, boolean dryRun) {
        long start = System.currentTimeMillis();
        String name = projectId + "!" + iid;

        // ① 받아 오기 — 웹훅 본문은 "무슨 일이 있었다"는 알림으로만 쓰고, 내용은 API 로 다시 읽는다
        GitLabApi.MergeRequest mr = api.mergeRequest(projectId, iid);
        if (!"opened".equals(mr.state())) {
            return Result.skipped(name, "열린 MR 이 아니다 (" + mr.state() + ")");
        }
        GitLabApi.DiffRefs refs = mr.diffRefs();
        String head = refs.headSha();

        List<String> existing = api.notes(projectId, iid).stream().map(GitLabApi.Note::body).toList();
        if (!dryRun && containsMarker(existing, summaryMarker(head))) {
            return Result.skipped(name, "이 커밋(" + shortSha(head) + ")은 이미 리뷰했다");
        }
        Path repo = mirror.fetch(api.project(projectId), mr);

        // ② 리뷰 — 14장 /review/report 와 같은 세 줄. 범위만 "MR 의 base..head" 로 바뀌었다
        ReviewTools tools = new ReviewTools(repo, refs.baseSha(), head);
        String notes = reviewer.investigate(client, tools, FOCUS);
        List<Finding> findings = reviewer.toFindings(client, notes);
        List<Checked> checked = new FindingChecker(tools).check(findings);

        // ③ 나누기 — 봇이 시끄러우면 아무도 안 읽는다. 무엇을 어디에 달지는 코드가 정한다
        Triage t = triage(checked, existing);

        // ④ 남기기 — 줄 댓글을 달고, GitLab 이 거절한 자리는 요약으로 돌린다
        int posted = 0;
        for (Checked c : t.inline) {
            Finding f = c.finding();
            if (dryRun) {
                posted++;
                t.note(f, "줄 댓글 (dry-run)");
            } else if (api.commentOnLine(projectId, iid, refs, f.file(), f.line(), lineComment(f))) {
                posted++;
                t.note(f, "줄 댓글");
            } else {
                t.summary.add(c);
                t.note(f, "요약에: 줄 댓글 실패");
            }
        }

        long ms = System.currentTimeMillis() - start;
        String summary = summary(head, posted, t, tools.viewed().size(), ms);
        if (!dryRun) api.comment(projectId, iid, summary);

        log.info("[bot] {} 리뷰 끝: 줄 댓글 {}, 요약 {}, LOW {}, 버림 {} ({}ms)",
                name, posted, t.summary.size(), t.low, t.dropped, ms);
        return new Result(name, head, dryRun ? "DRY_RUN" : "REVIEWED", null, ms,
                tools.viewed(), t.log, summary);
    }

    // ========== ③ 나누기 — 댓글 규칙 ==========

    /** 지적을 어디에 둘지 나눈 결과 */
    static final class Triage {
        final List<Checked> inline = new ArrayList<>();    // 줄 댓글로 달 것
        final List<Checked> summary = new ArrayList<>();   // 요약 댓글에 적을 것
        int low;                                           // 개수만 알릴 LOW
        int dropped;                                       // 검증에서 버린 것
        final List<Line> log = new ArrayList<>();          // 지적마다 어떻게 처리했나

        void note(Finding f, String result) {
            log.add(new Line(f.file(), f.line(), f.severity(), result, f.message()));
        }
    }

    /**
     * 검증을 거친 지적을 줄 댓글 / 요약 / 버림 으로 나눈다. GitLab 을 부르지 않아 단위 테스트할 수 있다.
     *
     * @param existing MR 에 이미 달린 댓글 본문들 (같은 지적을 또 달지 않으려고)
     */
    static Triage triage(List<Checked> checked, List<String> existing) {
        Triage t = new Triage();

        // 규칙 1·2: 검증에서 떨어진 지적은 버리고, LOW 는 개수만 센다
        List<Checked> worth = new ArrayList<>();
        for (Checked c : checked) {
            Finding f = c.finding();
            if (c.status() == Status.NOT_CHANGED || c.status() == Status.NOT_FOUND) {
                t.dropped++;
                t.note(f, "버림: " + c.status() + " " + c.note());
            } else if (f.severity() == Severity.LOW) {
                t.low++;
                t.note(f, "생략: LOW");
            } else {
                worth.add(c);
            }
        }

        // 규칙 3~5: 같은 줄은 하나만 남기고, 심각한 것부터 자리를 정한다
        for (Checked c : onePerLine(worth, t)) {
            Finding f = c.finding();
            if (c.status() == Status.LINE_SHIFTED) {
                t.summary.add(c);                                   // 자리가 불확실하면 줄에 달지 않는다
                t.note(f, "요약에: " + c.note());
            } else if (containsMarker(existing, lineMarker(f))) {
                t.note(f, "이미 달려 있음");                         // 새 커밋이 올라와 다시 리뷰할 때
            } else if (t.inline.size() >= MAX_INLINE) {
                t.summary.add(c);
                t.note(f, "요약에: 줄 댓글 " + MAX_INLINE + "개 초과");
            } else {
                t.inline.add(c);
            }
        }
        return t;
    }

    /** 같은 파일·같은 줄 지적은 가장 심각한 하나만 남기고, 심각한 것부터 정렬한다 */
    static List<Checked> onePerLine(List<Checked> candidates, Triage t) {
        Map<String, Checked> byLine = new LinkedHashMap<>();
        for (Checked c : candidates) {
            String key = c.finding().file() + ":" + c.finding().line();
            Checked prev = byLine.get(key);
            if (prev == null) {
                byLine.put(key, c);
            } else if (moreSevere(c, prev)) {
                byLine.put(key, c);
                t.note(prev.finding(), "합침: 같은 줄에 더 심각한 지적");
            } else {
                t.note(c.finding(), "합침: 같은 줄에 더 심각한 지적");
            }
        }
        List<Checked> kept = new ArrayList<>(byLine.values());
        kept.sort(Comparator.comparing((Checked c) -> c.finding().severity()));   // HIGH 가 먼저 (enum 순서)
        return kept;
    }

    private static boolean moreSevere(Checked a, Checked b) {
        return a.finding().severity().compareTo(b.finding().severity()) < 0;      // HIGH < MEDIUM < LOW
    }

    // ========== ④ 댓글 문구 ==========

    static String lineComment(Finding f) {
        return """
                **[%s · %s]** %s

                <sub>🤖 AI 리뷰 — 틀릴 수 있습니다. 맞으면 👍, 틀리면 👎 를 눌러 주세요.</sub>
                %s""".formatted(f.severity(), f.category(), f.message(), lineMarker(f));
    }

    String summary(String head, int posted, Triage t, int viewed, long ms) {
        List<String> out = new ArrayList<>();
        out.add("### 🤖 AI 코드 리뷰 — `" + shortSha(head) + "`");
        out.add("");

        if (posted == 0 && t.summary.isEmpty()) {
            out.add("눈에 띄는 문제를 찾지 못했습니다.");
        } else {
            out.add("- 줄 댓글: **" + posted + "개**");
            if (!t.summary.isEmpty()) {
                out.add("- 줄에 달지 않은 지적:");
                for (Checked c : t.summary) out.add("  - " + summaryItem(c));
            }
        }
        if (t.low > 0) out.add("- 사소한 지적(LOW) " + t.low + "개는 생략했습니다.");
        if (t.dropped > 0) out.add("- 검증에서 버린 지적: " + t.dropped + "개 (바뀐 줄이 아니거나 코드에 없음)");

        out.add("");
        out.add("<sub>모델 %s · 본 diff·파일 %d개 · %d초. AI 리뷰는 틀릴 수 있습니다.</sub>"
                .formatted(modelName, viewed, ms / 1000));
        out.add(summaryMarker(head));
        return String.join("\n", out);
    }

    /** 요약 댓글의 한 줄: `파일:줄` 심각도 — 내용 (자리가 밀렸으면 그 설명) */
    private static String summaryItem(Checked c) {
        Finding f = c.finding();
        String item = "`" + f.file() + ":" + f.line() + "` " + f.severity() + " — " + f.message();
        return c.status() == Status.LINE_SHIFTED ? item + " _(" + c.note() + ")_" : item;
    }

    // ========== 같은 댓글을 두 번 달지 않기 위한 표시 (화면에는 안 보이는 HTML 주석) ==========

    static String summaryMarker(String head) {
        return "<!-- ai-review-summary:" + head + " -->";
    }

    /** 줄 번호는 커밋이 쌓이면 바뀌므로 넣지 않는다. 파일 + 지적한 코드로 같은 지적인지 가른다 */
    static String lineMarker(Finding f) {
        String code = f.code() == null ? "" : f.code().strip().replaceAll("\\s+", " ");
        return "<!-- ai-review:" + f.file() + ":" + sha1(code).substring(0, 10) + " -->";
    }

    private static boolean containsMarker(List<String> comments, String marker) {
        return comments.stream().anyMatch(body -> body.contains(marker));
    }

    private static String shortSha(String sha) {
        return sha.substring(0, 8);
    }

    private static String sha1(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
