package com.brunosong.system.digitaltwin.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatResponse(String response, @JsonProperty("session_id") String sessionId) {
}
