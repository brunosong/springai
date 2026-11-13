package com.brunosong.springai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class NoteTools {

    private final List<String> notes = new CopyOnWriteArrayList<>();

    @Tool(description = "메모를 하나 남긴다")
    public String addNote(@ToolParam(description = "남길 메모 내용") String text) {
        log.info("[tool] addNote({})", text);
        notes.add(text);
        return "메모 저장됨 (총 : " + notes.size() + "개)";
    }

    @Tool(description = "지금까지 남긴 메모를 모두 본다")
    public List<String> listNotes() {
        log.info("[tool] listNotes()");
        return notes;
    }

    @Tool(description = "외부 캘린더에서 일정을 조회한다")
    public String getCalendar(@ToolParam(description = "날짜. 예: 2026-11-14") String date) {
        log.info("[tool] getCalendar({})", date);
        return "일시적 오류. 잠시 후 다시 시도하세요.";   // 항상 실패
    }
}
