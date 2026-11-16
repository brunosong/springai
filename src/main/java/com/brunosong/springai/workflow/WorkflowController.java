package com.brunosong.springai.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
public class WorkflowController {

//    private static final String SAMPLE_COMMITS = """
//            feat: 주문 상태 조회 API 추가
//            fix: 로그인 후 30분이 지나도 세션이 만료되지 않던 문제 수정
//            refactor: OrderService 메서드 분리
//            docs: README 설치 방법 보완
//            chore: Gradle 버전 업그레이드
//            fix: 장바구니 수량이 음수로 저장되던 문제 수정
//            feat: 주문 내역 엑셀 다운로드
//            """;

    private static final String SAMPLE_COMMITS = """
            c8d4eb5 (HEAD -> main, origin/main) Add agent module: LoopLogAdvisor, NoteTools, tool call limits, plan-and-execute
            1b83eec Move MCP client connection settings to application.yaml
            f5ee9ed Add mcp module: mcp-server subproject (@McpTool, resource, prompt) and MCP client controller
            dccdfa2 Add advisor module: SimpleLoggerAdvisor, CallAdvisor, BaseAdvisor, order, short-circuit, streaming
            544e3e7 Add rag module: SimpleVectorStore, QuestionAnswerAdvisor, filters, ETL, modular RAG
            2abf0f0 Add embedding module: embed, cosine similarity, semantic search, FAQ index, ask
            befe5e0 Add tool module: @Tool, @ToolParam, defaultTools, ToolContext, returnDirect
            1edf62d Add memory module: manual history, ChatMemory advisor, window size
            cca87d7 Add streaming module: stream().content() over SSE with EventSource page
            858da34 Add glossary sections to basic and prompt docs
            """;


    private static final String SAMPLE_CODE = """
            public List<String> find(String n) throws Exception {
                Connection c = DriverManager.getConnection(URL, "admin", "admin1234");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT * FROM users WHERE name = '" + n + "'");
                List<String> l = new ArrayList<>();
                while (r.next()) {
                    String id = r.getString("id");
                    ResultSet o = c.createStatement()
                            .executeQuery("SELECT count(*) FROM orders WHERE user_id = " + id);
                    o.next();
                    l.add(r.getString("name") + ":" + o.getInt(1));
                }
                return l;
            }
            """;

    private final ChainWorkflow chain;
    private final RoutingWorkflow routing;
    private final ParallelWorkflow parallel;



    @GetMapping("/chain")
    public Map<String, Object> chain(@RequestParam(required = false) String commits) {
        return chain.run(commits != null ? commits : SAMPLE_COMMITS);
    }

    @GetMapping("/route")
    public Map<String, Object> route(@RequestParam(defaultValue = "어제 주문한 신발 언제 와요?") String question) {
        return routing.run(question);
    }

    @GetMapping("/parallel")
    public Map<String, Object> parallel(@RequestParam(required = false) String code,
                                        @RequestParam(defaultValue = "true") boolean parallel) {
        return this.parallel.run(code != null ? code : SAMPLE_CODE, parallel);
    }
}
