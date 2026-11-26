# ai-chat

「全栈与 Agent 方向实习生 8 周培养计划」实践项目主仓库：Vue3 + Spring Boot + DeepSeek API。阶段一已交付流式对话与公文结构化抽取；阶段二已完成知识库上传、A/B 双库、后端 RAG 流式问答、前端引用交互、15 题 A/B 评测，以及带格式/术语校验、工具兜底与人工确认导出的 Dify Workflow；S9 已完成文档、DSL、数据清单与四批回归收口，下一步 S10 干净环境彩排、最终材料和 `stage2-v1.0` 标签。

> 2026-11-19：仓库由 `ai-chat-stage1` 更名为 `ai-chat`；阶段一 tag 内容保留为历史快照，GitHub 旧链接自动重定向。

| 项目 | 内容 |
|---|---|
| GitHub | https://github.com/aily-rx/ai-chat |
| 阶段一交付 tag | `stage1-v1.4`（包含协作人 PR #4 交叉评审记录；`stage1-v1.3`、`stage1-v1.2`、`stage1-v1.1`、初始里程碑 `stage1-v1` 保留） |
| 阶段二交付 tag | `stage2-v1.0`（开发中；需求见根目录 `docs/07-阶段二需求文档.md`） |
| 主链路 | 阶段一：Vue3（5173）→ Spring Boot（8080）→ DeepSeek API；阶段二 RAG：Vue3 → Spring Boot → Dify Chatflow（A/B 应用）→ Dify 知识库；S7/S8 Workflow：前端 → Spring Boot → Dify Workflow → 后端格式/术语工具 → human_input 人工确认 → Markdown/DOCX 导出 |
| 交付状态 | 阶段一 S0–S8 已完成；阶段二 S0–S9 已完成（S7 双路径/工具兜底、S8 暂停确认/驳回/导出、S9 文档/DSL/数据清单/四批回归均通过），下一步 S10 干净环境彩排与 `stage2-v1.0` |

## 一、功能与请求链路

```text
浏览器 http://localhost:5173
        │  POST /api/chat/stream（SSE，fetch 读取流）
        ▼
Spring Boot http://localhost:8080
        │  OpenAI 兼容 /chat/completions（stream=true）
        ▼
DeepSeek API

阶段二 RAG：
浏览器 → POST /api/rag/chat/stream（SSE）→ Spring Boot → Dify Chatflow（A/B 应用）→ Dify 知识库
```

阶段一已实现：

- 流式对话：页面逐字显示，支持 Markdown、错误提示、停止生成；
- Prompt 变量：`style=formal/concise`，同一问题可对比正式/简洁风格；
- 结构化抽取：`POST /api/extract`，固定五字段 JSON，解析失败重试并兜底；
- 健康检查、统一响应、traceId、全局异常处理、CORS；
- Dify 独立交付物：Chatbot「公文问答助手」。

阶段二 RAG 主链路（S2–S6）：

- 知识库文档上传与状态列表：`GET/POST /api/kb/documents`，前端“知识库”页支持类型/大小校验、索引轮询与错误展示；
- 20 份真实文本 PDF 已分别入 A/B 两个数据集，Dify 状态均为 `completed`；
- 后端 RAG 流式问答：`POST /api/rag/chat/stream`，按 `datasetId` 路由到 A/B Chatflow，SSE 顺序为 `message* → citations → done`；
- 引用含文档名、段落序号、摘录和分数，`page=null`；无引用不出成功终态；无答案固定拒答并返回空引用；
- 前端“知识库问答”页：A/B 知识库切换、逐字流式输出、发送/停止、引用编号与卡片；点击编号滚动到对应卡片并高亮 2 秒；拒答与错误态分开展示，不伪造引用。

阶段二已实现（S7）：

- 格式检查工具：`POST /api/format/check`，检查标题、文种、主送机关、落款、日期、正文缩进与序号连续性；只报告问题，不修改原文；
- 规范用语检查工具：`POST /api/terms/check`，内置错别字/规范用词词表并支持自定义覆盖，返回命中位置与上下文；
- Dify Workflow「公文起草与校验-Workflow」：开始 → 草稿判断/知识检索 → LLM 生成 → 格式校验 → 条件分支 → 术语检查 → 兜底 → 输出；
- 后端 Workflow 代理：`POST /api/workflow/run/stream`，SSE 顺序为 `node* → message* → done`；工具失败进入 Dify fail-branch，状态 `partial-succeeded` 仍正常转发兜底文本；
- DSL 与技能文档：`dify/dsl/`、`docs/skills/official-doc-format.md`。

阶段二已实现（S8）：

