package com.brunosong.system.digitaltwin.chat;

/** 대화 기록 한 줄. role 은 "user" 또는 "assistant". */
public record ChatMessage(String role, String content, String timestamp) {
}
