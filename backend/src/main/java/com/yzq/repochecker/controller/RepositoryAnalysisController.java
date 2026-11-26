package com.yzq.repochecker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.yzq.repochecker.service.GitHubRepositoryService;
import com.yzq.repochecker.service.RepositoryChecks.FileChecks;
import com.yzq.repochecker.service.RepositoryChecks.Readiness;
import com.yzq.repochecker.service.RepositoryChecks.Risk;


@RestController
@RequestMapping("/api/repositories")
public class RepositoryAnalysisController {

    private final GitHubRepositoryService githubRepositoryService;

    public RepositoryAnalysisController(GitHubRepositoryService githubRepositoryService) {
        this.githubRepositoryService = githubRepositoryService;
    }

    private static final Pattern GITHUB_REPOSITORY_URL = Pattern.compile(
            "https://github\\.com/([a-zA-Z0-9-]+)/([a-zA-Z0-9_.-]+)/?"
    );

    @PostMapping("/analyze")
    public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
        String url = request.repositoryUrl() == null
                ? ""
                : request.repositoryUrl().trim();

        Matcher matcher = GITHUB_REPOSITORY_URL.matcher(url);
        if (!matcher.matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "请输入 GitHub 仓库主页链接"
            );
        }

        String repositoryName = matcher.group(2);
        if (repositoryName.endsWith(".git")) {
            repositoryName = repositoryName.substring(0, repositoryName.length() - 4);
        }
        if (repositoryName.isEmpty() || repositoryName.equals(".") || repositoryName.equals("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "仓库名称无效"
            );
        }

        var metadata = githubRepositoryService.getMetadata(
                matcher.group(1), repositoryName
        );

        return new AnalysisResponse(
                metadata.fullName(),
                metadata.defaultBranch(),
                metadata.commitSha(),
                metadata.scan().fileChecks(),
                metadata.scan().readiness(),
                metadata.scan().risks()
        );
    }

    public record AnalysisRequest(String repositoryUrl) {}

    public record AnalysisResponse(
            String repository,
            String defaultBranch,
            String commitSha,
            FileChecks fileChecks,
            Readiness readiness,
            List<Risk> risks
    ) {}
}