- Dify Workflow 在 `human_input` 节点暂停；后端保存 `runId`、状态、草稿、工具结果、历史与 traceId，SSE 终态为 `done.status=WAITING_CONFIRM`，不向前端或接口返回 `form_token`；
- 运行状态查询：`GET /api/workflow/{runId}`，返回 `RUNNING/WAITING_CONFIRM/SUCCEEDED/FAILED/REJECTED`、草稿、工具结果、节点快照、确认结果与状态历史；
- 人工确认：`POST /api/workflow/{runId}/confirm`，支持 `approve/modify/reject`；修改后内容非空且 ≤50000 字；非法动作、重复确认、过期或记录不存在返回业务码 `5005`；
- 导出：`GET /api/workflow/{runId}/export?format=md|docx`；Markdown P0，DOCX 使用 Apache POI；仅 `SUCCEEDED` 可导出，未确认、已驳回、失败或记录不存在返回 `5006`；
- 前端“公文工作流”页：发起/停止、节点进度、格式/用语工具结果、待确认草稿编辑、通过/修改/驳回、状态历史与下载按钮；
- Dify 发布版本 `s8-v1.0`，新增人工确认节点；DSL 见 `dify/dsl/official-doc-workflow-s8.yml`。

> 运行状态当前保存在后端内存，重启后清空；演示中途不要重启后端。确认提交成功后 Dify 异步恢复运行，后端不再消费恢复流。

## 二、技术栈与版本要求

| 组件 | 版本/要求 |
|---|---|
| 前端 | Vue 3.5 + Vite 8，marked、DOMPurify |
| 后端 | Spring Boot 3.3.5 + Java 17 |
| 构建 | Maven 3.9+ |
| Node.js | **`^20.19.0` 或 `>=22.12.0`**（Vite 8 要求，推荐 22 LTS） |
| 包管理 | npm |
| 模型 API | 阶段一：DeepSeek OpenAI 兼容接口，默认 `deepseek-chat`；阶段二 RAG/Workflow：Dify 内配置 `deepseek-v4-flash` |
| Dify | 1.19.0（Docker Compose；阶段二 RAG 与 Workflow 主链路依赖） |
| Git | 任意近期版本 |

检查环境：

```bash
java -version
mvn -v
node -v
npm -v
```

## 三、目录结构

```text
ai-chat/
├─ frontend/                         # Vue3 + Vite
│  ├─ src/api/                       # chat / extract / health / kb / rag / workflow 请求封装
│  ├─ src/components/                # 消息、输入框、引用卡片、抽取结果等组件
│  ├─ src/composables/               # useChatStream / useRagChat / useWorkflow，SSE 状态管理
│  ├─ src/views/                     # 聊天页、知识库页、RAG 问答页、抽取页、公文工作流页
│  ├─ src/mock/                      # 无后端时的演示数据
│  ├─ .env.example                   # VITE_API_BASE_URL / VITE_USE_MOCK
│  ├─ vite.config.js                 # 5173 端口 + /api 代理到 8080
│  └─ package.json
├─ backend/                          # Spring Boot 3 + JDK 17
│  ├─ src/main/java/com/ailyrx/aichat/
│  │  ├─ chat/                       # /api/chat/stream
│  │  ├─ kb/                         # /api/kb/documents
│  │  ├─ rag/                        # /api/rag/chat/stream、Dify 流式客户端、引用映射
│  │  ├─ workflow/                   # /api/workflow/*、状态机、人工确认、Markdown/DOCX 导出
│  │  ├─ check/                      # /api/format/check、/api/terms/check
│  │  ├─ extract/                    # /api/extract
│  │  ├─ health/                     # /api/health
│  │  ├─ llm/                        # DeepSeek 调用封装
│  │  ├─ dify/                       # Dify 数据集 API 适配
│  │  ├─ prompt/                     # Prompt 模板加载
│  │  ├─ common/                     # 统一响应、错误码、全局异常
│  │  └─ config/                     # traceId、CORS、线程池
│  ├─ src/main/resources/application.yml
│  ├─ src/main/resources/prompts/    # chat-system.txt / extract-fields.txt
│  └─ pom.xml
├─ dify/dsl/                          # S7/S8 Workflow DSL 与导入说明
├─ scripts/                           # Dify 导入、S6 评测、S9 回归脚本
├─ prompts/                          # 3 个 Prompt 样例
├─ docs/                             # 接口、测试、Dify、PR、演示文档
├─ notes/                            # 学习笔记
├─ logs/                             # 本地回归/运行日志（已 gitignore）
├─ .env.example                      # 后端环境变量样例
├─ .gitignore
└─ README.md
```

## 四、环境准备与端口

