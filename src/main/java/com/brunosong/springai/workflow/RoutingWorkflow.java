package com.brunosong.springai.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class RoutingWorkflow {

    public enum Category { DELIVERY, REFUND, TECH, COUPON, OTHER }

    public record Route(Category category, String reason) {}

    // 경로마다 다른 전문가 프롬프트
    private static final Map<Category, String> PROMPTS = Map.of(
            Category.DELIVERY, """
                    너는 배송 담당 상담원이다.
                    - 주문번호를 알려 달라고 요청한다
                    - 일반 배송은 결제 후 2~3일 걸린다고 안내한다""",
            Category.REFUND, """
                    너는 환불 담당 상담원이다.
                    - 환불 정책: 상품 수령 후 7일 이내, 미개봉이면 전액 환불
                    - 정책에 맞지 않으면 정중하게 거절하고 이유를 설명한다""",
            Category.TECH, """
                    너는 기술 지원 엔지니어다.
                    - 원인 후보를 2~3개 든다
                    - 사용자가 확인할 순서를 번호로 안내한다""",
            Category.OTHER, """
                    너는 고객센터 상담원이다.
                    - 짧고 친절하게 답한다""",
            Category.COUPON, """
                    너는 쿠폰, 할인코드 담당 상담원이다.
                    - 쿠폰기능은 아직 제공하지 않는다고 대답한다.
                    - 추후 쿠폰 기능이 되면 제공한다고 얘기해준다.
                    """
            );

    private final ChatClient gemini;
    private final ChatClient ollama;

    public RoutingWorkflow(@Qualifier("geminiChatClient") ChatClient gemini,
                           @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    public Map<String, Object> run(String question) {
        // 1. 분류 : 싼 모델로 충분하다.
        Route route = ollama.prompt()
                .system("""
                        고객 문의를 한 가지로 분류한다.
                        - DELIVERY: 배송, 도착, 송장
                        - REFUND: 환불, 반품, 취소
                        - TECH: 앱 오류, 로그인, 결제 실패 같은 기술 문제
                        - COUPON : 쿠폰, 할인 코드
                        - OTHER: 그 밖의 것
                        """)
                .user(question)
                .call().entity(Route.class);
        log.info("[route 1] {} ({})", route.category(), route.reason());

        String system = PROMPTS.get(route.category());

        // 2. 경로 선택 : 어려운 기술 문의만 큰 모델로 보낸다.
        if (route.category() == Category.TECH) {
            try {
                String answer = answer(gemini, system, question);
                log.info("[route 2] gemini 로 처리 완료");
                return Map.of("route", route, "model", "gemini", "answer", answer);
            } catch (Exception e) {
                // 큰 모델이 실패하면(할당량 초과, 장애) 작은 모델로 대신 처리
                log.warn("[route 2] gemini 실패 → ollama 로 대체: {}", e.getMessage());
            }
        }

        // 3 처리
        String answer = answer(ollama, system, question);
        log.info("[route 3] ollama 로 처리 완료");
        return Map.of("route", route, "model", "ollama", "answer", answer);

    }

    private String answer(ChatClient client, String system, String question) {
        return client.prompt()
                .system(system)
                .user(question)
                .call()
                .content();
    }
}
