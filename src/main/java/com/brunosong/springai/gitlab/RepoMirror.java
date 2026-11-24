package com.brunosong.springai.gitlab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MR 의 커밋을 봇 서버의 로컬 저장소(bare)로 받아 온다.
 * 받아 온 폴더를 14장 ReviewTools 에 그대로 넘긴다 → 리뷰 도구는 한 줄도 안 바꾼다.
 */
@Component
public class RepoMirror {

    /** git 명령 하나가 이보다 오래 걸리면 끊는다 */
    private static final long GIT_TIMEOUT_MINUTES = 2;

    private final Path workdir;
    private final String gitlabUrl;
    private final String token;

    public RepoMirror(@Value("${gitlab.workdir:build/gitlab-repos}") String workdir,
                      @Value("${gitlab.url}") String gitlabUrl,
                      @Value("${gitlab.token}") String token) {
        this.workdir = Path.of(workdir).toAbsolutePath().normalize();
        this.gitlabUrl = gitlabUrl;
        this.token = token;
    }

    // ========== 큰 흐름 ==========

    /**
     * MR 의 커밋을 받아 와서 그 폴더를 돌려준다. 프로젝트마다 폴더 하나를 계속 쓴다.
     *   refs/merge-requests/{iid}/head : GitLab 이 MR 마다 만들어 두는 ref (포크에서 온 MR 도 된다)
     *   target 브랜치                   : base_sha 가 여기에 있다
     */
    public Path fetch(GitLabApi.Project project, GitLabApi.MergeRequest mr) {
        Path dir = prepareRepo(project.id());

        git(dir, "fetch", "--quiet", "--no-tags", repoUrl(project),
                "+refs/merge-requests/" + mr.iid() + "/head:refs/mr/" + mr.iid(),
                "+refs/heads/" + mr.targetBranch() + ":refs/target/" + mr.targetBranch());

        // 받아 온 것에 리뷰할 두 커밋이 실제로 있는지 확인 (없으면 git 이 실패 → 예외)
        git(dir, "cat-file", "-e", mr.diffRefs().baseSha() + "^{commit}");
        git(dir, "cat-file", "-e", mr.diffRefs().headSha() + "^{commit}");
        return dir;
    }

    /**
     * git fetch 할 주소. API 의 http_url_to_repo 를 쓰지 않고 설정한 gitlab.url 로 직접 만든다.
     *   - http_url_to_repo 는 GitLab 의 external_url 기준이라 봇 서버에서 안 닿을 수 있다 (예: http://gitlab.local)
     *   - 토큰은 항상 설정한 GitLab 으로만 간다. 다른 서버로 새 나갈 길이 없다
     */
    private String repoUrl(GitLabApi.Project project) {
        return gitlabUrl + "/" + project.path() + ".git";
    }

    /** 프로젝트 폴더. 처음이면 빈 bare 저장소를 만든다 */
    private Path prepareRepo(long projectId) {
        Path dir = workdir.resolve("project-" + projectId);
        if (Files.exists(dir.resolve("HEAD"))) return dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        git(dir, "init", "--bare", "--quiet");
        return dir;
    }

    // ========== git 실행 ==========

    /** git 명령을 실행한다. 실패하거나 시간이 넘으면 예외 */
    private void git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().putAll(authEnv());

        Path output = null;
        try {
            // 출력은 임시 파일로 받는다. 파이프로 읽으면 프로세스가 끝날 때까지 막혀서 아래 시간 제한이 듣지 않는다
            output = Files.createTempFile("git-", ".log");
            pb.redirectOutput(output.toFile());

            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException("git " + args[0] + " 시간 초과");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("git " + args[0] + " 실패: " + Files.readString(output).strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            if (output != null) output.toFile().delete();
        }
    }

    /**
     * 토큰을 넣는 환경 변수 (git 2.31+).
     * 명령줄(다른 사용자도 볼 수 있다)이나 .git/config(디스크에 남는다)에 넣지 않고, 이번 실행에만 http 헤더를 붙인다.
     */
    private Map<String, String> authEnv() {
        String basic = Base64.getEncoder().encodeToString(("oauth2:" + token).getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http.extraHeader",
                "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic,
                "GIT_TERMINAL_PROMPT", "0");   // 인증 실패 시 비밀번호를 물으며 멈추지 않게
    }
}
