package com.brunosong.springai.embadding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/embedding")
public class EmbeddingController {

    public static final List<String> FAQ = List.of(
            "환불은 결제 후 7일 이내에 고객센터에서 신청할 수 있습니다.",
            "배송은 주문 후 평균 2~3일 걸립니다.",
            "비밀번호를 잊으셨다면 로그인 화면의 '비밀번호 찾기'를 누르세요.",
            "회원 탈퇴는 마이페이지 > 설정에서 할 수 있습니다.",
            "해외 배송은 현재 지원하지 않습니다.",
            "포인트는 결제 금액의 1%가 적립됩니다.",
            "주문 취소는 배송 시작 전까지만 가능합니다.",
            "고객센터 운영 시간은 평일 오전 9시부터 오후 6시까지입니다."
    );

    private final EmbeddingModel embeddingModel;
    private final FaqIndex faqIndex;

    private final ChatClient ollama;

    public EmbeddingController(EmbeddingModel embeddingModel, FaqIndex faqIndex,
                               @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.embeddingModel = embeddingModel;
        this.faqIndex = faqIndex;
        this.ollama = ollama;
    }

    @GetMapping("/embed")
    public Map<String, Object> embed(@RequestParam(defaultValue = "안녕하세요") String text) {

        float[] vector = embeddingModel.embed(text);

        return Map.of(
                "text" , text,
                "dimensions", vector.length,
                "first5", Arrays.copyOf(vector, 5)
        );
    }

    @GetMapping("/similarity")
    public Map<String, Object> similarity(@RequestParam String a, @RequestParam String b) {
        double score = VectorMath.cosineSimilarity(embeddingModel.embed(a), embeddingModel.embed(b));
        return Map.of("a", a, "b", b, "similarity", Math.round(score * 1000) / 1000.0);
    }

    // 키워드 검색
    @GetMapping("/search/keyword")
    public List<String> keywordSearch(@RequestParam String q) {
        return FAQ.stream().filter(f -> f.contains(q)).toList();
    }

    // 의미 검색 (top-k)
    @GetMapping("/search/semantic")
    public List<ScoredText> semanticSearch(@RequestParam String q,
                                           @RequestParam(defaultValue = "3") int k) {

        float[] queryVector = embeddingModel.embed(q);

        return FAQ.stream()
                .map( f -> new ScoredText(f, VectorMath.cosineSimilarity(queryVector, embeddingModel.embed(f))))
                .sorted(Comparator.comparingDouble(ScoredText::score).reversed())
                .limit(k)
                .toList();
    }

    @GetMapping("/batch")
    public Map<String, Object> batch() {
        long t1 = System.currentTimeMillis();
        for (String f : FAQ) embeddingModel.embed(f);          // 하나씩 8번
        long oneByOne = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        List<float[]> all = embeddingModel.embed(FAQ);          // 한 번에
        long batch = System.currentTimeMillis() - t2;

        return Map.of("count", all.size(), "oneByOneMs", oneByOne, "batchMs", batch);
    }

    @GetMapping("/response")
    public Map<String, Object> response(@RequestParam(defaultValue = "안녕하세요") String text) {
        EmbeddingResponse res = embeddingModel.embedForResponse(List.of(text));
        log.info("[embedding] model={}, usage={}", res.getMetadata().getModel(), res.getMetadata().getUsage());
        return Map.of(
                "model", res.getMetadata().getModel(),
                "dimensions", res.getResult().getOutput().length
        );
    }

    @GetMapping("/index/search")
    public List<ScoredText> indexSearch(@RequestParam String q,
                                        @RequestParam(defaultValue = "3") int k,
                                        @RequestParam(defaultValue = "0.5") double threshold) {
        return faqIndex.search(q, k, threshold);
    }

    @PostMapping("/index")
    public String indexAdd(@RequestParam String text) {
        faqIndex.add(text);
        return "추가됨";
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        List<ScoredText> hits = faqIndex.search(q, 2, 0.5);

        String context = hits.stream()
                .map(ScoredText::text).collect(Collectors.joining("\n- ", "- ", ""));

        return ollama.prompt()
                .system(s -> s.text("""
                            너는 쇼핑몰 고객센터 상담원이야. 한국말로 답해.
                            아래 [FAQ]에 있는 내용으로만 답해. FAQ에 없으면 "해당 내용은 고객센터에 문의해 주세요" 라고 답해.
                            [FAQ]
                            {context}
                        """).param("context", hits.isEmpty() ? "(없음)" : context)
                )
                .user(q)
                .call().content();
    }

}
