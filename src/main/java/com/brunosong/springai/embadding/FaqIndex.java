package com.brunosong.springai.embadding;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class FaqIndex {

    record Entry(String text, float[] vector) {}

    private final EmbeddingModel embeddingModel;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public FaqIndex(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    void init() {
        List<float[]> vectors = embeddingModel.embed(EmbeddingController.FAQ);
        for (int i = 0; i < EmbeddingController.FAQ.size(); i++) {
            entries.add(new Entry(EmbeddingController.FAQ.get(i), vectors.get(i)));
        }
        log.info("[index] FAQ {}개 임베딩 완료", entries.size());
    }

    public void add(String text) {
        entries.add(new Entry(text, embeddingModel.embed(text)));
    }

    public List<ScoredText> search(String q, int k, double threshold) {
        float[] qv = embeddingModel.embed(q);
        return entries.stream()
                .map(e -> new ScoredText(e.text(), VectorMath.cosineSimilarity(qv, e.vector())))
                .filter(s -> s.score() >= threshold)
                .sorted(Comparator.comparingDouble(ScoredText::score).reversed())
                .limit(k)
                .toList();
    }

}