| 端口 | 服务 | 说明 |
|---|---|---|
| `5173` | 前端 Vite | 严格端口；被占用时启动失败 |
| `8080` | 后端 Spring Boot | 可通过 `SERVER_PORT` 修改 |
| `80` | Dify（nginx） | 仅启动 Dify 时占用 |

准备事项：

1. 安装 JDK 17、Maven 3.9+、Node.js（`^20.19.0` 或 `>=22.12.0`）、npm、Git；
2. 准备一个 DeepSeek API Key，只写入本地 `.env`，不要提交；
3. Dify 演示还需要 Docker Desktop，并确保 WSL2/虚拟化正常；
4. Windows 下建议统一使用 `localhost`，避免 `localhost` 与 `127.0.0.1` 解析差异导致 CORS 或代理问题。

## 五、配置：`.env` 样例

项目有两份独立的环境变量文件：

- 项目根目录 `.env`：后端读取，包含模型 Key；
- `frontend/.env`：前端读取，只包含页面配置，**不能放模型 Key**。

两份真实 `.env` 都已被 `.gitignore` 忽略，仓库中只提交 `.env.example` 占位符。

### 5.1 后端 `.env`

```bash
# 从项目根目录的 .env.example 复制为 .env
cp .env.example .env
```

`.env` 样例（把 `LLM_API_KEY` 换成自己的真实 Key）：

```bash
# 后端监听端口
SERVER_PORT=8080

# DeepSeek OpenAI 兼容接口地址
LLM_BASE_URL=https://api.deepseek.com

# 真实 Key 只放在本地 .env，禁止提交
LLM_API_KEY=sk-xxxxxxxx-placeholder

# 模型名称
LLM_MODEL=deepseek-chat

# 非流式请求/抽取超时时间（秒）
LLM_TIMEOUT_SECONDS=60

# 结构化抽取温度；0.0 降低随机性，提高 JSON 稳定性
LLM_EXTRACT_TEMPERATURE=0.0

# Dify Service API（数据集范围；知识库上传/列表）
DIFY_BASE_URL=http://localhost/v1
DIFY_DATASET_API_KEY=
DIFY_DEFAULT_DATASET_ID=
DIFY_DATASET_A_ID=
DIFY_DATASET_B_ID=
DIFY_POLL_INTERVAL_MS=2000
DIFY_POLL_TIMEOUT_MS=180000
DIFY_CONNECT_TIMEOUT_SECONDS=10
DIFY_REQUEST_TIMEOUT_SECONDS=60
DIFY_MAX_TOKENS=800
DIFY_CHUNK_OVERLAP=100

# Dify Service API（应用范围；RAG 问答。B 应用默认，A 应用单独配置）
DIFY_APP_API_KEY=
DIFY_APP_API_KEY_A=
DIFY_CHAT_TIMEOUT_SECONDS=120

# Dify Service API（Workflow 应用；S7/S8 公文工作流）
DIFY_WORKFLOW_API_KEY=
DIFY_WORKFLOW_TIMEOUT_SECONDS=120
```

> Dify 两类 Key 都由本地后端 `.env` 注入，禁止提交或写入前端。真实数据集 ID 可写入清单，但真实 Key 不得出现在文档、日志、脚本或对话记录中。

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `SERVER_PORT` | 否 | `8080` | 后端监听端口 |
| `LLM_BASE_URL` | 否 | `https://api.deepseek.com` | OpenAI 兼容接口地址 |
| `LLM_API_KEY` | **是** | 无 | DeepSeek Key；未配置时调用会失败 |
| `LLM_MODEL` | 否 | `deepseek-chat` | 模型名称 |
| `LLM_TIMEOUT_SECONDS` | 否 | `60` | 模型调用超时时间 |
| `LLM_EXTRACT_TEMPERATURE` | 否 | `0.0` | 结构化抽取的模型温度，降低随机性 |
| `DIFY_BASE_URL` | 否 | `http://localhost/v1` | Dify Service API 地址 |
| `DIFY_DATASET_API_KEY` | 知识库功能必填 | 无 | Dify 数据集 API Key；不能与 App Key 混用 |
| `DIFY_DEFAULT_DATASET_ID` | RAG 缺省时必填 | 无 | 默认数据集，当前为 B 库 |
| `DIFY_DATASET_A_ID` / `DIFY_DATASET_B_ID` | RAG 必填 | 无 | A/B 数据集 ID，用于 `datasetId` 白名单和 Key 路由 |
| `DIFY_APP_API_KEY` | RAG 必填 | 无 | B 应用 `RAG对话` 的 Service API Key |
| `DIFY_APP_API_KEY_A` | A 库问答必填 | 无 | A 应用 `RAG对话-A` 的 Service API Key |
| `DIFY_CHAT_TIMEOUT_SECONDS` | 否 | `120` | RAG 流式请求超时；与 SSE 120s 对齐 |
| `DIFY_POLL_INTERVAL_MS` | 否 | `2000` | 文档索引进度轮询间隔 |
| `DIFY_POLL_TIMEOUT_MS` | 否 | `180000` | 文档索引轮询超时 |
| `DIFY_CONNECT_TIMEOUT_SECONDS` | 否 | `10` | Java → Dify 连接超时 |
| `DIFY_REQUEST_TIMEOUT_SECONDS` | 否 | `60` | 数据集/文档请求超时（不含 RAG/Workflow 流） |
| `DIFY_MAX_TOKENS` | 否 | `800` | 文档索引分段上限；B 库默认使用 |
| `DIFY_CHUNK_OVERLAP` | 否 | `100` | 文档索引分段重叠 |
| `DIFY_WORKFLOW_API_KEY` | Workflow 必填 | 无 | `公文起草与校验-Workflow` 的 Service API Key；不能与数据集/App Key 混用 |
| `DIFY_WORKFLOW_TIMEOUT_SECONDS` | 否 | `120` | Workflow 请求超时 |

