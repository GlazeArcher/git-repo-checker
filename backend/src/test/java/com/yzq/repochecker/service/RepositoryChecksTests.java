package com.yzq.repochecker.service;

import com.yzq.repochecker.service.RepositoryChecks.Deduction;
import com.yzq.repochecker.service.RepositoryChecks.RepositoryFile;
import com.yzq.repochecker.service.RepositoryChecks.Risk;
import com.yzq.repochecker.service.RepositoryChecks.StartupCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryChecksTests {
    @Test
    void identifiesStartupAndReportsRiskLocationsWithoutIncludingMatchedValues() {
        String syntheticToken = "sk-" + "x".repeat(24);
        var fixture = new LinkedHashMap<String, String>();
        fixture.put("README.md", "Install dependencies before running.");
        fixture.put("web/package.json", """
                {"scripts":{"start":"node app.js","dev":"node dev.js","serve":" "}}
                """);
        fixture.put("scripts/run.bat", "@echo off");
        fixture.put("settings.properties", "data=./local\r\nroot=C:\\Users\\demo\\workspace\r\nkey=" + syntheticToken);
        fixture.put(".env.local", "MODE=demo");
        fixture.put(".env.example", "MODE=");
        fixture.put("node_modules/ignored.js", syntheticToken);

        var result = scan(fixture);
        var checks = result.fileChecks();
        assertThat(checks.rootReadmes()).containsExactly("README.md");
        assertThat(checks.dependencyManifests()).containsExactly("web/package.json");
        assertThat(checks.windowsScripts()).containsExactly("scripts/run.bat");
        assertThat(checks.startupEntryScripts()).containsExactly("scripts/run.bat");
        assertThat(checks.startupCommands()).containsExactly(
                new StartupCommand("web/package.json", "npm start"),
                new StartupCommand("web/package.json", "npm run dev"));
        assertThat(checks.contentCoverage().inspectedFiles()).isEqualTo(6);
        assertThat(result.risks()).extracting(Risk::code)
                .containsExactly("TRACKED_ENV", "SECRET_OPENAI", "ABSOLUTE_PATH", "WINDOWS_SCRIPT");
        assertThat(result.risks()).filteredOn(risk -> risk.code().equals("SECRET_OPENAI"))
                .singleElement().satisfies(risk -> {
                    assertThat(risk.path()).isEqualTo("settings.properties");
                    assertThat(risk.line()).isEqualTo(3);
                });
        assertThat(result.toString()).doesNotContain(syntheticToken, "C:\\Users\\demo\\workspace");
        assertThat(result.readiness().score()).isEqualTo(25);
        assertThat(result.readiness().provisional()).isFalse();
    }

    @Test
    void distinguishesInvalidManifestsFromVerifiedAbsence() {
        var invalid = scan(Map.of("package.json", "{} {}", "api/package.json", ""));
        assertThat(invalid.fileChecks().startupCheckIssues()).hasSize(2);
        assertThat(invalid.readiness().provisional()).isTrue();
        assertThat(invalid.risks()).extracting(Risk::code).doesNotContain("STARTUP_MISSING");

        var absent = scan(Map.of("package.json", "{}", "README.md", "Demo"));
        assertThat(absent.fileChecks().startupCheckIssues()).isEmpty();
        assertThat(absent.readiness().provisional()).isFalse();
        assertThat(absent.readiness().deductions())
                .containsExactly(new Deduction("缺少受支持的启动入口", 25));
    }

    @Test
    void reportsOtherSensitivePatternsAndIgnoresRelativePathsAndUrls() {
        var result = scan(Map.of("app/config.txt", """
                url=https://example.test/Users/example
                relative=./home/example
                token=Bearer %s
                cloud=%s
                %s
                linux=/home/demo/cache
                """.formatted("a".repeat(20), "AKID" + "X".repeat(16),
                "-----BEGIN " + "PRIVATE KEY-----")));

        assertThat(result.risks()).filteredOn(risk -> risk.line() != null)
                .extracting(Risk::line).containsExactly(3, 4, 5, 6);
        assertThat(result.risks()).extracting(Risk::code)
                .contains("SECRET_BEARER", "SECRET_AWS", "PRIVATE_KEY", "ABSOLUTE_PATH");
    }

    @Test
    void boundsFileReadsAndDoesNotPenalizeMissingFilesWhenGitTreeIsTruncated() {
        var files = IntStream.range(0, 30)
                .mapToObj(index -> new RepositoryFile("config" + index + ".txt", sha(index), 2))
                .toList();
        var reads = new AtomicInteger();
        var result = RepositoryChecks.scan(files, false, ignored -> {
            reads.incrementAndGet();
            return new byte[] {'o', 'k'};
        });

        assertThat(reads.get()).isEqualTo(RepositoryChecks.MAX_CONTENT_FILES);
        assertThat(result.fileChecks().contentCoverage().skippedFiles()).isEqualTo(6);
        assertThat(result.fileChecks().contentCoverage().complete()).isFalse();
        assertThat(result.readiness().provisional()).isTrue();
        assertThat(result.readiness().deductions()).isEmpty();
    }

    @Test
    void appliesSingleFileAndTotalByteLimits() {
        var files = IntStream.range(0, 10)
                .mapToObj(index -> new RepositoryFile("settings" + index + ".txt", sha(index),
                        index == 0 ? RepositoryChecks.MAX_FILE_BYTES + 1 : RepositoryChecks.MAX_FILE_BYTES))
                .toList();
        var reads = new AtomicInteger();
        var result = RepositoryChecks.scan(files, true, ignored -> {
            reads.incrementAndGet();
            byte[] text = new byte[RepositoryChecks.MAX_FILE_BYTES];
            java.util.Arrays.fill(text, (byte) 'x');
            return text;
        });

        assertThat(reads.get()).isEqualTo(8);
        assertThat(result.fileChecks().contentCoverage().skippedFiles()).isEqualTo(2);
        assertThat(result.readiness().provisional()).isTrue();
    }

    private static RepositoryChecks.ScanResult scan(Map<String, String> contents) {
        var blobs = new LinkedHashMap<String, byte[]>();
        var files = contents.entrySet().stream().map(entry -> {
            String sha = sha(blobs.size());
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            blobs.put(sha, value);
            return new RepositoryFile(entry.getKey(), sha, value.length);
        }).toList();
        return RepositoryChecks.scan(files, true, blobs::get);
    }

    private static String sha(int index) {
        return "%040x".formatted(index);
    }
}
