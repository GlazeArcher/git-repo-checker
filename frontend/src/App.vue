<script setup>
import { ref } from 'vue'

const repositoryUrl = ref('')
const report = ref(null)
const loading = ref(false)
const error = ref('')

const fileCheckItems = [
  { key: 'rootReadmes', title: '根目录 README' },
  { key: 'dependencyManifests', title: '依赖清单' },
  { key: 'windowsScripts', title: 'Windows 专用脚本' },
]

const severityLabels = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' }

const errorMessages = {
  400: '请输入有效的 GitHub 仓库主页链接。',
  404: '找不到仓库或默认分支，请确认链接正确且仓库公开。',
  409: '仓库暂时没有可读取的提交。',
  502: 'GitHub 请求失败，请稍后重试。',
  503: 'GitHub 请求受限，请稍后重试。',
  504: '连接 GitHub 超时或失败，请检查网络后重试。',
}

async function analyzeRepository() {
  if (loading.value) return

  const url = repositoryUrl.value.trim()
  error.value = ''
  report.value = null

  if (!url) {
    error.value = '请输入 GitHub 仓库链接。'
    return
  }

  loading.value = true

  try {
    const response = await fetch('/api/repositories/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ repositoryUrl: url }),
    })

    if (!response.ok) {
      error.value =
          errorMessages[response.status] ??
          `分析请求失败（HTTP ${response.status}），请检查后端服务。`
      return
    }

    const result = await response.json()
    if (!result?.readiness || !result.fileChecks?.contentCoverage || !Array.isArray(result.risks)) {
      error.value = '后端报告格式与当前页面不匹配，请重启后端后重试。'
      return
    }
    report.value = result
  } catch (cause) {
    error.value =
        cause instanceof SyntaxError
            ? '接口返回的数据格式有误，请检查后端响应和 Vite 代理配置。'
            : '无法连接到分析服务，请确认后端已启动。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="checker">
    <header class="page-header">
      <h1>GitHub 仓库接入体检</h1>
      <p>输入公开仓库链接，查看仓库版本和接入体检报告。</p>
    </header>

    <form class="panel" @submit.prevent="analyzeRepository">
      <label for="repository-url">公开仓库链接</label>

      <div class="input-row">
        <input
            id="repository-url"
            v-model="repositoryUrl"
            type="url"
            placeholder="https://github.com/owner/repo"
            required
            :disabled="loading"
        />
        <button type="submit" :disabled="loading">
          {{ loading ? '正在分析…' : '开始体检' }}
        </button>
      </div>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </form>

    <section v-if="report" class="panel report" aria-live="polite">
      <div class="report-header">
        <div>
          <h2>{{ report.repository }}</h2>
          <p class="demo-note">
            已检查 {{ report.fileChecks.inspectedFiles }} 个文件路径 ·
            内容扫描 {{ report.fileChecks.contentCoverage.inspectedFiles }} /
            {{ report.fileChecks.contentCoverage.candidateFiles }} 个候选文件
          </p>
        </div>

        <div class="score">
          <span>接入就绪度</span>
          <strong>{{ report.readiness.score }}<small> / 100</small></strong>
          <meter
            min="0"
            max="100"
            :value="report.readiness.score"
            aria-label="接入就绪度评分"
          ></meter>
          <small v-if="report.readiness.provisional">暂定评分 · 部分检查未完成</small>
        </div>
      </div>

      <dl class="metadata">
        <div>
          <dt>默认分支</dt>
          <dd><code>{{ report.defaultBranch }}</code></dd>
        </div>
        <div>
          <dt>Commit SHA</dt>
          <dd><code>{{ report.commitSha }}</code></dd>
        </div>
      </dl>

      <p v-if="!report.fileChecks.complete" class="demo-note">
        文件清单不完整，以下结果仅覆盖本次已检查的文件。
      </p>

      <p v-if="!report.fileChecks.contentCoverage.complete" class="coverage-note">
        内容扫描范围有限。跳过 {{ report.fileChecks.contentCoverage.skippedFiles }} 个，
        读取或解码失败 {{ report.fileChecks.contentCoverage.failedFiles }} 个。
        {{ report.fileChecks.contentCoverage.stopReason }}
      </p>

      <details v-if="report.readiness.deductions.length" class="deductions">
        <summary>查看评分明细</summary>
        <ul>
          <li v-for="item in report.readiness.deductions" :key="item.label">
            {{ item.label }} · 扣 {{ item.points }} 分
          </li>
        </ul>
      </details>

      <h3>主要风险</h3>
      <ul v-if="report.risks.length" class="risk-list">
        <li
          v-for="risk in report.risks"
          :key="`${risk.code}:${risk.path ?? ''}:${risk.line ?? ''}`"
          class="risk-item"
        >
          <span class="risk-level" :data-severity="risk.severity">
            {{ severityLabels[risk.severity] ?? risk.severity }}
          </span>
          <strong>{{ risk.title }}</strong>
          <p v-if="risk.path">
            <code>{{ risk.path }}{{ risk.line ? `:${risk.line}` : '' }}</code>
          </p>
          <p>{{ risk.suggestion }}</p>
        </li>
      </ul>
      <p v-else class="demo-note">在本次扫描范围内未发现受支持的风险项。</p>

      <h3>文件检查</h3>

      <ul class="risk-list">
        <li v-for="item in fileCheckItems" :key="item.key" class="risk-item">
          <strong>{{ item.title }}</strong>

          <ul v-if="report.fileChecks[item.key].length">
            <li v-for="path in report.fileChecks[item.key]" :key="path">
              <code>{{ path }}</code>
            </li>
          </ul>

          <p v-else>
            {{ report.fileChecks.complete ? '未发现' : '已检查范围内未发现' }}
          </p>
        </li>

        <li class="risk-item">
          <strong>启动入口</strong>
          <ul
            v-if="report.fileChecks.startupCommands.length || report.fileChecks.startupEntryScripts.length"
          >
            <li
              v-for="item in report.fileChecks.startupCommands"
              :key="`${item.path}:${item.command}`"
            >
              <code>{{ item.path }}</code> → <code>{{ item.command }}</code>
            </li>
            <li v-for="path in report.fileChecks.startupEntryScripts" :key="path">
              <code>{{ path }}</code> · 依据文件名识别
            </li>
          </ul>
          <p v-else>在已检查范围内未发现受支持的启动入口。</p>
          <details v-if="report.fileChecks.startupCheckIssues.length" class="deductions">
            <summary>无法确认的 npm 启动入口（{{ report.fileChecks.startupCheckIssues.length }}）</summary>
            <ul>
              <li v-for="item in report.fileChecks.startupCheckIssues" :key="item.path">
                <code>{{ item.path }}</code> · {{ item.reason }}
              </li>
            </ul>
          </details>
        </li>
      </ul>
    </section>
  </main>
</template>

<style scoped>
.checker {
  width: 100%;
  box-sizing: border-box;
  max-width: 820px;
  margin: 56px auto;
  padding: 0 20px;
  color: #17243a;
  font-family: system-ui, sans-serif;
  color-scheme: light;
}

.page-header {
  margin-bottom: 24px;
}

h1,
h2 {
  margin: 0;
  overflow-wrap: anywhere;
  color: inherit;
}

.page-header p,
.demo-note {
  margin: 8px 0 0;
  color: #526174;
}

.panel {
  padding: 24px;
  border: 1px solid #dbe3ec;
  border-radius: 14px;
  background: #fff;
}

label {
  font-weight: 600;
}

.input-row,
.report-header {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.input-row {
  margin-top: 10px;
}

input,
button {
  padding: 10px 14px;
  border-radius: 8px;
  font: inherit;
}

input {
  flex: 1 1 260px;
  min-width: 0;
  border: 1px solid #aebbc9;
  background: #fff;
  color: #17243a;
}

button {
  border: 0;
  background: #2456b2;
  color: #fff;
  cursor: pointer;
}

button:disabled {
  opacity: 0.65;
  cursor: wait;
}

.error {
  margin: 12px 0 0;
  color: #a32323;
}

.report {
  margin-top: 20px;
}

.report-header {
  justify-content: space-between;
  align-items: flex-start;
}

.report-header > div:first-child {
  min-width: 0;
}

.score {
  display: grid;
  gap: 4px;
  padding: 10px 14px;
  border-radius: 8px;
  background: #edf3ff;
  color: #214b9b;
  min-width: 130px;
}

.score strong {
  font-size: 22px;
}

.score meter {
  width: 100%;
  height: 12px;
}

.score small {
  font-size: 13px;
}

.coverage-note {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff4d9;
  color: #664705;
}

.deductions {
  margin-top: 12px;
}

.deductions summary {
  color: #214b9b;
  cursor: pointer;
}

.metadata {
  display: grid;
  gap: 10px;
  margin: 20px 0;
  padding: 14px;
  border-radius: 8px;
  background: #f4f7fa;
}

.metadata dt {
  margin-bottom: 4px;
  font-weight: 600;
}

.metadata dd {
  margin: 0;
}

code {
  display: inline;
  color: inherit;
  background: #eef2f6;
  padding: 2px 4px;
  overflow-wrap: anywhere;
}

.risk-list {
  display: grid;
  gap: 10px;
  padding: 0;
  list-style: none;
}

.risk-item {
  padding: 14px;
  border: 1px solid #e0e7ef;
  border-radius: 8px;
}

.risk-item p {
  margin: 8px 0 0;
}

.risk-level {
  display: inline-block;
  margin-right: 8px;
  padding: 3px 8px;
  border-radius: 6px;
  background: #edf3ff;
  color: #214b9b;
  font-size: 13px;
}

.risk-level[data-severity='MEDIUM'] {
  background: #fff1d6;
  color: #774500;
}

.risk-level[data-severity='HIGH'] {
  background: #fde8e8;
  color: #a32323;
}

.checker {
  text-align: left;
}

.page-header h1 {
  font-size: clamp(28px, 5vw, 42px);
  line-height: 1.2;
  letter-spacing: normal;
}

.page-header p {
  margin-top: 12px;
  line-height: 1.6;
}
</style>
