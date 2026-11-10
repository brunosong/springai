package com.brunosong.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;

@Slf4j
public class DateTimeTools {

    @Tool(description = "사용자 시간대 기준 현재 날짜와 시간을 알려준다")
    public String getCurrentDateTime() {
        log.info("[tool] getCurrentDateTime 호출됨");
        return LocalDateTime.now().toString();
    }
}
