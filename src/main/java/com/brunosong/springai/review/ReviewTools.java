package com.brunosong.springai.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

@Slf4j
public class ReviewTools {

    static final int MAX_CHARS = 4000;   // diff 한 번에 돌려주는 최대 글자 수
    static final int MAX_LINES = 120;    // 파일 한 번에 돌려주는 최대 줄 수
    static final int MAX_HITS = 20;      // 검색 결과 최대 줄 수

    /** 커밋 이름으로 허용하는 글자: 영문·숫자·_ . / ~ ^ - (공백, =, ; 등은 안 됨) */
    private static final Pattern SAFE_REF = Pattern.compile("[\\w./~^-]+");

    private final Path repo;
    private final String from;
    private final String to;
    private final List<String> viewed = new CopyOnWriteArrayList<>();   // 에이전트가 실제로 본 것

    public ReviewTools(Path repo, String from, String to) {
        this.repo = repo.toAbsolutePath().normalize();
        this.from = checkRef(from);
        this.to = checkRef(to);
    }

    /** 사용자가 넘긴 커밋 이름이 git 옵션(--output=...)이나 이상한 글자가 아닌지 확인 */
    static String checkRef(String ref) {
        if (ref == null || ref.startsWith("-") || !SAFE_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("허용하지 않는 커밋 이름: " + ref);
        }
        return ref;
    }

    public String range() {
        return from + ".." + to;
    }

    /** 이번 리뷰에서 에이전트가 실제로 열어 본 것 */
    public List<String> viewed() {
        return List.copyOf(viewed);
    }

    // ========== 도구 ① 바뀐 파일 목록 ==========

    @Tool(description = "이번 리뷰 범위에서 바뀐 파일 목록과 추가/삭제 줄 수를 보여준다. 리뷰는 이 도구로 시작한다.")
    public String listChangedFiles() {
        log.info("[tool] listChangedFiles()");

        List<String> files = changedFiles();
        if (files.isEmpty()) return range() + " 범위에 바뀐 파일이 없다.";

        StringBuilder sb = new StringBuilder("바뀐 파일 (" + files.size() + "개, 범위 " + range() + ")\n");
        for (String line : git("diff", "--numstat", range()).strip().split("\n")) {
            String[] p = line.split("\t");
            sb.append("- ").append(p[2]).append(" (+")
                    .append(p[0]).append(" -").append(p[1]).append(")\n");
        }
        return sb.toString();
    }

    @Tool(description = "바뀐 파일 하나의 diff(바뀐 부분)을 보여준다. " +
            "+ 는 추가된 줄, -는 지워진 줄이다. 파일 경로는 listChangedFiles 결과에 나온 경로를 그대로 쓴다.")
    public String readDiff(@ToolParam(description = "파일 경로 (listChangedFiles 결과의 경로)") String file) {
        log.info("[tool] readDiff({})", file);
        if (!changedFiles().contains(file)) {
            return "'" + file + "' 는 이번 범위에서 바뀐 파일이 아니다. listChangedFiles로 경로를 확인해라";
        }

        viewed.add("diff " + file);

        String diff = withLineNumbers(git("diff", range(), "--", file));
        if (diff.length() > MAX_CHARS) {
            return diff.substring(0, MAX_CHARS)
                    + "\n…(잘림: 전체 " + diff.length() + "자 중 " + MAX_CHARS + "자. 나머지는 readFile 로 줄 번호를 정해 읽어라)";
        }
        return diff;
    }

    // ========== 도구 ③ 파일 일부 읽기 ==========

    @Tool(description = "리뷰 대상 버전의 파일을 줄 번호와 함께 읽는다. diff 만으로 앞뒤 맥락이 부족할 때 쓴다. 한 번에 최대 120줄.")
    public String readFile(@ToolParam(description = "저장소 기준 파일 경로 (예: src/main/java/.../Foo.java)") String file,
                           @ToolParam(description = "시작 줄 번호 (1부터)") int fromLine,
                           @ToolParam(description = "끝 줄 번호") int toLine) {
        log.info("[tool] readFile({}, {}, {})", file, fromLine, toLine);
        String blocked = blockReason(file);
        if (blocked != null) {
            log.warn("[tool] 읽기 차단: {} ({})", file, blocked);
            return "'" + file + "' 는 읽을 수 없다: " + blocked;
        }
        List<String> lines = fileLines(file);
        if (lines == null) return "'" + file + "' 파일이 없다. listChangedFiles 나 searchCode 로 경로를 확인해라.";

        int start = Math.max(1, fromLine);
        int end = Math.min(lines.size(), Math.min(toLine, start + MAX_LINES - 1));
        if (start > lines.size()) return "'" + file + "' 는 " + lines.size() + "줄까지 있다.";

        viewed.add("file " + file + ":" + start + "-" + end);
        StringBuilder sb = new StringBuilder(file + " (" + start + "~" + end + "줄 / 전체 " + lines.size() + "줄)\n");
        for (int i = start; i <= end; i++) {
            sb.append(String.format("%4d | %s%n", i, lines.get(i - 1)));
        }
        return sb.toString();
    }

