package com.brunosong.springai.agent;

import com.brunosong.springai.tool.DateTimeTools;
import com.brunosong.springai.tool.WeatherTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final String SYSTEM = """
                너는 사용자의 개인 비서 에이전트다.
                - 목표를 이루기 위해 필요한 도구를 스스로 골라 순서대로 사용한다.
                - 도구 결과를 확인한 뒤 다음 행동을 정한다.
                - 모르는 정보는 지어내지 말고 도구로 확인한다.
                - 목표를 다 이루면 무엇을 했는지 짧게 보고하고 끝낸다.
            """;

    private final ChatClient gemini;
    private final ChatClient ollama;
    private final NoteTools noteTools = new NoteTools();

    public AgentController(@Qualifier("geminiChatClient") ChatClient gemini,
                           @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping("/assistant")
    public String assistant(
            @RequestParam(defaultValue = "부산이랑 제주 날씨 확인하고, 비 오는 곳이 있으면 '<도시> 갈 때 우산 챙기기' 메모 남겨줘") String goal,
            @RequestParam(defaultValue = "ollama") String model) {

        ChatClient client = model.equals("ollama") ? ollama : gemini;

        return client.prompt().system(SYSTEM)
                .user(goal)
                .tools(new DateTimeTools(), new WeatherTools(), noteTools)
                .advisors(new LoopLogAdvisor(ToolCallingManager.builder().build()))
                .call().content();

    }

    @GetMapping("/limited")
    public String limited(
            @RequestParam(defaultValue = "내일 일정 꼭 확인해서 알려줘. 될 때까지 다시 시도해.") String goal,
            @RequestParam(defaultValue = "ollama") String model,
            @RequestParam(defaultValue = "RETURN_ERROR_RESPONSE") ToolCallLimitBehavior behavior) {

        ToolCallingManager manager = ToolCallingManager.builder()
                .maxCallsPerTool(3)     // 도구 하나당 최대 3번
                .maxTotalToolCalls(6)   // 전체 최대 6번
                .onLimitExceeded(behavior)   // THROW 또는 RETURN_ERROR_RESPONSE
                .build();

        ChatClient client = model.equals("ollama") ? ollama : gemini;

        return client.prompt()
                .system(SYSTEM)
                .user(goal)
                .tools(new NoteTools())
                .advisors(new LoopLogAdvisor(manager))
                .call().content();
    }

    @GetMapping("/plan")
    public Map<String, Object> plan(
            @RequestParam(defaultValue = "서울, 부산, 제주 날씨를 확인해서 비 오는 곳은 우산 메모, " +
                    "맑은 곳은 산책 메모를 남기고 마지막에 메모 목록을 보여줘") String goal) {

        // ① 계획: 도구 없이, 단계 목록만 받는다 (03장 Structured Output)
        Plan plan = ollama.prompt()
                .system("""
                        목표를 이루기 위한 단계 목록을 만든다.
                        - 단계는 3~6개, 한 단계는 한 가지 일만
                        - 사용할 수 있는 도구: 현재 시간 조회, 도시 날씨 조회, 메모 남기기, 메모 목록 보기
                        """)
                .user(goal)
                .call()
                .entity(Plan.class);

        log.info("[plan] {}", plan.steps());

        // ② 실행: 단계마다 에이전트 한 번. 이전 결과를 같이 넘긴다
        List<String> results = new ArrayList<>();
        for (String step : plan.steps()) {
            log.info("[step] {}", step);
            String result = ollama.prompt()
                    .system(SYSTEM + "\n지금은 전체 목표 중 한 단계만 처리한다.")
                    .user("""
                            전체 목표 : %s,
                            지금까지 결과 : %s,
                            이번 단계 : %s
                            """.formatted(goal, results, step))
                    .tools(new DateTimeTools(), new WeatherTools(), noteTools)
                    .advisors(new LoopLogAdvisor(ToolCallingManager.builder().maxTotalToolCalls(20).build()))
                    .call().content();

            log.info("[step] {} → {}", step, result);
            results.add(step + " -> " + result);
        }

        // ③ 정리
        String report = ollama.prompt()
                .user("목표: " + goal + "\n단계별 결과:\n" + String.join("\n", results) + "\n\n사용자에게 3줄로 보고해줘.")
                .call()
                .content();

        return Map.of("plan", plan.steps(), "results", results, "report", report);
    }
}
