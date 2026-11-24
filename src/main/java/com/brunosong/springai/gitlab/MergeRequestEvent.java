package com.brunosong.springai.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
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
 *     "oldrev": "47cf443c…",
 *     "last_commit": { "id": "85540632…" }
 *   },
 *   "changes": {}
 * }
 * </pre>
 */
public record MergeRequestEvent(
        @JsonProperty("object_kind") String objectKind,
        User user,
        Project project,
        @JsonProperty("object_attributes") Attributes attributes,
        Map<String, Change> changes) {          // update 일 때 바뀐 칸: 이전 값 → 지금 값

    // ========== 본문의 칸들 ==========

    public record User(String username) {}

    public record Project(long id, @JsonProperty("path_with_namespace") String path) {}

    public record Attributes(
            long iid,                                   // 프로젝트 안의 MR 번호 (!2 의 2)
            String title,
            String state,                               // opened, closed, merged
            String action,                              // open, reopen, update, close, merge, approved ...
            boolean draft,
            String oldrev,                              // update 일 때 "새 커밋이 올라왔으면" 이전 head. 제목만 바꾸면 없다
            @JsonProperty("last_commit") Commit lastCommit) {}

    public record Commit(String id) {}

    public record Change(Object previous, Object current) {}

    // ========== 리뷰할지 거르기 ==========

    /** 리뷰를 시작하는 동작. close, merge, approved 같은 것은 리뷰하지 않는다 */
    private static final Set<String> REVIEW_ACTIONS = Set.of("open", "reopen", "update");

    /**
     * 리뷰할 이벤트인지 판단한다. 리뷰하지 않을 거면 그 이유, 리뷰할 거면 null.
     * LLM 을 부르기 전에 코드로 거른다 — 모델 호출은 돈과 시간이다.
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
        if (isEditWithoutNewCommit()) {
            return "새 커밋 없는 수정이다 (제목·설명·라벨 변경)";
        }
        if (attributes.draft()) {
            return "Draft MR 이다";
        }
        return null;
    }

    /** update 인데 새 커밋도 없고 Draft 해제도 아니다 = 제목·설명·라벨만 바꿨다 */
    private boolean isEditWithoutNewCommit() {
        boolean newCommits = attributes.oldrev() != null;
        return "update".equals(attributes.action()) && !newCommits && !becameReady();
    }

    /** Draft 를 풀었나 (Draft → Ready). 커밋은 그대로지만 이때 처음 리뷰해야 한다 */
    boolean becameReady() {
        Change draft = changes == null ? null : changes.get("draft");
        return draft != null
                && Boolean.TRUE.equals(draft.previous())
                && Boolean.FALSE.equals(draft.current());
    }

    // ========== 같은 이벤트 가르기 ==========

    /** 같은 MR 의 같은 커밋인지 가르는 열쇠. 예: "1!2@85540632…" */
    public String key() {
        String head = attributes.lastCommit() == null ? "?" : attributes.lastCommit().id();
        return project.id() + "!" + attributes.iid() + "@" + head;
    }
}
