package com.brunosong.system.digitaltwin.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 세션별 대화 기록을 로컬 JSON 파일로 보관한다. */
@Component
public class ConversationStore {

    private final ObjectMapper objectMapper;
    private final Path memoryDir;

    public ConversationStore(ObjectMapper objectMapper, @Value("${twin.memory-dir}") String memoryDir) {
        this.objectMapper = objectMapper;
        this.memoryDir = Path.of(memoryDir);
    }

    public List<ChatMessage> load(String sessionId) {
        Path file = pathOf(sessionId);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("대화 기록을 읽을 수 없습니다: " + file, e);
        }
    }

    public void save(String sessionId, List<ChatMessage> messages) {
        try {
            Files.createDirectories(memoryDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(pathOf(sessionId).toFile(), messages);
        } catch (IOException e) {
            throw new UncheckedIOException("대화 기록 폴더를 만들 수 없습니다: " + memoryDir, e);
        } catch (JacksonException e) {
            throw new IllegalStateException("대화 기록을 저장할 수 없습니다: " + sessionId, e);
        }
    }

    /** 세션 ID 가 그대로 파일명이 되므로, 경로를 벗어나지 못하게 UUID 형식만 받는다. */
    private Path pathOf(String sessionId) {
        return memoryDir.resolve(UUID.fromString(sessionId) + ".json");
    }
}