后端由 `application.yml` 通过 `optional:file:./.env` 加载配置，因此推荐在**项目根目录**执行启动命令，并确保 `.env` 位于项目根目录。修改 `.env` 后需要重启后端。

### 5.2 前端 `.env`

```bash
# 从 frontend/.env.example 复制为 frontend/.env
cd frontend
cp .env.example .env
```

`.env` 样例：

```bash
# 前端统一走 Vite 代理，/api 转发到 http://localhost:8080
VITE_API_BASE_URL=/api

# false=连接真实后端（默认）；true=使用内置 Mock，无需后端
VITE_USE_MOCK=false
```

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `/api` | 接口前缀，开发时由 Vite 代理到 8080 |
| `VITE_USE_MOCK` | `false` | `true` 时使用内置 Mock，适合无后端演示 |

前端不持有、也不需要任何模型 Key；模型调用只发生在后端。

### 5.3 密钥安全检查

```bash
# 确认 .env 没有被 Git 跟踪
git ls-files | grep -E '(^|/)\.env$' || echo "no .env tracked"

# 确认仓库内容没有真实 sk- Key
git grep -nE 'sk-[A-Za-z0-9]{20,}' || echo "no real key in tracked files"
```

预期：没有 `.env` 被跟踪；只允许 `.env.example` 中的 `sk-xxxxxxxx-placeholder` 占位符。

## 六、启动步骤

### 6.1 获取代码

```bash
git clone --depth 1 https://github.com/aily-rx/ai-chat.git
cd ai-chat
```

已有仓库则更新默认分支：

```bash
git fetch origin
git checkout main
git pull --ff-only
```

阶段一历史快照仍可通过 `stage1-v1.4` tag 查看；`stage2-v1.0` 将在 S10 完成干净环境彩排后创建。S9 收口后的阶段二代码位于默认分支。

### 6.2 启动后端

在项目根目录执行：

```bash
# 1. 准备后端配置
cp .env.example .env
# 编辑 .env，填入 LLM_API_KEY

# 2. 运行测试（可选，首次会下载 Maven 依赖）
mvn -f backend/pom.xml test

# 3. 启动后端
mvn -f backend/pom.xml spring-boot:run
```

保持该终端运行。另开终端验证：

```bash
curl -i http://localhost:8080/api/health
```

预期返回 HTTP 200：

```json
{"code":0,"message":"ok","data":"ok","traceId":"a1b2c3d4"}
```

响应头同时包含 `X-Trace-Id`。

### 6.3 启动前端

另开终端：

```bash
cd frontend
npm install
cp .env.example .env
npm run dev
```

浏览器打开 **http://localhost:5173**。默认 `VITE_USE_MOCK=false`，页面通过 Vite 代理连接 `http://localhost:8080/api` 的真实后端。

如需在无后端时演示流式对话、停止生成、正式/简洁切换和结构化抽取（含失败样例），将 `frontend/.env` 改为：

```bash
VITE_USE_MOCK=true
```

修改后重启 Vite。该模式仅用于演示，不代表真实链路。“知识库问答”页必须连接真实后端与 Dify；Mock 模式会禁用输入并明确提示，不生成伪造回答或引用。

### 6.4 启动 Dify（阶段二 RAG 依赖）

Dify 不在本仓库内，部署目录以当前机器为准：

