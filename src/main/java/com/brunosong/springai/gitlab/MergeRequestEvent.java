package com.brunosong.springai.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * GitLab 이 보내는 "Merge Request Hook" 웹훅 본문 중 봇이 쓰는 칸만 옮긴 것.
 * 나머지 칸은 무시한다 (Spring Boot 기본 설정이 모르는 칸을 무시한다).
 *
 * <pre>
 * {
 *   "object_kind": "merge_request",
 *   "user": { "username": "root" },
 *   "project": { "id": 1, "path_with_namespace": "root/demo-shop" },
 *   "object_attributes": {
 *     "iid": 2, "title": "할인 계산과 사용자별 주문 수",
 *     "state": "opened", "action": "update", "draft": false,
 *     "last_commit": { "id": "85540632…" }
 *   }
 * }
 * </pre>
 */
public record MergeRequestEvent(
        @JsonProperty("object_kind") String objectKind,
        User user,
        Project project,
        @JsonProperty("object_attributes") Attributes attributes) {

    // ========== 본문의 칸들 ==========

    public record User(String username) {}

    public record Project(long id, @JsonProperty("path_with_namespace") String path) {}

    public record Attributes(
            long iid,                                   // 프로젝트 안의 MR 번호 (!2 의 2)
            String title,
            String state,                               // opened, closed, merged
            String action,                              // open, reopen, update, close, merge, approved ...
            boolean draft,
            @JsonProperty("last_commit") Commit lastCommit) {}

    public record Commit(String id) {}

    // ========== 리뷰할지 거르기 ==========

    /** 리뷰를 시작하는 동작. close, merge, approved 같은 것은 리뷰하지 않는다 */
    private static final Set<String> REVIEW_ACTIONS = Set.of("open", "reopen", "update");

    /**
     * 리뷰할 이벤트인지 판단한다. 리뷰하지 않을 거면 그 이유, 리뷰할 거면 null.
     * 웹훅 본문만으로 확실히 아는 것만 여기서 거른다 (LLM 0번).
     *
     * "새 커밋이 있나"는 여기서 판단하지 않는다. update 웹훅의 oldrev 는 push 때만 들어 있고,
     * Draft → Ready 나 "닫힌 동안 push → Reopen" 처럼 새 head 를 리뷰해야 하는데 oldrev 가 없는 경우가 있다.
     * 그래서 update 는 모두 큐에 넣고, "이 커밋을 이미 리뷰했나"는 MrReviewBot 이 MR 댓글의 표시로 가린다.
     */
    public String skipReason() {
        if (!"merge_request".equals(objectKind) || attributes == null || project == null) {
            return "머지 요청 이벤트가 아니다";
        }
        if (!"opened".equals(attributes.state())) {
            return "열린 MR 이 아니다 (" + attributes.state() + ")";
        }
        if (!REVIEW_ACTIONS.contains(attributes.action())) {
            return "리뷰할 동작이 아니다 (" + attributes.action() + ")";
        }
        if (attributes.draft()) {
            return "Draft MR 이다";
        }
        return null;
    }

    // ========== 같은 이벤트 가르기 ==========

    /** 같은 MR 의 같은 커밋인지 가르는 열쇠. 예: "1!2@85540632…" */
    public String key() {
        String head = attributes.lastCommit() == null ? "?" : attributes.lastCommit().id();
        return project.id() + "!" + attributes.iid() + "@" + head;
    }
}
