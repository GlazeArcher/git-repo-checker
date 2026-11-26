# GitHub 仓库接入体检

## 项目简介

输入一个公开 GitHub 仓库的主页链接，查看默认分支指定提交的接入就绪度、扣分明细和主要风险。适用于接手项目、评估本地启动条件前的初步检查。

前端使用 Vue 3 和 Vite，后端使用 Java 17 和 Spring Boot 4.2.0。分析通过 GitHub REST API 获取文件树和文件内容。

## 主要功能

- **文件检查**：识别根目录 README、常见依赖清单和 Windows 专用脚本。
- **启动入口识别**：读取 npm 启动脚本声明，列出支持的 Shell 和 Windows 启动脚本。
- **硬编码路径检查**：定位疑似依赖特定机器的绝对路径，展示文件位置和行号。
- **敏感信息检查**：发现疑似密钥、Bearer 令牌、私钥和被跟踪的 `.env` 配置文件。
- **接入就绪度评分**：展示分数、扣分依据、风险级别和修改建议。
- **版本与覆盖范围展示**：显示报告对应的 Commit SHA，以及本次实际检查的文件范围。

## 运行截图

### 基础体检报告

分析 `octocat/Hello-World`，展示默认分支、Commit SHA、55 分的就绪度结果及扣分明细。

![基础体检报告](docs/images/basic-report.png)

### 启动入口识别

识别 `web/package.json` 中的 `npm start`、`npm run dev`，并列出 `scripts/start.bat`。

![启动入口识别](docs/images/startup-commands.png)

### 风险定位

测试仓库加入绝对路径、合成 Bearer 令牌和 `.env` 后，报告得分为 25，展示风险级别、文件位置和修改建议。令牌样本为测试字符串。

![风险定位报告](docs/images/risk-report.png)

## 快速启动

### 环境与目录

准备 JDK 17+ 和 Node.js 22.12+。首次安装依赖需要访问 Maven 和 npm 仓库。从项目根目录打开两个终端。

```text
git-repo-checker/
├── backend/          Spring Boot 接口、GitHub 请求和规则检查
├── frontend/         Vue 输入表单和报告展示
├── docs/images/      README 演示截图
├── README.md         运行、规则、验收和更新跟踪设计
└── AI_USAGE.md       AI 工具使用、验证方法与纠错记录
```

### 启动后端

后端默认监听 8080。

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

macOS 或 Linux 使用 `sh ./mvnw spring-boot:run`。若 Maven Wrapper 无法从默认地址下载 Maven，可设置环境变量 `MVNW_REPOURL` 为可用的 Maven 仓库镜像，或使用本机 Maven 3.9+ 执行 `mvn spring-boot:run`。

本次 Windows 环境验证过的镜像配置如下，在后端终端执行。

```powershell
$env:MVNW_REPOURL = 'https://maven.aliyun.com/repository/public'
.\mvnw.cmd spring-boot:run
```

### 启动前端

