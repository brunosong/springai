package com.brunosong.springai.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 봇이 쓰는 GitLab REST API 5개만 감싼다.
 *   읽기 : project, mergeRequest, notes
 *   쓰기 : comment(요약 댓글), commentOnLine(줄 댓글)
 * 토큰은 PRIVATE-TOKEN 헤더로 보낸다 (application-local.yaml 의 gitlab.token).
 */
@Slf4j
@Component
public class GitLabApi {

    // ========== GitLab 응답 중 봇이 쓰는 칸만 ==========

    /** 프로젝트. path 는 "그룹/프로젝트" — git fetch 주소를 만들 때 쓴다 */
    public record Project(long id, @JsonProperty("path_with_namespace") String path) {}

    /** MR. diffRefs 가 리뷰할 범위(base..head)다 */
    public record MergeRequest(
            long iid,
            String title,
            String state,                                       // opened, closed, merged
            @JsonProperty("target_branch") String targetBranch,
            @JsonProperty("web_url") String webUrl,
            @JsonProperty("diff_refs") DiffRefs diffRefs) {}

    /** MR 의 세 커밋. 줄 댓글을 달 때 세 개 다 필요하다 */
    public record DiffRefs(
            @JsonProperty("base_sha") String baseSha,          // MR 브랜치가 갈라진 지점
            @JsonProperty("start_sha") String startSha,        // 지금 target 브랜치 끝
            @JsonProperty("head_sha") String headSha) {}       // MR 의 마지막 커밋

    /** MR 에 달린 댓글 하나. system 은 "커밋을 추가했다" 같은 GitLab 자동 기록 */
    public record Note(long id, String body, boolean system) {}

    // ========== 준비 ==========

    private final RestClient http;

    public GitLabApi(RestClient.Builder builder,
                     @Value("${gitlab.url}") String url,
                     @Value("${gitlab.token}") String token) {
        this.http = builder.baseUrl(url + "/api/v4")
                .defaultHeader("PRIVATE-TOKEN", token)
                .build();
    }

    // ========== 읽기 ==========

    public Project project(long projectId) {
        return http.get()
                .uri("/projects/{id}", projectId)
                .retrieve()
                .body(Project.class);
    }

    public MergeRequest mergeRequest(long projectId, long iid) {
        return http.get()
                .uri("/projects/{id}/merge_requests/{iid}", projectId, iid)
                .retrieve()
                .body(MergeRequest.class);
    }

    /** MR 의 댓글 전부 (줄 댓글 포함). 한 번에 100개씩, 다음 페이지가 없을 때까지 모은다 */
    public List<Note> notes(long projectId, long iid) {
        List<Note> all = new ArrayList<>();
        int page = 1;
        while (true) {
            ResponseEntity<List<Note>> res = http.get()
                    .uri("/projects/{id}/merge_requests/{iid}/notes?per_page=100&page={page}", projectId, iid, page)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {});
            if (res.getBody() != null) all.addAll(res.getBody());

            String next = res.getHeaders().getFirst("X-Next-Page");   // 마지막 페이지면 비어 있다
            if (next == null || next.isBlank()) return all;
            page++;
        }
    }

    // ========== 쓰기 ==========

    /** MR 전체에 다는 일반 댓글 */
    public void comment(long projectId, long iid, String body) {
        http.post()
                .uri("/projects/{id}/merge_requests/{iid}/notes", projectId, iid)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 바뀐 줄에 다는 줄 댓글. 성공하면 true.
     * diff 에 없는 줄을 주면 GitLab 이 400 을 돌려준다 → false (봇 전체를 멈추지 않는다)
     */
    public boolean commentOnLine(long projectId, long iid, DiffRefs refs, String file, int newLine, String body) {
        try {
            http.post()
                    .uri("/projects/{id}/merge_requests/{iid}/discussions", projectId, iid)
                    .body(Map.of("body", body, "position", position(refs, file, newLine)))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            log.warn("[gitlab] 줄 댓글 실패 {}:{} → {} {}", file, newLine, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        }
    }

    /** 줄 댓글의 자리: "이 MR(세 커밋)의 이 파일, 바뀐 뒤 몇 번째 줄" */
    private static Map<String, Object> position(DiffRefs refs, String file, int newLine) {
        return Map.of(
                "position_type", "text",
                "base_sha", refs.baseSha(),
                "start_sha", refs.startSha(),
                "head_sha", refs.headSha(),
                "old_path", file,
                "new_path", file,
                "new_line", newLine);
    }
}
