package com.brunosong.springai.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReviewToolsTest {

    @ParameterizedTest
    @ValueSource(strings = {"--output=x", "a b", "a;rm"})
    @DisplayName("이상한 커밋 이름은 git을 실행하기 전에 막힌다")
    void 이상한_커밋_이름은_거절한다(String ref) {
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewTools(Path.of("."), ref, "HEAD"));
    }

    @Test
    @DisplayName("git 없이 문자열만으로 확인한다")
    void withLineNumbers_새_버전_줄_번호를_붙인다() {
        String diff = "@@ -10,2 +12,3 @@\n 문맥\n+추가\n-삭제\n";

        String out = ReviewTools.withLineNumbers(diff);

        assertTrue(out.contains("   12   문맥"));
        assertTrue(out.contains("   13 + 추가"));
        assertTrue(out.contains("      - 삭제"));   // 지워진 줄은 새 버전에 없으니 번호 없음
    }

}