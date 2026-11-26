package com.yzq.repochecker.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class RepositoryChecks {
    static final int MAX_CONTENT_FILES = 24;
    static final int MAX_FILE_BYTES = 128 * 1024;
    static final int MAX_TOTAL_BYTES = 1024 * 1024;
    private static final Duration CONTENT_TIME_BUDGET = Duration.ofSeconds(12);

    private static final Set<String> DEPENDENCY_MANIFESTS = Set.of(
            "package.json", "pom.xml", "build.gradle", "build.gradle.kts", "requirements.txt",
            "pyproject.toml", "pipfile", "go.mod", "cargo.toml", "composer.json", "gemfile");
    private static final Set<String> WINDOWS_EXTENSIONS = Set.of(".bat", ".cmd", ".ps1", ".vbs");
    private static final Set<String> START_SCRIPT_NAMES = Set.of("start", "dev", "run", "serve");
    private static final List<String> NPM_SCRIPTS = List.of("start", "dev", "serve");
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "vendor", "dist", "target", "coverage", ".git", ".next");
    private static final Set<String> LOCK_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock", "cargo.lock", "go.sum");
    private static final Set<String> TEXT_NAMES = Set.of(
            "readme", "dockerfile", "containerfile", "makefile", "gemfile", "pipfile", "go.mod",
            ".npmrc", ".pypirc", ".gitconfig");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".sh", ".bat", ".cmd", ".ps1", ".vbs", ".json", ".yaml", ".yml", ".toml", ".ini",
            ".properties", ".conf", ".cfg", ".xml", ".gradle", ".kts", ".js", ".jsx", ".ts",
            ".tsx", ".vue", ".py", ".java", ".kt", ".go", ".rs", ".rb", ".php", ".cs",
            ".c", ".cpp", ".h", ".sql", ".tf", ".md", ".txt", ".rst", ".pem", ".key");
    private static final Pattern BLOB_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final List<TextRule> TEXT_RULES = List.of(
            new TextRule("ABSOLUTE_PATH", "MEDIUM", "疑似硬编码绝对路径", Pattern.compile(
                    "(?i)(?<![\\w./\\\\-])(?:[a-z]:[\\\\/]+[^\\s\"'<>]+"
                            + "|/(?:Users|home|opt|tmp|var|etc|mnt|srv|usr|root|workspace|app)/[^\\s\"'<>]+)"),
                    "确认路径是否依赖特定机器。优先使用相对路径、环境变量或配置项。"),
            new TextRule("SECRET_OPENAI", "HIGH", "疑似 OpenAI API 密钥",
                    Pattern.compile("(?<![\\w-])sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}(?![\\w-])"),
                    "核实是否为真实密钥。如果已暴露，请轮换密钥并改用环境变量。"),
            new TextRule("SECRET_AWS", "HIGH", "疑似云服务访问密钥 ID",
                    Pattern.compile("(?<![\\w])(?:AKIA|ASIA|AKID)[A-Z0-9]{16}(?![\\w])"),
                    "核实凭据是否仍在使用。若为真实凭据，请撤销或轮换相关密钥。"),
            new TextRule("SECRET_BEARER", "HIGH", "疑似写死的 Bearer 令牌",
                    Pattern.compile("(?i)\\bBearer[ \\t]+[A-Za-z0-9._~+/-]{16,}={0,2}(?![\\w./~-])"),
                    "从环境变量或密钥管理服务加载令牌。若为真实令牌，请轮换。"),
            new TextRule("PRIVATE_KEY", "HIGH", "疑似私钥内容",
                    Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
                    "确认私钥用途。若为真实私钥，请更换密钥并清理历史提交。"));

    private RepositoryChecks() {}

    static ScanResult scan(List<RepositoryFile> files, boolean treeComplete, BlobReader reader) {
        List<String> readmes = new ArrayList<>();
        List<String> manifests = new ArrayList<>();
        List<String> windows = new ArrayList<>();
        List<String> launchScripts = new ArrayList<>();
        List<RepositoryFile> candidates = new ArrayList<>();
        List<Risk> risks = new ArrayList<>();
        boolean usableReadme = false;
        boolean usableManifest = false;
        String firstEnvFile = null;
        int envFiles = 0;

        for (RepositoryFile file : files) {
            String name = file.name();
            String extension = extensionOf(name);
            if (!file.path().contains("/") && (name.equals("readme") || name.startsWith("readme."))) {
                readmes.add(file.path());
                usableReadme |= file.size() != 0;
            }
            if (DEPENDENCY_MANIFESTS.contains(name)) {
                manifests.add(file.path());
                usableManifest |= file.size() != 0;
            }
            if (WINDOWS_EXTENSIONS.contains(extension)) {
                windows.add(file.path());
            }
            if (file.size() != 0 && isLaunchScript(name, extension) && !ignoredDirectory(file.path())) {
                launchScripts.add(file.path());
            }
            if (isEnvFile(name) && !isEnvExample(name)) {
                envFiles++;
                if (firstEnvFile == null) {
                    firstEnvFile = file.path();
                }
            }
            if (!ignoredDirectory(file.path()) && !LOCK_FILES.contains(name)
                    && (TEXT_NAMES.contains(name) || TEXT_EXTENSIONS.contains(extension) || isEnvFile(name))) {
                candidates.add(file);
            }
        }

        candidates.sort(Comparator.comparingInt(RepositoryChecks::priority).thenComparing(RepositoryFile::path));
        var content = scanContents(candidates, treeComplete, reader, risks);
        boolean hasStartup = !content.commands().isEmpty() || !launchScripts.isEmpty();
        if (treeComplete && !usableReadme) {
            risks.add(new Risk("README_MISSING", "MEDIUM", "根目录缺少非空 README",
                    firstPath(readmes), null, "说明项目用途、环境要求、安装和启动步骤。"));
        }
        if (treeComplete && !usableManifest) {
            risks.add(new Risk("DEPENDENCIES_MISSING", "MEDIUM", "未发现受支持的非空依赖清单",
                    firstPath(manifests), null, "提交项目使用的依赖清单，并在 README 说明依赖安装方式。"));
        }
        if (treeComplete && content.startupIssues().isEmpty() && !hasStartup) {
            risks.add(new Risk("STARTUP_MISSING", "MEDIUM", "未发现受支持的启动入口",
                    null, null, "补充启动脚本，或在 README 说明该项目的运行方式。"));
        }
        if (!windows.isEmpty()) {
            risks.add(new Risk("WINDOWS_SCRIPT", "LOW", "包含 " + windows.size() + " 个 Windows 专用脚本",
                    firstPath(windows), null, "确认主要启动流程是否需要 Windows。需要跨平台时，请补充等价命令。"));
        }
        if (envFiles > 0) {
            risks.add(new Risk("TRACKED_ENV", "HIGH", "有 " + envFiles + " 个 .env 配置文件被跟踪",
                    firstEnvFile, null, "核实配置中是否含有真实凭据。提交脱敏模板并忽略本地 .env。"));
        }

        risks.sort(Comparator.comparingInt((Risk risk) -> severityOrder(risk.severity()))
                .thenComparing(Risk::path, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Risk::line, Comparator.nullsLast(Comparator.naturalOrder())));
        var checks = new FileChecks(readmes, manifests, windows, files.size(), treeComplete,
                content.commands(), launchScripts, content.startupIssues(), content.coverage());
        return new ScanResult(checks, score(risks, content.coverage().complete()
                && content.startupIssues().isEmpty()), risks);
    }

    private static ContentResult scanContents(
            List<RepositoryFile> candidates, boolean treeComplete, BlobReader reader, List<Risk> risks) {
        List<StartupCommand> commands = new ArrayList<>();
        List<StartupCheckIssue> startupIssues = new ArrayList<>();
        int attempted = 0;
        int inspected = 0;
        int failed = 0;
        int fetchedBytes = 0;
        String stopReason = null;
        long deadline = System.nanoTime() + CONTENT_TIME_BUDGET.toNanos();

        for (RepositoryFile file : candidates) {
            boolean manifest = file.name().equals("package.json");
            if (stopReason == null && System.nanoTime() - deadline >= 0) {
                stopReason = "已达到本次内容扫描时间上限";
            }
            if (stopReason == null && attempted >= MAX_CONTENT_FILES) {
                stopReason = "已达到本次内容扫描文件数上限";
            }
            if (stopReason != null) {
                addStartupIssue(startupIssues, manifest, file, stopReason);
                continue;
            }
            if (file.size() < 0 || file.size() > MAX_FILE_BYTES || file.size() > MAX_TOTAL_BYTES - fetchedBytes) {
                addStartupIssue(startupIssues, manifest, file, "文件大小未知或超出扫描容量");
                continue;
            }
            if (!BLOB_SHA.matcher(file.sha()).matches()) {
                failed++;
                addStartupIssue(startupIssues, manifest, file, "缺少有效的文件版本标识");
                continue;
            }

            attempted++;
            String body;
            try {
                byte[] bytes = reader.read(file.sha());
                if (bytes.length > MAX_FILE_BYTES || bytes.length > MAX_TOTAL_BYTES - fetchedBytes) {
                    fetchedBytes = MAX_TOTAL_BYTES;
                    throw new BlobReadException("返回内容超出扫描容量", true);
                }
                fetchedBytes += bytes.length;
                body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                if (body.indexOf('\0') >= 0) {
                    throw new BlobReadException("不是支持的文本文件", false);
                }
            } catch (BlobReadException ex) {
                failed++;
                addStartupIssue(startupIssues, manifest, file, ex.getMessage());
                if (ex.stop()) {
                    stopReason = ex.getMessage();
                }
                continue;
            } catch (CharacterCodingException ex) {
                failed++;
                addStartupIssue(startupIssues, manifest, file, "不是支持的 UTF-8 文本");
                continue;
            }

            inspected++;
            if (manifest) {
                inspectPackageJson(file.path(), body, commands, startupIssues);
            }
            inspectText(file.path(), body, risks);
        }
        int skipped = candidates.size() - inspected - failed;
        return new ContentResult(commands, startupIssues, new ContentCoverage(candidates.size(), inspected,
                skipped, failed, stopReason, treeComplete && failed == 0 && skipped == 0));
    }

    private static void inspectPackageJson(
            String path, String body, List<StartupCommand> commands, List<StartupCheckIssue> issues) {
        try {
            String json = body.startsWith("\ufeff") ? body.substring(1) : body;
            JsonNode root = JSON.readTree(json);
            if (!root.isObject()) {
                issues.add(new StartupCheckIssue(path, "package.json 应是 JSON 对象"));
                return;
            }
            JsonNode scripts = root.path("scripts");
            if (scripts.isMissingNode()) {
                return;
            }
            if (!scripts.isObject()) {
                issues.add(new StartupCheckIssue(path, "scripts 应是 JSON 对象"));
                return;
            }
            for (String name : NPM_SCRIPTS) {
                JsonNode command = scripts.path(name);
                if (command.isString() && !command.asString().isBlank()) {
                    commands.add(new StartupCommand(path, name.equals("start") ? "npm start" : "npm run " + name));
                }
            }
        } catch (JacksonException ex) {
            issues.add(new StartupCheckIssue(path, "package.json 格式无效"));
        }
    }

    private static void inspectText(String path, String body, List<Risk> risks) {
        for (TextRule rule : TEXT_RULES) {
            var match = rule.pattern().matcher(body);
            if (match.find()) {
                var breaks = LINE_BREAK.matcher(body).region(0, match.start());
                int line = 1;
                while (breaks.find()) {
                    line++;
                }
                risks.add(new Risk(rule.code(), rule.severity(), rule.title(), path, line, rule.suggestion()));
            }
        }
    }

    private static Readiness score(List<Risk> risks, boolean complete) {
        List<Deduction> deductions = new ArrayList<>();
        addDeduction(risks, deductions, "缺少非空 README", 15, 15, "README_MISSING");
        addDeduction(risks, deductions, "缺少依赖清单", 20, 20, "DEPENDENCIES_MISSING");
        addDeduction(risks, deductions, "缺少受支持的启动入口", 25, 25, "STARTUP_MISSING");
        addDeduction(risks, deductions, "包含 Windows 专用脚本", 5, 5, "WINDOWS_SCRIPT");
        addDeduction(risks, deductions, "疑似硬编码路径", 10, 30, "ABSOLUTE_PATH");
        addDeduction(risks, deductions, "疑似敏感信息或 .env 入库", 30, 60,
                "SECRET_OPENAI", "SECRET_AWS", "SECRET_BEARER", "PRIVATE_KEY", "TRACKED_ENV");
        int points = deductions.stream().mapToInt(Deduction::points).sum();
        return new Readiness(Math.max(0, 100 - points), !complete, deductions);
    }

    private static void addDeduction(
            List<Risk> risks, List<Deduction> deductions, String label, int points, int cap, String... codes) {
        Set<String> accepted = Set.of(codes);
        long count = risks.stream().filter(risk -> accepted.contains(risk.code())).count();
        if (count > 0) {
            deductions.add(new Deduction(label, (int) Math.min(cap, count * points)));
        }
    }

    private static void addStartupIssue(
            List<StartupCheckIssue> issues, boolean manifest, RepositoryFile file, String reason) {
        if (manifest) {
            issues.add(new StartupCheckIssue(file.path(), reason));
        }
    }

    private static int priority(RepositoryFile file) {
        String name = file.name();
        if (name.equals("package.json")) {
            return 0;
        }
        if (isEnvFile(name) || DEPENDENCY_MANIFESTS.contains(name) || TEXT_NAMES.contains(name)
                || isLaunchScript(name, extensionOf(name))) {
            return 1;
        }
        return 2;
    }

    private static boolean isEnvFile(String name) {
        return name.equals(".env") || name.startsWith(".env.");
    }

    private static boolean isEnvExample(String name) {
        return name.endsWith(".example") || name.endsWith(".sample") || name.endsWith(".template");
    }

    private static boolean isLaunchScript(String name, String extension) {
        return (extension.equals(".sh") || WINDOWS_EXTENSIONS.contains(extension))
                && START_SCRIPT_NAMES.contains(name.substring(0, name.length() - extension.length()));
    }

    private static boolean ignoredDirectory(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (IGNORED_DIRECTORIES.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String firstPath(List<String> paths) {
        return paths.isEmpty() ? null : paths.get(0);
    }

    private static int severityOrder(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    record RepositoryFile(String path, String sha, long size) {
        String name() {
            return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        }
    }

    @FunctionalInterface
    interface BlobReader {
        byte[] read(String sha);
    }

    static final class BlobReadException extends RuntimeException {
        private final boolean stop;

        BlobReadException(String reason, boolean stop) {
            super(reason);
            this.stop = stop;
        }

        boolean stop() {
            return stop;
        }
    }

    private record TextRule(String code, String severity, String title, Pattern pattern, String suggestion) {}
    private record ContentResult(
            List<StartupCommand> commands, List<StartupCheckIssue> startupIssues, ContentCoverage coverage) {}

    public record ScanResult(FileChecks fileChecks, Readiness readiness, List<Risk> risks) {}
    public record FileChecks(
            List<String> rootReadmes, List<String> dependencyManifests, List<String> windowsScripts,
            int inspectedFiles, boolean complete, List<StartupCommand> startupCommands,
            List<String> startupEntryScripts, List<StartupCheckIssue> startupCheckIssues,
            ContentCoverage contentCoverage) {}
    public record StartupCommand(String path, String command) {}
    public record StartupCheckIssue(String path, String reason) {}
    public record ContentCoverage(
            int candidateFiles, int inspectedFiles, int skippedFiles, int failedFiles,
            String stopReason, boolean complete) {}
    public record Risk(String code, String severity, String title, String path, Integer line, String suggestion) {}
    public record Deduction(String label, int points) {}
    public record Readiness(int score, boolean provisional, List<Deduction> deductions) {}
}
