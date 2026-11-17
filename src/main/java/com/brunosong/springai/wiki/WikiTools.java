package com.brunosong.springai.wiki;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * 위키(마크다운 폴더)를 읽기만 하는 도구 모음.
 * 에이전트는 이 도구로 "찾기 → 목차 보기 → 필요한 절만 읽기"를 스스로 한다.
 */
@Slf4j
public class WikiTools {

    static final int MAX_CHARS = 3000;      // 한 번에 돌려주는 최대 글자 수 (문맥 예산)
    private static final int MAX_RESULTS = 8;

    private final Path root;
    private final List<String> readLog = new CopyOnWriteArrayList<>(); // 실제로 읽은 "문서 > 절"

    public WikiTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 이번 요청에서 에이전트가 실제로 읽은 절 목록 (출처 검사용) */
    public List<String> readLog() {
        return List.copyOf(readLog);
    }

    @Tool(description = "위키 전체에서 키워드가 들어간 문서를 찾는다. 결과는 문서 이름, 맞은 줄 수 , 맞을 줄 일부다. 처음에는 항상 이 도구로 찾는다.")
    public String searchWiki(@ToolParam(description = "찾을 단어 하나. 문장이 아니라 핵심 단어") String keyword) {
        log.info("[tool] searchWiki({})", keyword);

        // 모델이 "Advisor order" 처럼 여러 단어를 넘겨도 단어별로 나눠서 찾는다.
        // 통째로 비교하면 그 문장이 그대로 적힌 문서만 걸리고, 단어가 떨어져 있는 진짜 설명 문서는 놓친다.
        List<String> terms = Stream.of(keyword.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(t -> !t.isBlank())
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return "검색어가 비었다. 핵심 단어 하나를 넣어라.";
        }

        // termCount: 문서에 나온 서로 다른 검색어 수 (많을수록 위로), count: 맞은 줄 수
        record Hit(String doc, int termCount, long count, List<String> lines) {
        }

        List<Hit> hits = new ArrayList<>();
        for (Path file : allDocs()) {
            List<String> original = readLines(file);
            List<String> lower = original.stream().map(l -> l.toLowerCase(Locale.ROOT)).toList();

            int termCount = (int) terms.stream()
                    .filter(t -> lower.stream().anyMatch(l -> l.contains(t)))
                    .count();
            if (termCount == 0) continue;

            List<String> matched = new ArrayList<>();
            for (int i = 0; i < lower.size(); i++) {
                String l = lower.get(i);
                if (terms.stream().anyMatch(l::contains)) matched.add(original.get(i));
            }
            // 미리보기는 검색어가 많이 들어간 줄부터
            List<String> preview = matched.stream()
                    .sorted(Comparator.comparingLong((String l) -> terms.stream()
                            .filter(l.toLowerCase(Locale.ROOT)::contains).count()).reversed())
                    .limit(2)
                    .map(l -> cut(l.strip(), 80))
                    .toList();
            hits.add(new Hit(docName(file), termCount, matched.size(), preview));
        }

        if (hits.isEmpty()) {
            return "'" + keyword + "' 가 들어간 문서가 없다. 다른 단어(영어/한글, 더 짧은 단어)로 다시 찾아라.";
        }

        hits.sort(Comparator.comparingInt(Hit::termCount).reversed()
                .thenComparing(Comparator.comparingLong(Hit::count).reversed()));

        StringBuilder sb = new StringBuilder("검색 결과 (검색어 " + terms + ", 상위 "
                + Math.min(MAX_RESULTS, hits.size()) + "개 / 전체 " + hits.size() + "개)\n");
        hits.stream().limit(MAX_RESULTS).forEach(h ->
                sb.append("- ").append(h.doc())
                        .append(" (검색어 ").append(h.termCount()).append('/').append(terms.size())
                        .append(", ").append(h.count()).append("줄) : ").append(h.lines()).append('\n'));
        return sb.toString();
    }