```bash
cd E:/Dify/dify/docker
docker compose up -d
docker compose ps
```

等待 `docker-api-1` 变为 `healthy` 后，打开 **http://localhost/**。阶段一 Chatbot「公文问答助手」是独立交付物；阶段二“知识库问答”页依赖 Dify 中的 `RAG对话`（B 库）与 `RAG对话-A`（A 库）两个 Chatflow 应用以及对应数据集。S7/S8 Workflow 还依赖 `公文起草与校验-Workflow`（应用 ID `39259284-937e-41de-b975-a093f8da7a14`）及后端 8080 端口；S8 发布版本 `s8-v1.0` 已包含 `human_input` 人工确认节点。阶段一“流式对话”仍由 Vue3 → Spring Boot → DeepSeek 完成，不经过 Dify。

> 换一台机器演示时，只拉本仓库不会带过去 Dify、应用和数据集；需要提前部署 Dify 并按文档导入 DSL 与资料。

### 6.5 停止服务

- 前端/后端：在对应终端按 `Ctrl+C`；
- Dify：

```bash
cd E:/Dify/dify/docker
docker compose down
```

不要随意执行 `docker compose down -v`，该命令会删除数据卷，导致 Dify 应用和账号数据丢失。

## 七、接口清单与 curl 示例

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/health` | 健康检查 |
| `POST` | `/api/chat/stream` | 阶段一流式对话，SSE 返回 |
| `POST` | `/api/extract` | 公文五要素结构化抽取 |
| `GET` | `/api/kb/documents` | 知识库文档列表 |
| `POST` | `/api/kb/documents` | 上传知识库文档（multipart，pdf/docx/txt/md，≤20MB） |
| `POST` | `/api/rag/chat/stream` | RAG 流式问答，SSE 返回 `message/citations/done/error` |
| `POST` | `/api/format/check` | 公文格式检查，返回问题列表，不修改原文 |
| `POST` | `/api/terms/check` | 规范用语/错别字检查，返回命中位置与建议 |
| `POST` | `/api/workflow/run/stream` | 公文 Workflow 流式代理，SSE 返回 `node/message/done/error` |
| `GET` | `/api/workflow/{runId}` | 查询运行状态、草稿、工具结果、确认结果与历史 |
| `POST` | `/api/workflow/{runId}/confirm` | 人工确认，`action=approve/modify/reject` |
| `GET` | `/api/workflow/{runId}/export` | 导出已确认公文，`format=md`（默认）或 `docx` |
| `POST` | `/api/chat` | 阶段一未实现（可选调试接口） |

> 接口契约以本 README 与 [接口文档](docs/api.md) 的 Markdown、curl 和 SSE 示例为准。本项目为本地单用户演示，不引入 springdoc，也不维护单独的 OpenAPI YAML；S9 用脚本校验控制器路径、环境变量、错误码与文档一致性。

### 7.1 健康检查

```bash
curl -i http://localhost:8080/api/health
```

### 7.2 流式对话

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"用三句话介绍RAG","style":"formal"}'
```

预期收到多条 `event: message`，并以 `event: done` 结束。将 `style` 改为 `concise` 可对比输出风格。

SSE 事件格式：

```text
event: message
data: {"delta":"关于"}

event: done
data: {"traceId":"a1b2c3d4"}

event: error
data: {"code":5001,"message":"模型调用失败","traceId":"a1b2c3d4"}
```

### 7.3 结构化抽取

```bash
curl -X POST http://localhost:8080/api/extract \
  -H "Content-Type: application/json" \
  -d '{"text":"关于开展安全生产检查的通知\n各部门：\n……\nXX办公室\n2026年11月1日"}'
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "docType": "通知",
    "title": "关于开展安全生产检查的通知",
    "recipient": "各部门",
    "signature": "XX办公室",
    "date": "2026-11-01"
  },
  "traceId": "a1b2c3d4"
}
```

五个字段固定为 `docType`、`title`、`recipient`、`signature`、`date`；无法识别时返回 `null`，字段名不会省略。解析失败重试 1 次后仍失败返回 `code=5003`，模型调用失败返回 `code=5001`。

### 7.4 RAG 流式问答

前置：Dify 已启动，后端 `.env` 已配置 A/B 数据集 ID、`DIFY_APP_API_KEY` 与 `DIFY_APP_API_KEY_A`。

```bash
curl -N -X POST http://localhost:8080/api/rag/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d '{"question":"2027年元旦放假几天？"}'
```

`datasetId` 可省略（默认 B 库）；显式值接受 `A`/`B` 别名或后端配置的实际数据集 ID，最终归一化到 A/B 白名单，其他值返回 HTTP 400 + `4000`。

