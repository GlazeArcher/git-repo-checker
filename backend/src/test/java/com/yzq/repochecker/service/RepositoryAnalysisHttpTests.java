package com.yzq.repochecker.service;

import com.yzq.repochecker.controller.RepositoryAnalysisController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryAnalysisHttpTests {
    private static final String API = "https://api.github.com";
    private static final String REPO = API + "/repos/demo/example";
    private static final String MANIFEST_SHA = "a".repeat(40);
    private static final String CONFIG_SHA = "b".repeat(40);
    private static final String README_SHA = "c".repeat(40);
    private static final String TREE_SHA = "d".repeat(40);
    private static final String COMMIT_SHA = "e".repeat(40);
    private static final String REQUEST = """
            {"repositoryUrl":"https://github.com/demo/example"}
            """;

    private final RestClient.Builder github = RestClient.builder().baseUrl(API);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(github).build();
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RepositoryAnalysisController(new GitHubRepositoryService(github.build())))
            .build();

    @Test
    void returnsScoreAndRedactedFindingsFromCommitTreeThroughHttpEndpoint() throws Exception {
        expectTree();
        expectBlob(MANIFEST_SHA, """
                {"scripts":{"start":"node --version"}}
                """);
        String syntheticKey = "sk-" + "X".repeat(24);
        expectBlob(CONFIG_SHA, "local=./data\nsecret=" + syntheticKey);
        expectBlob(README_SHA, "Demo");

        mvc.perform(post("/api/repositories/analyze").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value(COMMIT_SHA))
                .andExpect(jsonPath("$.fileChecks.startupCommands[0].command").value("npm start"))
                .andExpect(jsonPath("$.fileChecks.contentCoverage.inspectedFiles").value(3))
                .andExpect(jsonPath("$.readiness.score").value(70))
                .andExpect(jsonPath("$.readiness.provisional").value(false))
                .andExpect(jsonPath("$.risks[0].path").value(".env.example"))
                .andExpect(jsonPath("$.risks[0].line").value(2))
                .andExpect(content().string(not(containsString(syntheticKey))));
        server.verify();
    }

    @Test
    void retainsFileTreeEvidenceOnGitHubRateLimitAndSkipsRemainingBlobCalls() throws Exception {
        expectTree();
        server.expect(requestTo(REPO + "/git/blobs/" + MANIFEST_SHA))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        mvc.perform(post("/api/repositories/analyze").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileChecks.rootReadmes[0]").value("README"))
                .andExpect(jsonPath("$.fileChecks.contentCoverage.failedFiles").value(1))
                .andExpect(jsonPath("$.fileChecks.contentCoverage.skippedFiles").value(2))
                .andExpect(jsonPath("$.fileChecks.startupCheckIssues[0].path").value("web/package.json"))
                .andExpect(jsonPath("$.readiness.provisional").value(true));
        server.verify();
    }

    @Test
    void rejectsPrivateRepositoryBeforeRequestingContents() throws Exception {
        server.expect(requestTo(REPO)).andRespond(withSuccess("""
                {"full_name":"demo/example","default_branch":"main","private":true}
                """, MediaType.APPLICATION_JSON));

        mvc.perform(post("/api/repositories/analyze").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isNotFound());
        server.verify();
    }

    private void expectTree() {
        server.expect(requestTo(REPO)).andRespond(withSuccess("""
                {"full_name":"demo/example","default_branch":"main","private":false}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/commits/heads%2Fmain")).andRespond(withSuccess("""
                {"sha":"%s","commit":{"tree":{"sha":"%s"}}}
                """.formatted(COMMIT_SHA, TREE_SHA), MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "/git/trees/" + TREE_SHA + "?recursive=1")).andRespond(withSuccess("""
                {"truncated":false,"tree":[
                    {"type":"blob","mode":"100644","path":"README","sha":"%s","size":4},
                    {"type":"blob","mode":"100644","path":".env.example","sha":"%s","size":48},
                    {"type":"blob","mode":"100644","path":"web/package.json","sha":"%s","size":38}
                ]}
                """.formatted(README_SHA, CONFIG_SHA, MANIFEST_SHA), MediaType.APPLICATION_JSON));
    }

    private void expectBlob(String sha, String body) {
        String content = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(REPO + "/git/blobs/" + sha)).andRespond(withSuccess("""
                {"encoding":"base64","content":"%s"}
                """.formatted(content), MediaType.APPLICATION_JSON));
    }
}
