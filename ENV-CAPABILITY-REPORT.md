# 环境能力只读检查报告：能否实现「自动独立审查」

> 检查时间：2026-10-11 15:10
> 检查性质：**只读**。未安装插件、未修改配置、未发起任何付费请求、未读取任何凭据值。
> 目的：判断能否让开发 Agent 在每轮改完代码 + 跑完测试后，**自动调用另一个 AI 做独立审查**。

---

## 一、结论先行

**能，而且有两种现成路径，都不需要新增依赖：**

| 路径 | 成本 | 独立性 | 推荐度 |
|---|---|---|---|
| **A. 内置子 Agent**（`Agent` 工具） | 零新增成本（同模型额度） | **独立上下文**（非独立模型） | ★★★ 主力 |
| **B. Codex CLI**（已装已登录） | 消耗 ChatGPT 订阅额度 | **独立模型**（跨厂商） | ★★ 重要变更时用 |
| C. GitHub PR + 评论 | 零（已有 MCP） | 人工/AI 均可 | ★ 里程碑用 |

**建议组合**：A 每轮自动 + B 里程碑/高风险变更 + C 发布前。

---

## 二、逐项检查结果

### 2.1 内置子 Agent / 委派 / 多模型能力 —— ✅ 有

| 能力 | 工具 | 证据 |
|---|---|---|
| **子 Agent** | `Agent`（`subagent_type`: `general-purpose` / `Explore` / `Plan` / `sheet-agent` / `doc-writer` 等） | 本会话工具清单 + 本次已实际使用过（曾 spawn `general-purpose` 编译 DSHA-lite） |
| 后台运行 + 完成通知 | `Agent(run_in_background: true)` + `TaskOutput` | 同上 |
| 团队内通信 | `SendMessage`（`message` / `broadcast` / `shutdown_request`） | 工具清单 |
| 任务委派追踪 | `TaskCreate` / `TaskList` / `TaskUpdate` / `TaskGet` | 本会话已用（34~46 号任务） |
| 团队能力开关 | `~/.workbuddy/app/app-config.json` 含键 **`disableAgentTeams`** | 只读该文件顶层键名 |

**关键限制**：子 Agent **用的是同一个模型**（当前 `CODEBUDDY_CURRENT_MODEL_ID = deepseek-v4.1-flash`）
→ 它是「**独立上下文**」而非「**独立模型**」。能发现主 Agent 的遗漏（因为不带主 Agent 的思路惯性），
但**不能**提供跨模型视角。

### 2.2 已配置的模型 API / MCP / AI 接口

#### （a）第三方 AI API Key —— ❌ 没有