在另一个终端从项目根目录启动前端，打开 Vite 打印的本地地址，通常为 [http://localhost:5173](http://localhost:5173)。

```powershell
cd frontend
npm ci
npm run dev
```

Vite 会将 `/api` 转发到后端。公开仓库体检默认无需 GitHub 凭据。遇到匿名请求限流时，可以等待额度恢复，或在后端进程环境中设置 `REPO_CHECKER_GITHUB_TOKEN` 后重启。令牌仅用于向 GitHub API 认证，应用只分析公开仓库。

### 发起体检

在页面粘贴 `https://github.com/owner/repo` 并点击“开始体检”。输入应为仓库主页，支持末尾的 `.git` 和斜杠。结果包含默认分支、完整 Commit SHA、评分、风险清单和文件检查。按 `Ctrl+C` 停止对应终端中的服务。

### 接口调用

`POST /api/repositories/analyze`，请求类型为 `application/json`。

```json
{
  "repositoryUrl": "https://github.com/octocat/Hello-World"
}
```

响应中的 `repository`、`defaultBranch` 和 `commitSha` 标识报告来源，`fileChecks` 提供文件分类、启动入口与内容覆盖范围，`readiness` 提供分数、暂定标记和扣分明细，`risks` 提供风险类型、严重程度、路径、可用行号和建议。

无效链接返回 400，仓库不存在或非公开返回 404，无提交返回 409，GitHub 请求异常、限流和连接失败分别映射为 502、503、504。读取内容阶段发生限流时，返回已获取的文件证据并标记未完成范围。

## 检查规则和评分

### 检查规则

| 检查项 | 首版规则 |
| --- | --- |
| README 和依赖 | 标出根目录 README 和各目录中的常见依赖清单，例如 `package.json`、`pom.xml`、`requirements.txt`。缺失或全部为空会提示。 |
| 启动入口 | 识别 npm 中显式声明的 `start`、`dev`、`serve`，以及文件名为 `start`、`dev`、`run`、`serve` 的常见 Shell 或 Windows 脚本。 |
| Windows 脚本 | 列出 `.bat`、`.cmd`、`.ps1`、`.vbs`，提示确认跨平台启动方式。 |
| 硬编码路径 | 标记疑似 Windows 盘符路径及常见 POSIX 绝对路径，如 `/Users/`、`/home/`、`/opt/`。 |
| 敏感信息 | 标记疑似 `sk-`、`AKIA`、`ASIA`、`AKID`、Bearer 令牌和私钥，以及被 Git 跟踪的 `.env` 与非模板变体。 |

分析从默认分支的提交进入 Git 文件树，再按文件的 Blob SHA 读取内容。应用执行静态检查。报告仅展示风险类型、文件路径、可用行号和处理建议，匹配到的敏感值留在扫描过程内。每个文本规则每个文件保留第一处命中。依赖清单与启动入口是启发式识别，项目可运行性需要人工验证。检测结果可能包含示例凭据、容器路径等误报。

README 和依赖清单的完整度检查目前依据文件存在及非空状态。npm 启动入口依据脚本声明，Shell 和 Windows 启动入口依据非空文件的名称。工具不会安装目标仓库依赖或执行其中的脚本。评分用于静态检查结果的汇总，高分仍需要结合实际启动验证。

文件树最多处理 10,000 个条目。内容检查优先读取 npm 清单、常见配置和启动脚本，只读取已知类型的 UTF-8 文本，跳过常见依赖目录、构建产物目录和锁文件。单次最多读取 24 个候选文件，每文件最多 128 KiB，累计最多 1 MiB。内容读取阶段的循环在达到约 12 秒后停止发起新请求，已发出的请求使用独立的连接和读取超时。GitHub 返回限流、文件树截断或有内容跳过时，页面会显示实际覆盖范围和暂定评分。

### 评分方式

满分 100，最低为 0。扣分项可以在页面展开查看。

| 发现 | 扣分 |
| --- | --- |
| 缺少非空根目录 README | 15 |
| 缺少受支持的非空依赖清单 | 20 |
| 未发现受支持的启动入口 | 25 |
| 包含 Windows 专用脚本 | 5 |
| 疑似硬编码绝对路径 | 每项 10，最多 30 |
| 疑似敏感信息或 `.env` 入库 | 每项 30，最多 60 |

文件树不完整时无法确定 README、依赖清单或启动入口缺失，这三项暂缓扣分。npm 清单解析失败时也暂缓判定启动入口缺失。暂定分数仅反映已获取的信息。

## 测试与验收

### 自动化验证

在 `backend` 目录运行后端测试。

```powershell
.\mvnw.cmd test
```

已安装本机 Maven 时也可以运行 `mvn test`。在 `frontend` 目录运行生产构建。

```powershell
npm run build
```

2026-11-26 的验证结果为后端 9 个测试通过、失败 0 个，前端生产构建通过。后端测试包括应用启动、文件分类、npm 启动识别、合法与无效 JSON、风险行号、响应敏感值保护、扫描容量限制、限流后保留文件证据，以及公开仓库限制。GitHub 响应使用模拟数据，下载好依赖后这些测试可离线运行。

### 浏览器验收记录

以下结果来自 2026-11-26 在 Windows / Microsoft Edge 上的页面操作及截图，前端地址为 `http://localhost:5173`。测试仓库为 `GlazeArcher/repo-checker-test`，凭据样本使用合成字符串。

| 场景 | 报告提交（前 8 位） | 实际结果 |
| --- | --- | --- |
| 分析 `octocat/Hello-World` | `7fd1a60b` | 默认分支 `master`，扫描 1/1 个候选文件，找到根目录 `README`，缺依赖和启动入口扣 45 分，得分 55。 |
| 分析带 npm 脚本的测试仓库 | `09250442` | 默认分支 `main`，扫描 3/3 个候选文件，找到 `README.md`、`web/package.json` 和 `scripts/start.bat`，识别 `npm start`、`npm run dev`，Windows 脚本扣 5 分，得分 95。 |
| 新增路径与合成 Bearer 测试配置 | `aca01558` | 扫描 4/4 个候选文件，定位 `config/test-fixture.txt:1` 的绝对路径和第 2 行的 Bearer 令牌，扣分为 5 + 10 + 30，得分 55。页面展示类型及位置，未展示令牌原文。 |
| 再新增被跟踪的 `.env` | `6f1f2a6e` | 扫描 5/5 个候选文件，新增 `.env` 风险，敏感信息类累计扣 60 分，合计扣 75 分，得分 25。 |

以上分数对应各自的提交快照。测试仓库继续更新后，文件数、SHA 和评分可能变化。

### 复现步骤

1. 在页面输入 `https://github.com/octocat/Hello-World`，确认显示仓库、默认分支、Commit SHA、根目录 `README` 和评分。
2. 使用自己的公开测试仓库，添加非空 `README.md`、`scripts/start.bat`，以及含 `scripts.start`、`scripts.dev` 的 `web/package.json`，核对文件分类和两条 npm 命令。
3. 新增测试配置，第一行放 `root=C:\Users\demo\workspace`，第二行放 `authorization=Bearer DEMO_ONLY_1234567890_ABCDEFGHIJ`，核对风险位置、建议和扣分。
4. 新增内容仅为 `MODE=test` 的 `.env`，确认报告出现被跟踪的环境配置提示。
5. 输入非 GitHub 链接或一个确认不存在的仓库链接，确认显示错误；随后输入有效链接，检查能否恢复显示报告。

当前截图覆盖前四类场景。无效链接的 HTTP 400 已通过前期接口联调验证，错误提示与恢复、窄窗口布局可在最终页面验收时补充检查。

## 更新跟踪设计

当前每次点击体检都获取最新默认分支提交，页面显示本次分析所基于的 Commit SHA。以下缓存、增量检查和自动更新流程属于后续设计。

1. 查询默认分支的最新 Commit SHA，用仓库标识、Commit SHA、规则版本查找已有报告。三者未变时直接返回缓存。
2. SHA 变化时比较新旧文件树，将新增、删除、改名和 Blob SHA 变化记录下来。相同 Blob SHA 和规则版本复用内容检查。新增或改名路径重新应用路径规则，删除文件移除旧结果。用新文件树重新汇总报告和评分。初次扫描、比较结果截断或规则版本变化时退回受限全量扫描。
3. 报告记录分支、完整 Commit SHA、规则版本和生成时间。完整报告按提交保存，当前报告指向最新一次完整结果。
4. 遇到超时或限流时，保留上次完整报告，标记其提交和生成时间，并提示新提交尚未完成。GitHub 提供 `Retry-After` 时按其等待，否则指数退避。无历史结果时返回部分报告或明确错误。

## 当前限制与 AI 使用

首版支持公开 GitHub 仓库的默认分支。检查覆盖当前提交的受限文件范围，未实现历史提交泄露扫描。npm 以外的启动方式主要依靠已支持的脚本文件名识别。

AI 辅助生成接入建议为后续可选扩展。当前建议由规则生成。自动更新、持久化报告缓存和增量重算的方案已在上节说明，尚未接入运行流程。

开发过程中使用 ChatGPT 和 Codex 辅助拆解任务、编写实现与核对结果。具体使用方式、测试方法，以及发现并修正 Jackson 包名不匹配的记录见 [AI_USAGE.md](AI_USAGE.md)。