    // ========== 도구 ④ 코드 검색 ==========

    @Tool(description = "리뷰 대상 버전의 Java 코드 전체에서 글자를 찾는다. 바뀐 메서드를 다른 곳에서 쓰는지 확인할 때 쓴다. 결과는 '파일:줄: 내용'.")
    public String searchCode(@ToolParam(description = "찾을 글자 (예: 메서드 이름)") String keyword) {
        log.info("[tool] searchCode({})", keyword);
        if (keyword == null || keyword.isBlank()) return "찾을 글자가 비었다.";
        // -e 뒤에 두면 keyword 가 '-' 로 시작해도 옵션으로 읽히지 않는다
        String out = gitOrEmpty("grep", "-n", "-I", "-F", "-e", keyword, to, "--", "*.java");
        if (out.isBlank()) return "'" + keyword + "' 를 쓰는 곳이 없다.";
        String[] hits = out.strip().split("\n");
        StringBuilder sb = new StringBuilder("'" + keyword + "' 검색 결과 (" + hits.length + "줄)\n");
        for (int i = 0; i < Math.min(hits.length, MAX_HITS); i++) {
            sb.append(hits[i].substring(to.length() + 1)).append('\n');      // 앞의 "커밋:" 제거
        }
        if (hits.length > MAX_HITS) sb.append("…(").append(hits.length - MAX_HITS).append("줄 더 있음. 더 좁은 글자로 찾아라)\n");
        return sb.toString();
    }

    List<String> changedFiles() {
        String out = git("diff", "--name-only", range());
        return out.isBlank() ? List.of() : List.of(out.strip().split("\n"));
    }

    String git(String... args) {
        Result run = run(args);
        if (run.exit() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 실패: " + run.out());
        }
        return run.out();
    }

    private record Result(int exit, String out) {}

    private Result run(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false"));
        cmd.addAll(List.of(args));

        try {
            Process p = new ProcessBuilder(cmd).directory(repo.toFile())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            return new Result(p.waitFor(), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ========== 아래는 도구가 아닌 내부 메서드 (모델은 모른다) ==========

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    /**
     * diff 의 각 줄 앞에 "새 버전 기준 줄 번호"를 붙인다.
     * 모델에게 @@ 헤더를 보고 줄 번호를 세게 하면 자주 틀린다. 계산은 코드가 한다.
     *   @@ -10,2 +12,3 @@     →  그대로
     *   " 문맥"                →  "  12   문맥"
     *   "+추가"                →  "  13 + 추가"
     *   "-삭제"                →  "     - 삭제"   (새 버전에는 없는 줄이라 번호 없음)
     */
    static String withLineNumbers(String diff) {
        StringBuilder sb = new StringBuilder();
        int next = -1;   // 다음 줄의 새 버전 줄 번호. 헤더 구간에서는 -1
        for (String line : diff.split("\n", -1)) {
            var m = HUNK.matcher(line);
            if (m.matches()) {
                next = Integer.parseInt(m.group(1));
                sb.append(line).append('\n');
            } else if (next < 0 || line.startsWith("\\")) {
                sb.append(line).append('\n');                       // diff --git, index, ---, +++ 등 헤더
            } else if (line.startsWith("+")) {
                sb.append(String.format("%5d + %s%n", next++, line.substring(1)));
            } else if (line.startsWith("-")) {
                sb.append(String.format("%5s - %s%n", "", line.substring(1)));
            } else if (line.startsWith(" ")) {
                sb.append(String.format("%5d   %s%n", next++, line.substring(1)));
            } else if (line.startsWith("diff --git")) {
                next = -1;
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 결과가 없을 때 0이 아닌 값으로 끝나는 명령(grep, show)용 */
    String gitOrEmpty(String... args) {
        Result r = run(args);
        return r.exit() == 0 ? r.out() : "";
    }

    /** 읽으면 안 되는 파일이면 이유를, 괜찮으면 null */
    String blockReason(String file) {
        Path p = repo.resolve(file).normalize();
        if (!p.startsWith(repo)) return "저장소 밖 경로";
        String name = p.getFileName().toString().toLowerCase();
        if (file.contains(".git/") || name.endsWith(".env") || name.contains("-local.")
                || name.endsWith(".key") || name.endsWith(".pem")) {
            return "비밀 값이 있을 수 있는 파일";
        }
        return null;
    }

    /** 리뷰 대상 버전(to)의 파일 내용. 없으면 null */
    List<String> fileLines(String file) {
        String out = gitOrEmpty("show", to + ":" + file);
        return out.isEmpty() ? null : out.lines().toList();
    }

}
