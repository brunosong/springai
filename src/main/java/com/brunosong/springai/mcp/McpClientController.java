package com.brunosong.springai.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/mcp-client")
public class McpClientController {

    private final ChatClient ollama;
    private final SyncMcpToolCallbackProvider mcpTools;   // 스타터가 만들어 준 빈
    private final List<McpSyncClient> clients;            // 연결마다 하나

    public McpClientController(@Qualifier("ollamaChatClient") ChatClient ollama,
                               SyncMcpToolCallbackProvider mcpTools,
                               List<McpSyncClient> clients) {
        this.ollama = ollama;
        this.mcpTools = mcpTools;
        this.clients = clients;
    }

    // 1) 서버에서 받아 온 도구 목록 (Spring AI가 ToolCallback으로 바꾼 모습)
    @GetMapping("/tools")
    public List<String> tools() {
        return Arrays.stream(mcpTools.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name() + " : " + d.description())
                .toList();
    }

    // 2) 모델이 MCP 도구를 쓰게 하기
    @GetMapping("/ask")
    public String ask(@RequestParam(defaultValue = "주문 A100 지금 어디쯤이야?") String q) {
        return ollama.prompt().user(q).tools(mcpTools)
                .call().content();
    }

    // 3) 연결된 서버 정보
    @GetMapping("/servers")
    public List<String> servers() {
        return clients.stream()
                .map(c -> c.getServerInfo().name() + " " + c.getServerInfo().version())
                .toList();
    }

    // 4) 도구 원본 목록 (MCP 형식 그대로: 이름, 설명, 입력 스키마)
    @GetMapping("/raw-tools")
    public List<McpSchema.Tool> rawTools() {
        return clients.getFirst().listTools().tools();
    }

    // 5) 서버에 어떤 Resource / Prompt가 있는지 목록 보기  (resources/list, prompts/list)
    @GetMapping("/catalog")
    public Map<String, List<String>> catalog() {
        McpSyncClient client = clients.getFirst();
        return Map.of(
                "resources", client.listResources().resources().stream()
                        .map(r -> r.uri() + " : " + r.description())
                        .toList(),
                "prompts", client.listPrompts().prompts().stream()
                                .map(p -> p.name() + " " + p.arguments().stream().map(McpSchema.PromptArgument::name).toList()
                                + " : " + p.description())
                        .toList());
    }

    // 6) Resource 읽어서 프롬프트에 넣기  (resources/read)
    @GetMapping("/refund")
    public String refund(@RequestParam(defaultValue = "어제 받은 물건 그냥 마음이 바뀌었는데 환불돼?") String q) {
        // 1. 서버에서 문서 받아 오기
        McpSchema.ReadResourceResult result =
                clients.getFirst().readResource(new McpSchema.ReadResourceRequest("policy://refund"));

        // 2. 내용 꺼내기
        String policy = ((McpSchema.TextResourceContents) result.contents().getFirst()).text();
        log.info("[mcp-client] policy = {}", policy);

        // 3. RAG 처럼 system에 붙여서 질문 (MCP 도구는 안넘김)
        return ollama.prompt()
                .system("다음 환불 정책만 근거로 답해. 정책에 없는 내용은 모른다고 답해 \n " + policy)
                .user(q)
                .call().content();
    }

    // 7) 서버의 Prompt 양식으로 질문 만들기 + 도구까지 같이  (prompts/get → tools/call)
    @GetMapping("/guide")
    public String guide(@RequestParam(defaultValue = "A100") String orderId) {

        // 1. 양식 이름 + 빈칸 값을 보내서 완성된 메시지 받기
        McpSchema.GetPromptResult prompt =
                clients.getFirst().getPrompt(new McpSchema.GetPromptRequest("order-guide", Map.of("orderId", orderId)));

        // 2. 메시지 내용 꺼내기
        String text = ((McpSchema.TextContent) prompt.messages().getFirst().content()).text();
        log.info("[mcp-client] prompt = {}", text);

        // 3. 그 글을 user 메시지로 보내고, 주문 조회 도구도 넘긴다.
        return ollama.prompt().user(text).tools(mcpTools).call().content();
    }
}