正常事件顺序：

```text
event: message
data: {"delta":"根据资料，"}

...

event: citations
data: {"items":[{"docId":"...","docName":"...pdf","segmentIndex":1,"excerpt":"...","page":null,"score":0.83}]}

event: done
data: {"runId":"...","traceId":"a1b2c3d4","elapsedMs":2656}
```

- 拒答不是错误：回答固定话术，`citations.items=[]`，随后 `done`；
- 非拒答但引用为空，或 Dify/网络异常：`event: error`（5002）为终止事件，不再发送 `done`；
- 前端 SSE 读取不应等待 error 后的 `done`，且不得把无引用的回答计为成功。

`question` 非空且 ≤1000 字符；参数错误发生在 SSE 建立前，返回 HTTP 400 + JSON。前端请求头建议同时接受 `text/event-stream` 和 `application/json`。

前端“知识库问答”页使用 `A`/`B` 别名调用该接口：回答结束后展示引用编号和引用卡片，卡片包含文档名、段落序号、页码（有则显示）、摘录和分数；点击编号会滚动到对应卡片并高亮 2 秒。拒答显示“资料中未找到依据”且不显示引用，错误显示独立错误块。

完整契约：阶段一见 [接口文档](docs/api.md)；阶段二 RAG 见根目录 `docs/07-阶段二需求文档.md` §6.2，实现与验收记录见 [阶段二技术验证](docs/stage2-tech-validation.md)。

### 7.5 公文 Workflow 与校验工具

```bash
curl -sN -X POST http://localhost:8080/api/workflow/run/stream \
  -H 'Content-Type: application/json' \
  -d '{"input":"起草一份安全生产检查通知","draft":"　　各部门要立即开展自查。"}'
```

- 请求字段：`input`（必填，≤2000 字）、`draft`（可选，≤20000 字）、`documentIds`（可选，≤20 项）；`documentIds` 当前仅做契约预留，不参与 Dify 入参。
- SSE 顺序：`node*`（节点开始/结束）→ `message*`（最终文本一次性输出，Dify 未透传增量）→ `done`（`runId/status/traceId`）；失败时发送 `error`（5004）且不发送 `done`。
- `done.status` 为 `succeeded`、`partial-succeeded` 或 `WAITING_CONFIRM`；暂停时同时返回 `runId`，前端进入人工确认流程。
- 工具失败由 Dify fail-branch 兜底，工作流状态为 `partial-succeeded`，后端仍发送 `message` 与 `done`。
- `draft` 为空时执行知识检索与 LLM 生成；非空时直接校验草稿。
- 格式/术语工具契约与规则见 [公文格式与用语校验技能](docs/skills/official-doc-format.md)；DSL 与导入说明见 `dify/dsl/README.md`。

**S8 状态查询、确认与导出：**

```bash
RUN_ID=<上一步 done 返回的 runId>

# 1. 查询状态：WAITING_CONFIRM 时返回待确认草稿
curl -s http://localhost:8080/api/workflow/$RUN_ID

# 2. 修改后通过（content 非空；approve/reject 可省略 content）
curl -s -X POST http://localhost:8080/api/workflow/$RUN_ID/confirm \
  -H 'Content-Type: application/json' \
  -d '{"action":"modify","content":"修改后的正文"}'

# 3. 下载（必须先确认成功）
curl -OJ "http://localhost:8080/api/workflow/$RUN_ID/export?format=md"
curl -OJ "http://localhost:8080/api/workflow/$RUN_ID/export?format=docx"
```

- 确认动作仅接受 `approve`、`modify`、`reject`；非法动作、已确认、已过期或记录不存在返回 `code=5005`，HTTP 仍为 200，由前端按业务码处理。
- 导出仅对 `SUCCEEDED` 开放；未确认、已驳回、失败、过期或记录不存在返回 `code=5006`。导出文件名会去除 Windows 非法字符，响应设置 `Content-Disposition` 与 `no-store`。
- 运行状态保存在后端内存，重启后清空；暂停后不要重启后端。确认提交成功后 Dify 会异步恢复并完成 `human_confirm → end`，后端不消费恢复流。
- 前端入口为“公文工作流”页：填写任务/可选草稿 → 发起 → 查看节点与工具结果 → 编辑并确认/驳回 → 下载 Markdown 或 DOCX。

```bash
curl -s -X POST http://localhost:8080/api/format/check \
  -H 'Content-Type: application/json' \
  -d '{"title":"关于开展检查的通知","docType":"通知","recipient":"各部门：","signature":"XX办公室","date":"2026-11-25","body":"　　各部门要立即开展自查。"}'

curl -s -X POST http://localhost:8080/api/terms/check \
  -H 'Content-Type: application/json' \
  -d '{"text":"同志们不能做为旁观者。"}'
```