| 检查项 | 结果 |
|---|---|
| 环境变量（只看变量名） | 无 `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `DEEPSEEK_API_KEY` 等 |
| `~/.workbuddy/models.json` | 内容为 `[]`（**空**，无自定义模型） |
| `~/.workbuddy/mcp.json` | **不存在**（无自定义 MCP server） |

**含义**：不能直接调"另一个厂商的 API"做审查，除非新增配置（本轮不做）。

#### （b）MCP 服务 —— ✅ 8 个（从工具名核出身份）

| 服务（推断依据=工具名） | 工具数 | 与"审查"的关系 |
|---|---|---|
| **GitHub**（`create_branch` / `add_comment_to_pending_review` / `pull_request` / `add_issue_comment` …） | **46** | ⭐ **可提交 diff、开 PR、发审查评论** |
| **Agent Mail**（`GetMe` / `ListMessages` / `SendMessage` …） | 11 | ⭐ 可把审查请求发给另一个 mail 地址 |
| SheetAgent（`resolve_local_excel` / `read_table` …） | 9 | 表格类产物的复核 |
| WorkBuddy BaaS（`workbuddy_cloudservice_db_*`） | 9 | 后端/数据 |
| 腾讯微云（`tdrive.*`） | 10 | 云盘 |
| 编辑器 SDK（`fetch_editor_state` / `batch_edit` …） | 26 | 本地 Office 文件 |
| Miora（`miora_write_canvas` / `text_to_image` …） | 8 | 画布/图像 |
| 微信支付（`weixinpay_*`） | 4 | 与审查无关 |

会话内状态：**`github`: connected，`agent-mail`: connected**。

#### （c）Codex CLI —— ✅ 已装且已登录

| 项 | 证据 |
|---|---|
| 可执行 | `codex --version` → **`codex-cli 0.144.3`**（只读命令，未触发 API） |
| 位置 | `C:\Users\朱青涛\AppData\Roaming\npm\codex.cmd`（在 PATH 中） |
| 登录 | `~/.codex/auth.json` 存在（3867 字节，**未读取内容**） |
| **认证模式** | 键名显示 `OPENAI_API_KEY: null`，`tokens: {id_token, access_token, refresh_token, account_id}` → **ChatGPT 订阅 OAuth 登录，非 API Key** |
| 自身 MCP | `config.toml` 含 `[mcp_servers.cua_repl]`、`[mcp_servers.node_repl]` |

⚠️ **关于额度的诚实说明**（用户特别提醒）：
`OPENAI_API_KEY` 为 null 说明走的是**订阅登录**，
但**这不等于"可以无限自动审查"** —— 订阅同样有速率/用量限制，
用它做高频自动审查**有触发限流的风险**，且会**消耗用户的订阅额度**。
本报告**不假设**其额度充足。

### 2.3 能否提交代码差异 + 测试报告并取得审查结果 —— ✅ 三条路

| 路径 | 提交物 | 取得结果 | 证据 |
|---|---|---|---|
| **子 Agent** | 直接传上下文（diff 文本 + 测试输出） | 返回值回到主 Agent | `Agent` 工具签名 |
| **GitHub MCP** | 分支 + commit + PR + 评论 | PR 评论 / review 状态 | 46 个工具含 `create_branch` / `add_comment_to_pending_review` |
| **Agent Mail** | 邮件正文 + 附件（`agent_mail_upload_attachment`） | 回信 | 11 个工具含 `SendMessage` / `ListAttachments` |

### 2.4 能否把审查结果自动反馈给开发 Agent —— ✅ 原生支持

- **子 Agent 模式**：`Agent` 的返回值**天然回到主 Agent** → 主 Agent 可据其核实、修复、复测（**闭环最短**）
- 后台 Agent：通过 `SendMessage(to: "main")` 回报
- 任务态：`TaskUpdate` 标记审查任务状态

### 2.5 后台持续运行 / 断点恢复 / 定时任务 / 完成通知

| 能力 | 状态 | 证据 |
|---|---|---|
| 后台执行 | ✅ | `run_in_background`（本会话多次使用） |
| **完成通知** | ✅ | 后台任务结束会以 `<task-notification>` 自动回到会话（本会话多次收到） |
| **定时/周期任务** | ✅ | `automation_update` 工具（`scheduleType: recurring` + RRULE） |
| 任务持久化 | ✅ | `~/.workbuddy/tasks/<会话UUID>/` 存在（含本会话目录） |
| **跨会话断点恢复** | ⚠️ **能力存在但未验证** | 本轮为只读检查，未测试"关掉再开能否续跑" |

### 2.6 ChatGPT 桌面 UI 的可靠性评估（未启动任何 UI 自动化）

| 项 | 观察 |
|---|---|
| ChatGPT 桌面端 | **未运行**（`tasklist` 无 chatgpt 进程） |
| `codex-windows-sandbox-ser` | 有 1 个**服务**进程（沙箱服务，非 UI） |
| Codex 配置目录 | `%APPDATA%\Codex\` 仅含 `web/` 子目录，无 UI 配置 |

**若走 UI 自动化的风险评估**（结论：**不建议**）：
- **登录依赖**：UI 走浏览器/桌面会话，token 过期、二次验证、异地登录都会断
- **可靠性**：Electron/Web 界面元素随版本变动，选择器脆弱
- **误操作风险**：模拟点击可能触发"新对话/发送"等不可逆动作，甚至在错误窗口里输入
- **不可观测**：拿不到结构化的审查结果，只能截图/复制文本
- **与现有能力重复**：既然有 `Agent` + Codex CLI，UI 自动化是**最差的一条路**

---

## 三、推荐方案（最少改动、成本可控）

### 方案 A（主力，立即可用）：内置子 Agent 做独立审查

**改动量**：**零**（不加依赖、不改配置）。只需在开发流程里固定一步。

```
每轮开发完：
  1. 主 Agent 跑测试 → 收集「diff + 测试输出 + 失败原因」
  2. Agent(subagent_type="general-purpose", prompt=
       "你是独立审查者，不参与本代码的编写。以下是本轮变更与测试结果：
        <diff>
        <测试摘要>
        请只做审查，不要改代码。重点核：
        ① 有无把失败改写成成功 ② 有无降低断言/跳过测试
        ③ 有无绕过安全守卫 ④ 有无未处理的异常路径
        ⑤ 结论与证据是否匹配（不许推测）
        输出：问题清单（文件:行 + 严重度 + 证据 + 复现方式）")
  3. 主 Agent 逐条核实 → 修复 → 复测 → 记录
```

**优点**：零成本、闭环最短（返回值直接可用）、可后台跑（`run_in_background`）
**限制**：**同模型**，跨模型盲区发现不了 → 由方案 B 补

### 方案 B（里程碑/高风险变更）：Codex CLI 跨模型审查

**改动量**：小（一个只读 wrapper，不改 Codex 配置）

```
codex exec --sandbox read-only --cd D:\DSHA-lite "<审查提示词 + diff 路径>"
```

**必须遵守**：
- `--sandbox read-only`（**只读**，防它动代码）
- 提示词里明确"只审查、不改代码、不执行网络操作"
- **先确认额度**：不假设订阅额度充足；建议**只在里程碑或高风险变更时**调用
- 首次使用前，建议先跑一次 **`codex exec --sandbox read-only "echo ok"`** 验证可用性与耗时
  （**本报告未执行**，属 NOT TESTED）

### 方案 C（发布前）：GitHub PR 审查

用现有 GitHub MCP 开 PR + 评论留痕，便于人工复核。

---

## 四、明确未做的事（安全边界）

- ❌ 未安装任何插件 / 未新增 MCP / 未修改任何配置
- ❌ 未启动 ChatGPT 桌面 UI 或任何 UI 自动化
- ❌ 未执行 `codex exec`（任何会触发模型调用的命令）
- ❌ 未读取任何凭据值（`auth.json` 只看键名，`mcp.json` 只看字段名）
- ❌ 未发起任何付费请求

## 五、未验证项

| 项 | 状态 |
|---|---|
| 子 Agent 审查的实际质量 | **NOT TESTED**（本报告只确认能力存在） |
| `codex exec` 能否在只读沙箱下正常工作 | **NOT TESTED**（未执行，避免触发额度消耗） |
| ChatGPT 订阅的剩余额度 | **未知**（无法从配置判断，也不应假设） |
| 跨会话断点恢复 | **NOT TESTED** |
| GitHub PR 审查链路端到端 | **NOT TESTED**（未提交任何内容） |
