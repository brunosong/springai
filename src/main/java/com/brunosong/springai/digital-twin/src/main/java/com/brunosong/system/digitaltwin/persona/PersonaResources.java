package com.brunosong.system.digitaltwin.persona;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 페르소나를 구성하는 원본 자료를 기동 시 한 번 읽어 둔다. */
@Component
public class PersonaResources {

    static final String NO_RESUME = "이력서 자료 없음";

    private final String facts;
    private final String fullName;
    private final String name;
    private final String summary;
    private final String style;
    private final String resume;

    public PersonaResources(ObjectMapper objectMapper) {
        this.facts = readClasspath("data/facts.json");
        this.summary = readClasspath("data/summary.txt");
        this.style = readClasspath("data/style.txt");
        this.resume = readResume("data/saramin.pdf");

        Map<String, Object> parsed = parseFacts(objectMapper);
        this.fullName = String.valueOf(parsed.get("full_name"));
        this.name = String.valueOf(parsed.get("name"));
    }

    public String facts() {
        return facts;
    }

    public String fullName() {
        return fullName;
    }

    public String name() {
        return name;
    }

    public String summary() {
        return summary;
    }

    public String style() {
        return style;
    }

    public String resume() {
        return resume;
    }

    private Map<String, Object> parseFacts(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(facts, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("data/facts.json 을 읽을 수 없습니다", e);
        }
    }

    private String readClasspath(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("클래스패스 자료를 읽을 수 없습니다: " + path, e);
        }
    }

    /** 이력서 PDF 는 개인 자료라 깃에 올리지 않는다. 없으면 이력서 없이 동작한다. */
    private String readResume(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return NO_RESUME;
        }
        try (InputStream in = resource.getInputStream();
             var document = Loader.loadPDF(in.readAllBytes())) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException("이력서 PDF 를 읽을 수 없습니다: " + path, e);
        }
    }
}