## 八、验收演示步骤（10 分钟硬性检查）

| 时间 | 动作 | 预期证据 |
|---|---|---|
| 0–1 min | 展示仓库结构、`.gitignore`、`git grep` 密钥检查 | 无 `.env` 被跟踪，无真实 Key |
| 1–4 min | 空目录克隆 tag，复制 `.env.example` 为 `.env`，启动后端与前端 | README 可复现，健康检查 200 |
| 4–6 min | 页面提问，观察逐字输出与首字延迟 | 首字 ≤3 秒，流式不截断，Markdown 正常 |
| 6–8 min | 现场将风格从 `formal` 切换为 `concise`，同一问题再问一次 | 输出变化明显，能解释 Prompt 变量作用 |
| 8–10 min | 展示一次异常提示与后端 traceId 日志 | 异常可追踪，日志含 traceId |

补充演示：

1. **结构化抽取**：打开抽取面板或使用 curl，提交正常公文和稀疏文本，展示五字段 JSON 与 `null` 结果；说明去围栏、重试一次、`5003` 兜底。
2. **知识库问答**：切换到“知识库问答”，选择 B 库提问“2027年元旦放假几天？”，观察逐字输出、停止按钮、引用卡片和点击编号后的定位高亮；再问一个资料外问题展示“资料中未找到依据”；切换 A 库重复一次，展示 A/B 路由。
3. **Dify**：打开 http://localhost/，说明阶段一 Chatbot 是独立交付物，阶段二 RAG 页面依赖 `RAG对话` / `RAG对话-A` 与 A/B 数据集；Workflow 应用已发布 `s8-v1.0`，包含 `human_input` 节点。
4. **公文工作流**：切换到“公文工作流”，发起任务；展示格式/用语工具结果、`WAITING_CONFIRM` 状态与草稿编辑，修改后通过并下载 Markdown/DOCX；再发起一次并驳回，说明导出返回 `5006`。
5. **密钥检查**：

```bash
git ls-files | grep -E '(^|/)\.env$' || echo "no .env tracked"
git log --all -p | grep -E 'sk-[A-Za-z0-9]{20,}' | head
```

预期：没有 `.env` 被跟踪；没有真实 Key（只允许 `.env.example` 中的占位符）。

完整话术见 [演示脚本](docs/demo-script.md)。

## 九、常见问题

| 问题 | 处理方式 |
|---|---|
| 后端提示 `LLM_API_KEY 未配置` | 确认项目根目录存在 `.env`，`LLM_API_KEY` 已填；修改后重启后端 |
| 模型调用失败 / 401 | 检查 Key 是否正确、是否有余额、`LLM_BASE_URL` 是否为 `https://api.deepseek.com`；Key 不会出现在响应和日志中 |
| 浏览器 CORS 报错 | 使用 http://localhost:5173 或 127.0.0.1:5173 访问；不要直接用 `file://` 打开页面；后端只对这两个来源开放跨域 |
| SSE 不逐字显示或被截断 | curl 测试加 `-N`；确认中间没有缓冲代理；后端需设置 `text/event-stream` 并及时 flush |
| 8080 端口被占用 | 关闭占用进程，或修改 `.env` 的 `SERVER_PORT`，同时修改 `frontend/vite.config.js` 的代理目标 |
| 5173 端口被占用 | Vite 配置了 `strictPort`，启动会直接失败；先释放 5173，或改端口并同步调整后端 CORS |
| 抽取返回 `5003` | 模型输出无法解析且重试后仍失败；查看后端日志中的原始输出和 traceId，不要把原始输出返回前端 |
| Node 启动报引擎版本错误 | Vite 8 要求 Node `^20.19.0` 或 `>=22.12.0`，升级 Node 后重新 `npm install` |
| `localhost` 访问异常但 `127.0.0.1` 正常 | Windows 下可能是 IPv6 解析差异；统一使用 `localhost`，并确认代理目标为 `localhost` |
| Dify 打不开或 `/console/api/setup` 返回 502 | 等待 `docker-api-1` 变为 `healthy`；Docker Desktop 异常时执行 `wsl --shutdown` 后重启 Docker Desktop，再 `docker compose up -d` |

## 十、已知限制与下一步

阶段一明确不做（仍然成立）：

- 登录、JWT、RBAC、多用户；
- MySQL、会话表、消息表、历史会话；
- 私有 vLLM、整体一键部署、移动端适配。

