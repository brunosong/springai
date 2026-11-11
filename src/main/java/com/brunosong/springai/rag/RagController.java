package com.brunosong.springai.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/rag")
public class RagController {

    private final VectorStore vectorStore;
    private final ChatClient gemini;
    private final ChatClient ollama;

    public RagController(VectorStore vectorStore,
                         @Qualifier("geminiChatClient") ChatClient gemini,
                         @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.vectorStore = vectorStore;
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @PostConstruct
    void loadFaq() {
        vectorStore.add(List.of(
                new Document("환불은 결제 후 7일 이내에 고객센터에서 신청할 수 있습니다.", Map.of("category", "refund")),
                new Document("배송은 주문 후 평균 2~3일 걸립니다.", Map.of("category", "delivery")),
                new Document("해외 배송은 현재 지원하지 않습니다.", Map.of("category", "delivery")),
                new Document("주문 취소는 배송 시작 전까지만 가능합니다.", Map.of("category", "order")),
                new Document("비밀번호를 잊으셨다면 로그인 화면의 '비밀번호 찾기'를 누르세요.", Map.of("category", "account")),
                new Document("회원 탈퇴는 마이페이지 > 설정에서 할 수 있습니다.", Map.of("category", "account")),
                new Document("포인트는 결제 금액의 1%가 적립됩니다.", Map.of("category", "point")),
                new Document("고객센터 운영 시간은 평일 오전 9시부터 오후 6시까지입니다.", Map.of("category", "cs"))
        ));
        log.info("[rag] FAQ 적재 완료");
    }

    @GetMapping("/search")
    public List<Map<String,Object>> search(@RequestParam String q) {
        return vectorStore.similaritySearch(q).stream()
                .map(d -> Map.<String,Object>of(
                        "text" , d.getText(),
                        "score", d.getScore(),
                        "metadata", d.getMetadata()
                )).toList();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return gemini.prompt()
                .user(q)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .call()
                .content();
    }

    @GetMapping("/ask/debug")
    public Map<String, Object> askDebug(@RequestParam String q) {
        ChatClientResponse res = gemini.prompt()
                .user(q)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .call()
                .chatClientResponse();

        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) res.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        return Map.of(
                "answer", res.chatResponse().getResult().getOutput().getText(),
                "retrieved", docs.stream().map(d -> d.getText() + " (" + d.getScore() + ")").toList()
        );
    }

    @GetMapping("/ask/tuned")
    public Map<String, Object> askTuned(@RequestParam String q,
                                        @RequestParam(defaultValue = "2") int topK,
                                        @RequestParam(defaultValue = "0.5") double threshold) {
        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build())
                .build();

        ChatClientResponse res = gemini.prompt()
                .user(q)
                .advisors(advisor)
                .call().chatClientResponse();

        List<Document> docs = (List<Document>)res.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        return Map.of(
                "answer", res.chatResponse().getResult().getOutput().getText(),
                "retrievedCount", docs.size());
    }

    @GetMapping("/search/filter")
    public List<String> searchFilter(@RequestParam String q, @RequestParam String category) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(q)
                        .topK(3)
                        .filterExpression("category == '" + category + "'")
                        .build())
                .stream().map(d -> d.getText() + " (" + d.getScore() + ")").toList();

//        // 실무 사용법
//        var b = new FilterExpressionBuilder()
//        .filterExpression(b.eq("category", category).build())
    }

    @GetMapping("/ask/filter")
    public String askFilter(@RequestParam String q, @RequestParam String category) {
        return ollama.prompt()
                .user(q)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "category == '" + category + "'"))
                .call().content();
    }

    @GetMapping("/modular")
    public String modular(@RequestParam String q) {
        RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(
                        VectorStoreDocumentRetriever.builder()
                                .vectorStore(vectorStore)
                                .topK(3)
                                .similarityThreshold(0.5)
                                .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)
                        .build()
                ).build();

        return ollama.prompt().user(q).advisors(advisor).call().content();

        // 질문 재작성
//        .queryTransformers(RewriteQueryTransformer.builder()
//                .chatClientBuilder(ollama.mutate())     // 재작성용 LLM (05장의 mutate)
//                .build())


    }


}
