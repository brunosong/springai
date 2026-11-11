package com.brunosong.springai.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DocumentLoader {

    private final VectorStore vectorStore;

    @Value("classpath:rag/company-policy.txt")
    private Resource policy;

    public DocumentLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    void load() {
        // E: 읽기
        TextReader reader = new TextReader(policy);
        reader.getCustomMetadata().put("source", "company-policy.txt");
        List<Document> documents = reader.get();

        // T: 자르기
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(200).build();
        List<Document> chunks = splitter.apply(documents);

        // L: 저장
        vectorStore.add(chunks);
        log.info("[rag] 사내 규정 {}개 청크 적재", chunks.size());
        chunks.forEach(c -> log.info("[chunk] {}자: {}", c.getText().length(),
                c.getText().substring(0, Math.min(30, c.getText().length())).replace("\n", " ")));
    }


}