阶段二 S6 已完成（2026-11-25）：A/B 各 10/10 命中、5/5 正确拒答、非拒答引用完整 10/10；引用摘录上限由 200 调整为 500，使引用卡片能够核验答案事实。默认库建议继续使用 B；A/B badcase 与限制见 `docs/stage2-eval-report.md`。

阶段二 S7 已完成（2026-11-25）：格式/术语检查接口、Dify Workflow、后端 SSE 代理与工具失败兜底均已通过真实链路验收；发布版本 `s7-v1.0-final`，DSL 见 `dify/dsl/official-doc-workflow-s7.yml`。

阶段二 S8 已完成（2026-11-25）：`human_input` 暂停、状态查询、修改/通过/驳回、Markdown/DOCX 导出与前端流程页均通过真实 Dify 链路验收；发布版本 `s8-v1.0`，DSL 见 `dify/dsl/official-doc-workflow-s8.yml`。全量后端测试 98/98 通过。

阶段二 S9 已完成（2026-11-26）：README、接口文档、DSL 说明、数据清单、演示脚本、Dify 部署记录与技能文档已收口；新增 `scripts/regression/workflow_e2e.py` 和 `scripts/regression/check_docs_consistency.py`；四批回归（静态、构建测试、真实 Workflow E2E、文档/DSL 一致性）结果见 `logs/regression/20261126-1441/` 和 `docs/stage2-tech-validation.md` 第 15 节。

阶段二截至 S9 尚未完成：

- S10 干净环境彩排、15 题最终评测复核、汇报材料与 `stage2-v1.0` 标签。

已知限制：

- Workflow 运行状态仅保存在后端内存，重启即清空，不支持多实例共享；
- 确认提交成功后，后端立即返回 `SUCCEEDED/REJECTED`，Dify 恢复流由 Dify 自行完成，后端不再回读最终节点；
- 确认过期依赖 Dify `human_input` 节点的 `expiration_time`，当前配置为 1 天；后端会在查询/确认时惰性标记过期。
- **FR-10 口径差异**：需求提到驳回后回退生成节点并最多重试 1 次；S8 已验收实现为 `reject` 后进入 `REJECTED` 终态，需要重新发起任务，不自动回退。S9 如实记录，不降低验收标准，也不把它伪装成已实现。
- **FR-13 口径差异**：需求提到导出含引用列表；当前 Markdown/DOCX 导出包含正文、校验摘要和确认结果，不包含知识检索引用列表。知识库问答页仍有引用；导出补引用属于独立功能增量，S9 不临时改功能。
- **OpenAPI 选择**：本项目为本地单用户演示，接口契约采用 README + `docs/api.md` 的 Markdown/curl/SSE 示例，并由 `check_docs_consistency.py` 校验；不引入 springdoc，不维护独立 OpenAPI YAML。
- **数据来源**：20 份资料的来源 URL 已从 PDF 内嵌链接确认 3 条，其余 17 条仍标记“URL 待补录”，不编造；补齐后可重新生成清单。

A 策略仍存在少量 5 字碎段，S6 已评估并记录为 badcase（A 库切分过细），后续可合并短段或重导后复测；评测报告未隐去该偏差。

## 十一、提交规范

- 提交信息统一使用中文。
- 标题格式：`类型(范围): 摘要`，例如 `feat(chat): 增加流式对话接口`。
- 正文至少包含以下两节：
  - `修改：` 已有文件、功能或配置的调整；
  - `新增：` 新增的文件、功能或配置。
- 没有修改或新增时写“无”，不要省略。

示例：

```text
feat(chat): 增加 SSE 流式对话接口

修改：
- ChatController 增加 /api/chat/stream 接口

新增：
- LlmClient 封装 DeepSeek 流式调用
```

## 十二、文档索引

- [接口文档（阶段一 + 阶段二）](docs/api.md)
- [前端原型设计](docs/frontend-prototype.md)
- [联调与性能测试记录](docs/streaming-test.md)
- [Dify 部署记录](docs/dify-setup.md)
- [阶段二技术验证记录](docs/stage2-tech-validation.md)
- [阶段二评测报告](docs/stage2-eval-report.md)
- [公文格式与用语校验技能](docs/skills/official-doc-format.md)
- [S7/S8 Workflow DSL 与导入说明](dify/dsl/README.md)
- [PR 与评审记录](docs/pr-log.md)
- [阶段一汇报单](docs/report-stage1.md)
- [演示脚本](docs/demo-script.md)
- [学习笔记](docs/learning-notes-stage1.md)
- [仓库 Tag 说明](docs/release-tag.md)
- [抽取样例 JSON](docs/samples/extract-normal.json)
- [Prompt 样例](prompts/chat-formal.md)