    @Tool(description = "문서의 목차(제목 줄 목록)를 보여준다. 문서를 통째로 읽지 말고 먼저 목차를 본다.")
    public String listSections(@ToolParam(description = "문서 이름 (searchWiki 결과에 나온 이름)") String doc) {
        log.info("[tool] listSections({})", doc);
        Path file = resolve(doc);
        if (file == null) return notFound(doc);
        List<String> headings = headings(readLines(file)).stream()
                .map(h -> h.text())
                .toList();
        return docName(file) + " 목차 (" + headings.size() + "개)\n" + String.join("\n", headings);
    }

    @Tool(description = "문서에서 절 하나만 읽는다. 절 제목은 listSections 결과에 나온 제목을 그대로 쓴다. 하위 절까지 함께 돌려준다.")
    public String readSection(@ToolParam(description = "문서 이름 (searchWiki 결과에 나온 이름)") String doc,
                              @ToolParam(description = "읽을 절 제목 (listSections 결과의 한 줄, # 은 있어도 없어도 된다)") String section) {
        log.info("[tool] readSection({}, {})", doc, section);
        Path file = resolve(doc);
        if (file == null) return notFound(doc);

        List<String> lines = readLines(file);
        List<Heading> headings = headings(lines);
        String want = stripHashes(section).toLowerCase(Locale.ROOT);

        // 정확히 같은 제목 우선, 없으면 부분 일치
        Heading target = headings.stream()
                .filter(h -> stripHashes(h.text()).toLowerCase(Locale.ROOT).equals(want))
                .findFirst()
                .or(() -> headings.stream()
                        .filter(h -> stripHashes(h.text()).toLowerCase(Locale.ROOT).contains(want))
                        .findFirst())
                .orElse(null);
        if (target == null) {
            return "'" + docName(file) + "' 에 '" + section + "' 절이 없다. listSections 로 정확한 제목을 확인해라.";
        }

        // 다음 "같거나 더 높은 수준"의 제목 직전까지가 이 절의 범위
        int end = headings.stream()
                .filter(h -> h.line() > target.line() && h.level() <= target.level())
                .mapToInt(Heading::line)
                .findFirst()
                .orElse(lines.size());

        String body = String.join("\n", lines.subList(target.line(), end));
        readLog.add(docName(file) + " > " + stripHashes(target.text()));

        if (body.length() > MAX_CHARS) {
            return body.substring(0, MAX_CHARS)
                    + "\n…(잘림: 전체 " + body.length() + "자 중 " + MAX_CHARS + "자. 더 필요하면 하위 절을 따로 읽어라)";
        }
        return body;
    }

    private static String stripHashes(String s) {
        return s.strip().replaceFirst("^#+\\s*", "").strip();
    }

    record Heading(int line, int level, String text) {}

    /** 코드 블록(```) 안의 # 은 제목이 아니므로 건너뛴다 */
    static List<Heading> headings(List<String> lines) {
        List<Heading> result = new ArrayList<>();
        boolean inCode = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("```")) inCode = !inCode;
            if (inCode || !line.startsWith("#")) continue;
            int level = 0;
            while (level < line.length() && line.charAt(level) == '#') level++;
            if (level < line.length() && line.charAt(level) == ' ') {
                result.add(new Heading(i, level, line));
            }
        }
        return result;
    }

    /** 문서 이름 → 실제 파일. 위키 폴더 밖은 절대 열지 않는다 */
    Path resolve(String doc) {
        String name = doc.strip().replace("[[", "").replace("]]", "");
        if (!name.endsWith(".md")) name = name + ".md";
        Path direct = root.resolve(name).normalize();
        if (!direct.startsWith(root)) {
            log.warn("[tool] 위키 밖 경로 차단: {}", doc);
            return null;
        }
        if (Files.isRegularFile(direct)) return direct;
        String fileName = Path.of(name).getFileName().toString();
        return allDocs().stream()
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                .findFirst().orElse(null);
    }

    List<Path> allDocs() {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String docName(Path file) {
        String n = file.getFileName().toString();
        return n.substring(0, n.length() - 3);
    }

    private String notFound(String doc) {
        return "'" + doc + "' 문서가 없다. searchWiki 로 정확한 문서 이름을 먼저 찾아라.";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

}
