package com.yzq.repochecker.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.yzq.repochecker.service.RepositoryChecks.RepositoryFile;
import com.yzq.repochecker.service.RepositoryChecks.ScanResult;

@Service
public class GitHubRepositoryService {

    private final RestClient github;

    private static final int MAX_TREE_ENTRIES = 10_000;
    private static final int MAX_ENCODED_BLOB_CHARS = 192 * 1024;

    @Autowired
    public GitHubRepositoryService(@Value("${REPO_CHECKER_GITHUB_TOKEN:}") String token) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(8_000);

        var builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("User-Agent", "git-repo-checker");
        if (!token.isBlank()) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(token.trim()));
        }
        this.github = builder.build();
    }

    GitHubRepositoryService(RestClient github) {
        this.github = github;
    }

    public RepositoryMetadata getMetadata(String owner, String repo) {
        try {
            Map<?, ?> repository = github.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            String fullName = requiredString(repository, "full_name");
            String defaultBranch = requiredString(repository, "default_branch");
            if (!Boolean.FALSE.equals(repository.get("private"))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仅支持公开仓库");
            }

            Map<?, ?> commit = github.get()
                    .uri("/repos/{owner}/{repo}/commits/{ref}",
                            owner, repo, "heads/" + defaultBranch)
                    .retrieve()
                    .body(Map.class);

            String commitSha = requiredString(commit, "sha");
            Map<?, ?> commitDetails = requiredObject(commit, "commit");
            Map<?, ?> commitTree = requiredObject(commitDetails, "tree");
            String treeSha = requiredString(commitTree, "sha");

            ScanResult scan = inspectTree(owner, repo, treeSha);
            return new RepositoryMetadata(fullName, defaultBranch, commitSha, scan);
        } catch (RestClientResponseException ex) {
            throw translateGitHubError(ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT, "连接 GitHub 超时或失败");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "无法读取 GitHub 返回的数据");
        }
    }

    private static String requiredString(Map<?, ?> body, String field) {
        if (body != null && body.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "GitHub 响应缺少字段 " + field);
    }

    private static ResponseStatusException translateGitHubError(
            RestClientResponseException ex) {
        return switch (ex.getStatusCode().value()) {
            case 403, 429 -> new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "GitHub 拒绝请求或触发限流");
            case 404 -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "仓库或默认分支不存在，或当前不可公开访问");
            case 409 -> new ResponseStatusException(
                    HttpStatus.CONFLICT, "仓库暂时没有可读取的提交");
            default -> new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "GitHub 请求失败");
        };
    }

    public record RepositoryMetadata(
            String fullName,
            String defaultBranch,
            String commitSha,
            ScanResult scan
    ) {}

    private ScanResult inspectTree(String owner, String repo, String treeSha) {
        Map<?, ?> body = github.get()
                .uri("/repos/{owner}/{repo}/git/trees/{treeSha}?recursive=1",
                        owner, repo, treeSha)
                .retrieve()
                .body(Map.class);

        if (body == null
                || !(body.get("tree") instanceof List<?> entries)
                || !(body.get("truncated") instanceof Boolean truncated)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "GitHub 返回的文件树格式有误");
        }

        List<RepositoryFile> files = new ArrayList<>();
        int inspectedEntries = Math.min(entries.size(), MAX_TREE_ENTRIES);

        for (int index = 0; index < inspectedEntries; index++) {
            if (!(entries.get(index) instanceof Map<?, ?> entry)
                    || !"blob".equals(entry.get("type"))
                    || "120000".equals(entry.get("mode"))) {
                continue;
            }

            files.add(new RepositoryFile(requiredString(entry, "path"),
                    entry.get("sha") instanceof String sha ? sha : "",
                    entry.get("size") instanceof Number size ? size.longValue() : -1));
        }

        boolean complete = !truncated && entries.size() <= MAX_TREE_ENTRIES;
        return RepositoryChecks.scan(files, complete, sha -> readBlob(owner, repo, sha));
    }

    private byte[] readBlob(String owner, String repo, String sha) {
        try {
            Map<?, ?> blob = github.get()
                    .uri("/repos/{owner}/{repo}/git/blobs/{sha}", owner, repo, sha)
                    .retrieve()
                    .body(Map.class);
            if (blob == null || !"base64".equals(blob.get("encoding"))
                    || !(blob.get("content") instanceof String content)
                    || content.length() > MAX_ENCODED_BLOB_CHARS) {
                throw new RepositoryChecks.BlobReadException("文件内容格式异常或过大", false);
            }
            return Base64.getDecoder().decode(content.replace("\n", "").replace("\r", ""));
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            boolean stop = status == 403 || status == 429;
            throw new RepositoryChecks.BlobReadException(
                    stop ? "GitHub 拒绝访问或限流，已停止读取后续文件" : "GitHub 文件读取失败", stop);
        } catch (ResourceAccessException ex) {
            throw new RepositoryChecks.BlobReadException("GitHub 连接超时或失败，已停止读取后续文件", true);
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new RepositoryChecks.BlobReadException("GitHub 文件响应或编码有误", false);
        }
    }

    private static Map<?, ?> requiredObject(Map<?, ?> body, String field) {
        if (body != null && body.get(field) instanceof Map<?, ?> object) {
            return object;
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "GitHub 响应缺少对象 " + field);
    }
}
