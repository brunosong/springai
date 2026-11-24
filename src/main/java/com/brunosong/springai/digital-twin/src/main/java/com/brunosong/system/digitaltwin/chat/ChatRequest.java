package com.brunosong.system.digitaltwin.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/** session_id 는 첫 요청에서 비워 두면 서버가 새로 발급한다. */
public record ChatRequest(String message, @JsonProperty("session_id") String sessionId) {
}
