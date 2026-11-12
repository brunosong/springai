package com.brunosong.mcpserver;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OrderMcpService {

    private final Map<String,String> orders = Map.of(
            "A100", "배송 중 (내일 도착 예정)",
            "A200", "배송 완료",
            "A300", "결제 대기");

    @McpTool(name = "getOrderStatus", description = "주문 번호로 주문 상태를 조회한다")
    public String getOrderStatus(
            @McpToolParam(description = "주문 번호. 예: A100", required = true) String orderId) {
        log.info("[mcp-server] getOrderStatus({})", orderId);
        return orders.getOrDefault(orderId, "없는 주문 번호 입니다: " + orderId);
    }

    @McpResource(uri = "policy://refund",   // 이 문서의 주소. 클라이언트는 이걸로 달라고 한다.
                 name = "refund-policy",    // 목록에 보일 이름
                 description = "환불 정책",   // 목록에 보일 설명
                 mimeType = "text/plain")   // 내용 형식
    public String refundPolicy() {
        log.info("[mcp-server] resources/read policy://refund");
        return """
               1. 배송 완료 후 7일 이내 환불 가능
               2. 단순 변심은 왕복 배송비 6,000원 고객 부담
               3. 결제 대기 주문은 바로 취소 가능
               """;
    }

    @McpPrompt(name = "order-guide", description = "주문 상태를 고객에게 안내하는 문장을 만든다")
    public McpSchema.GetPromptResult orderGuide(
            @McpArg(name = "orderId", description = "주문 번호", required = true) String orderId) {
        log.info("[mcp-server] prompts/get order-guide({})", orderId);

        // 빈칸(orderId)을 채운 질문 글
        String text = "주문 " + orderId + "의 상태를 조회해서, 고객에게 친절하게 두 문장으로 안내해줘.";

        return new McpSchema.GetPromptResult("주문 안내",
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }
}
