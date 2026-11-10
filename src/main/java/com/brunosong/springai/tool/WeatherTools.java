package com.brunosong.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

@Slf4j
public class WeatherTools {

    private static final Map<String, String> DATA = Map.of(
            "서울", "맑음, 21도",
            "부산", "흐림, 18도",
            "제주", "비, 16도"
    );

    @Tool(description = "도시의 현재 날씨를 조회한다")
    public String getWeather(@ToolParam(description = "한국 도시 이름. 예 : 서울, 부산") String city) {
        log.info("[tool] getWeather(city={})", city);

        if (city.equals("평양")) throw new IllegalStateException("이 지역은 조회할 수 없습니다");

        return DATA.getOrDefault(city, "그 도시는 날짜 정보가 없음");
    }


}
