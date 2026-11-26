package com.brunosong.springai.gitlab;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitLab 웹훅을 받아 리뷰를 큐에 넣는다.
 *   POST /gitlab/webhook : GitLab 이 부른다. 검사 3개를 거쳐 큐에 넣고 바로 답한다
 *   GET  /gitlab/review  : 사람이 손으로 돌려 본다 (기본 dry-run)
 *   POST /gitlab/judge   : 사람이 Resolve 한 뒤 머지 판정을 다시 한다 (15-1)
 */
@Slf4j
@RestController
@RequestMapping("/gitlab")
public class GitLabWebhookController {

    private final MrReviewBot bot;
    private final GitLabApi api;
    private final byte[] secret;

    /** 리뷰는 한 번에 하나씩. 로컬 모델(GPU 하나)과 무료 API 한도를 지키고, 같은 저장소 폴더를 동시에 건드리지 않는다 */
    private final ExecutorService queue = Executors.newSingleThreadExecutor();

    /** 큐에 있거나 리뷰 중인 MR@커밋. 같은 이벤트가 두 번 와도 한 번만 리뷰한다 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public GitLabWebhookController(MrReviewBot bot, GitLabApi api,
                                   @Value("${gitlab.webhook-secret}") String secret) {
        this.bot = bot;
        this.api = api;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    // ========== 웹훅 ==========

    /** GitLab 웹훅이 부르는 주소. 10초 안에 답해야 하므로 리뷰는 큐에 넣고 바로 202 를 돌려준다 */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event,
            @RequestBody MergeRequestEvent body) {

        // 검사 1. 진짜 GitLab 이 보낸 건지
        if (!isFromGitLab(token)) {
            log.warn("[webhook] 토큰이 맞지 않는 요청을 거절했다");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "rejected"));
        }

        // 검사 2. 리뷰할 이벤트인지 — 코드로 거른다 (LLM 0번)
        String skip = "Merge Request Hook".equals(event) ? body.skipReason() : "MR 이벤트가 아니다 (" + event + ")";
        if (skip != null) {
            log.info("[webhook] 건너뜀: {}", skip);
            return ignored(skip);
        }

        // 검사 3. 같은 MR@커밋이 이미 큐에 있으면 또 넣지 않는다
        String key = body.key();
        if (!inFlight.add(key)) {
            log.info("[webhook] 건너뜀: 이미 큐에 있다 ({})", key);
            return ignored("이미 큐에 있다");
        }

        // 통과 → 큐에 넣고 바로 답한다
        long projectId = body.project().id();
        long iid = body.attributes().iid();
        queue.submit(() -> reviewInBackground(key, projectId, iid));

        log.info("[webhook] 큐에 넣음: {} ({} by {})", key, body.attributes().action(), body.user().username());
        return ResponseEntity.accepted().body(Map.of("status", "queued", "key", key));
    }

    /** 웹훅에 설정한 비밀 값과 비교. 시간 차로 값을 추측하지 못하게 MessageDigest.isEqual 을 쓴다 */
    private boolean isFromGitLab(String token) {
        return token != null && MessageDigest.isEqual(secret, token.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> ignored(String reason) {
        return ResponseEntity.ok(Map.of("status", "ignored", "reason", reason));
    }

    // ========== 큐에서 도는 리뷰 ==========

    private void reviewInBackground(String key, long projectId, long iid) {
        try {
            MrReviewBot.Result r = bot.review(projectId, iid, false);
            log.info("[webhook] {} → {} {}", key, r.status(), r.reason() == null ? "" : r.reason());
        } catch (Exception e) {
            log.error("[webhook] {} 리뷰 실패", key, e);
            tellFailure(projectId, iid, e);
        } finally {
            inFlight.remove(key);   // 끝나면(실패해도) 같은 커밋을 다시 받을 수 있게
        }
    }

    /** 실패도 MR 에 알린다. 안 그러면 작성자는 봇이 리뷰 중인지 죽었는지 모른다 */
    private void tellFailure(long projectId, long iid, Exception e) {
        String why = rootCause(e).getClass().getSimpleName();
        try {
            api.comment(projectId, iid, "🤖 AI 리뷰를 하지 못했습니다 (`" + why + "`). 서버 로그를 확인해 주세요.");
        } catch (Exception ignored) {
            // 댓글마저 실패하면 로그만 남는다
        }
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    // ========== 손으로 돌려 보기 ==========

    /** 웹훅 없이 손으로 돌려 보기. 기본은 dry-run(GitLab 에 아무것도 안 쓴다), 끝날 때까지 기다린다 */
    @GetMapping("/review")
    public MrReviewBot.Result review(@RequestParam long project, @RequestParam long mr,
                                     @RequestParam(defaultValue = "true") boolean dryRun) {
        return bot.review(project, mr, dryRun);
    }

    /** 사람이 Resolve 한 뒤 다시 판정. Resolve 는 웹훅이 오지 않아서 손으로 부른다 */
    @PostMapping("/judge")
    public Map<String, Object> judge(@RequestParam long project, @RequestParam long mr) {
        String head = api.mergeRequest(project, mr).diffRefs().headSha();
        return Map.of("openHigh", bot.judge(project, mr, head));
    }

    @PreDestroy
    void shutdown() {
        queue.shutdownNow();
    }
}
