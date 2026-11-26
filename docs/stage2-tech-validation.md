# 阶段二技术验证记录（S0–S9）

> 状态：S0 已通过（2026-11-24）；S1 已完成（2026-11-24）；S2 实现与真实数据验收已完成（A/B 各 20 份 completed；来源 URL 待补录，2026-11-25）；S3 前端实现与真实上传验收已完成（2026-11-25）；S4 后端 RAG 流式代理与引用已完成，并通过 B 库 3 题、5 题拒答和 A 库路由冒烟验收（2026-11-25）；S5 前端 RAG 问答、引用定位高亮与拒答/错误态已完成，并通过真实浏览器验收（2026-11-25）；S6 15 题 A/B 两轮评测已完成，命中/拒答/引用完整均达门槛，并记录 3 个 badcase（2026-11-25）；S7 Dify Workflow、格式/术语工具、条件分支和工具超时兜底已完成并通过双路径验收（2026-11-25）；S8 人工确认、状态查询、Markdown/DOCX 导出与前端流程页已完成并通过真实 Dify 闭环验收（2026-11-25）；S9 文档、DSL、数据清单与四批回归已完成（2026-11-26）
> 关联：`docs/08-阶段二执行计划.md` v1.1 S0–S8；`docs/07-阶段二需求文档.md` v1.0
> 安全：本文不记录任何 API Key、密码或完整上游报错堆栈。

## 1. 环境基线

| 项目 | 版本/状态 | 验证方式 |
|---|---|---|
| Docker Desktop | 29.6.2 / Compose v5.3.1 | `docker info` |
| Dify | 1.19.0（api/web 镜像），初始化完成（2026-11-10） | `docker compose ps`、`/console/api/setup` |
| `docker-api-1` | Up (healthy) | 同上 |
| JDK / Maven | 17.0.8 / 3.9.16 | `java -version`、`mvn -version` |
| Node / npm | 24.18.0 / 11.16.0 | `node -v`、`npm -v` |
| Python | 3.12.7，requests 2.34.2 | `python --version` |
| LLM | DeepSeek `deepseek-chat`，Key 校验通过 | `GET /v1/models` → 200（未记录 Key） |
| Embedding | 硅基流动 `BAAI/bge-m3`，1024 维 | `POST /v1/embeddings` → 200，dim=1024（未记录 Key） |

## 2. 连通性

| 方向 | 命令/方式 | 结果 |
|---|---|---|
| 宿主机 → 后端 | `GET http://localhost:8080/api/health` | 200，`{"code":0,...}` |
| Dify 容器 → 宿主后端 | 容器内请求 `http://host.docker.internal:8080/api/health` | 200，`{"code":0,...}` |
| 宿主机 → Dify Service API | `GET http://localhost/v1/parameters` | 401（未带应用 Key，符合预期，证明可达） |
| Dify 容器 → 外网 | 容器内 TCP 443 到 api.siliconflow.cn / api.deepseek.com | 连接成功 |

## 3. S0 验证结果（已完成）

- [x] Dify 控制台配置 SiliconFlow Provider 与 Embedding 模型（实际选中 `Pro/BAAI/bge-m3`，1024 维）
- [x] 创建知识库 API Key（仅存后端 `.env`，本文不记录）
- [x] 创建策略 B 测试知识库 `ai-chat-kb-b-test`（id `1e402bbc-22c3-44d3-ac71-28e1dfe15c9c`）
  - 分段参数：分隔符 `\n\n`、最大 800、重叠 100（Dify 的 `max_tokens` 按 token 计，与需求中的“字符”口径可能不一致，待文本样本验证后登记偏差）
- [x] 文本文件上传/异步索引链路跑通（冒烟文档 `word_count=17262` → 130 段，状态 `completed`）
- [x] 检索接口与引用字段结构验证（冒烟测试，非评测数据）
- [x] 3 份真实文本 PDF 入库并完成检索验证（文档名/分段序号/摘录/分数均正确）
- [x] 最小 `human_input` Workflow 验证（见 3.3）
- [ ] 后端 → Dify 应用级联调（S2/S4 实施，需应用 API Key）

### 3.1 引用字段映射（冒烟测试已确认）

| 需求字段 | Dify 来源 | 说明 |
|---|---|---|
| `docId` | `records[].segment.document.id` | 文档 ID |
| `docName` | `records[].segment.document.name` | 文件名 |
| `segmentIndex` | `records[].segment.position` | 1 起始的分段序号 |
| `excerpt` | `records[].segment.content` | 分段正文，可截断 |
| `score` | `records[].score` | 相似度分数 |
| `page` | 无 | 分段结构与 `doc_metadata` 均无页码；按需求“有页码时提供”记为不可用 |

### 3.2 真实样例验证（3/3，2026-11-24）

| 样例（公开公文） | 页数 | word_count | 分段数 | 验证 query | top1 分数 | 命中 |
|---|---|---|---|---|---|---|
| 国务院办公厅关于2027年部分节假日安排的通知 | 2 | 1,103 | 2 | 2027年春节放假怎么安排 | 0.7433 | segmentIndex=1，正文 |
| 中医药传统知识保护条例 | 7 | 4,723 | 7 | 条例何时施行 | 0.7883 | segmentIndex=1，含 2027-01-01 |
| 基金会管理条例 | 27 | 17,084 | 27 | 何时修订通过/施行 | 0.7520 | segmentIndex=1，含 2026-09-11 / 2027-01-01 |

- 三份均 `completed`；单段 341–762 字符，未超过 800 上限
- 引用字段 `docName`、`segmentIndex`、`excerpt`、`score` 均可用；`page` 不可用
- 结论：3 份真实文本层 PDF 的上传、索引、检索、引用字段链路验证通过

### 3.3 human_input 验证（2026-11-24）

- App：`s0-human-input-test`（workflow 模式，Dify 控制台创建）
- 工作流图包含 `human-input` 节点（数据库 `workflows.graph` 校验结果为 true）
- 运行记录：1 次 `paused`，随后 4 次 `succeeded`，证明“暂停 → 提交 → 继续”链路可用
- 表单记录：5 条 `submitted`，`selected_action_id=action_1`，交付方式 `webapp`
- 结论：最小 human_input（Web App 交付、表单提交、流程恢复）验证通过

## 4. 关键发现（2026-11-24）

1. **扫描件不可用，文本件可用**：旧 3 份样例均为纯图片扫描件（`pypdf` 提取 0 字符，Dify 仅索引到 `![image](...)` 占位符），已从测试库删除；新增的国务院办公厅通知 PDF 有文本层（`pypdf` 提取 1,196 字符），入库与检索均正常。
2. **策略 B 分段表现**：Dify 自定义分隔符按段落切分、单段上限 800 token。3 份真实公文分段为 2 / 7 / 27 段，单段 341–762 字符，表现正常；但以 Markdown 标题/短段落为主的计划文档（`word_count=17262`）产生 130 个碎段，检索命中质量下降（top1 0.527）。结论：策略 B 适用于公文，但对短段落密集的文档需要合并小段或改用其他模式，评测前需固定规则。
3. **页码不可用**：Dify 检索结果无页码字段，引用只能提供文档名 + 分段序号 + 摘录；与需求“有页码时提供”一致，需在接口契约中明确页面号可缺省。

## 5. 结论

S0 通过（2026-11-24）。文本上传、索引、检索、引用字段与 human_input（Web App 暂停-提交-恢复）均验证通过。S1 后端纵切已于 2026-11-24 完成，见第 6 节。S2 适配层、状态轮询与 A/B 导入脚本已于 2026-11-25 实现并完成真实后端验收；同日补齐 20 份真实文本 PDF，Dify A/B 两库各 20 份均为 `completed`，切分记录和清单已生成，见第 7 节。来源 URL 明细仍待补录，不影响当前 A/B 入库数量与切分记录验收。

## 6. S1 完成记录（2026-11-24）

- 后端新增 `com.ailyrx.aichat.kb` 纵切：`POST /api/kb/documents` 接收 `multipart/form-data` 的 `file` 字段；`GET /api/kb/documents` 按 `createdAt` 倒序返回。
- 校验扩展名 `pdf/docx/txt/md`、大小 ≤20MB、空文件与文件名清洗；校验失败返回 HTTP 400 + `code=4000`；multipart 超限异常同样映射为 400 + `4000`，不泄露上游堆栈。
- 元数据暂存内存（`DocumentStore` 接口 + `InMemoryDocumentStore`），初始状态 `PENDING`，`chunkCount/errorMessage` 可为空；重启后清空为已知限制。
- 上传/列表响应与日志均通过 `TraceIdFilter` 贯通 traceId。
- 测试：`DocumentControllerTest` 7/7；后端全套 41/41，阶段一测试无回归。
- 真实 `curl` 验收：合法 `notice.txt` → 200 + `PENDING`；`notice.exe` → 400 + `4000`；21MB PDF → 400 + `4000`，未出现未处理 500。
- 限制：S1 尚未调用 Dify，状态流转与批量入库在 S2 实施。

## 7. S2 实现与验收记录（2026-11-25）

### 7.1 已实现

- `dify/` 适配层：`DifyProperties`、`DifyClient`、请求/响应 DTO、`DifyException`；Base URL、API Key、A/B 数据集 ID 全部通过后端 `.env` 注入，前端不持有 Dify Key。
- 上传状态机：保存 `PENDING` 元数据 → 调用 Dify `create-by-file` → 保存 `INDEXING` 与 `difyDatasetId/difyDocumentId` → `DifyIndexingPoller` 异步轮询 → `READY`（写 `chunkCount`）或 `FAILED`（写脱敏错误）。
- 轮询限制：默认间隔 2 s、最长 180 s；可取消，后端关闭时停止线程。查询失败保持 `INDEXING` 并重试，超时转 `FAILED`。
- 错误契约：Dify 不可用/超时返回 HTTP 200 + `code=5002`；Dify 上游错误详情和堆栈只进后端日志，不返回前端。Dify Key、上游响应正文不出现在接口响应中。
- A/B 导入脚本 `scripts/dify/import_datasets.py`：复用/创建 `ai-chat-kb-a`、`ai-chat-kb-b`，同一批资料分别上传，等待索引，导出 `data/eval/chunks-{a,b}.json`，生成 `docs/dataset-manifest.md`；脚本不打印 Key。真实文件与导出目录仍被 gitignore。
- 默认数据库仍为 B：`DIFY_DEFAULT_DATASET_ID` 指向 `ai-chat-kb-b`。

### 7.2 真实验收

| 项目 | 方式 | 结果 |
|---|---|---|
| 后端全量测试 | `mvn test` | 52/52 通过；阶段一用例无回归 |
| 上传真实 PDF | UTF-8 multipart 上传《基金会管理条例》 | HTTP 200 + `X-Trace-Id`；`fileName` 中文正常；状态 `INDEXING`；返回 Dify 文档 ID |
| 索引完成 | 轮询 `GET /api/kb/documents` | `READY`，`chunkCount=27`，`errorMessage=null`，`difyDatasetId` 为 B 库 |
| Java → Dify 中文名 | 查询 Dify 文档详情 | Dify 存储的文档名与本地文件名一致 |
| Dify 不可用 | 临时使用不存在的 dataset ID 上传 | HTTP 200 + `code=5002`、安全提示；列表记录 `FAILED`，无上游堆栈/Key |
| A/B 导入（首轮） | 对 3 份真实 PDF 跑导入脚本 | A/B 均为 `READY`；A 段数 14/3/56，B 段数 7/2/27；导出切分记录与清单 |
| A/B 导入（20 份） | 补齐 17 份后复用数据集重跑导入脚本 | A/B 各 20 份，Dify 状态全部 `completed`；A 共 229 段（单文档 2–56），B 共 138 段（1–27）；清单 20 行与 `data/docs/` 一致 |
| 文本层预检 | 本地 `pypdf` 提取 20 份 PDF | 20/20 有文本层，字符数 383–17,059，无扫描件 |

- 验收使用 Python `requests` 的 UTF-8 multipart 客户端；Git Bash/curl 在本机对中文文件名存在终端编码问题（服务端收到的字节已损坏），不作为后端缺陷判定。浏览器按 UTF-8 multipart 上传，Java → Dify 链路已用中文文件名验证正常。
- 上传响应状态为 `INDEXING`，`PENDING` 是短暂内部状态，不再作为 API 返回状态；这与 S1 的 `PENDING` 验收口径不同，已在此登记。
- A/B 真实数据集：`ai-chat-kb-a` = `14ebeb37-be49-4a4c-a1bf-9731ab2be8f0`；`ai-chat-kb-b` = `9bff9430-6853-4886-84a5-468fd6030352`。ID 不是密钥，可写入清单；Key 未记录。

### 7.3 未完成项与风险

- **来源 URL 未补录**：清单中的来源目前为“中国政府网（URL 待补录）”，需补充具体页面 URL；不得编造。可用脚本的 `--source-map` 传入 `{文件名: URL}` 后重新生成清单。
- **策略 A 碎段风险**：Dify `max_tokens` 是 token 上限，不是字符数；A 在 500 token 下出现标题行孤立（《基金会管理条例》最短 5 字）。A 的整体字符范围 5–500，B 为 186–800。S6 已评估该偏差并记为 BC-03（切分 badcase），本轮不改 A 参数以免与引用摘录修复同时变更变量；详见 `stage2-eval-report.md`，不得在评测报告中隐去。
- **Dify 应用 Key 已就绪**：`DIFY_APP_API_KEY` 已写入本地 `.env`，应用预检见第 8 节；本文不记录 Key 内容。
- 文档元数据仍为内存存储，后端重启清空，属于计划内已知限制。

## 8. S4 Dify 应用预检（2026-11-25）

本节只验证 Dify 应用侧，不代表后端 S4 已实现。

| 项目 | 方式 | 结果 |
|---|---|---|
| 应用 Key | `GET /v1/parameters` | 200 |
| 流式问答 | `POST /v1/chat-messages`，`response_mode=streaming` | 200，`text/event-stream` |
| 流事件 | 解析 SSE | `workflow_started/node_started/node_finished/reasoning_chunk/message/message_end/workflow_finished` |
| 知识库命中 | 问题“中医药传统知识保护条例什么时候施行？” | 回答含“自2027年1月1日起施行”；`message_end.metadata.retriever_resources` 4 条 |
| 引用字段 | 检查 `retriever_resources` | `document_name`、`segment_position`、`score` 可用；示例首条为中医药条例 segment 1，score 0.7783 |
| 思考内容 | 检查流式回答 | 已无 `<think>`；`reasoning_chunk` 事件仍存在，后端不得转发为 `message` |
| 拒答 | 问题“请介绍火星上的中医诊所。” | 回答“资料不足，无法回答该问题。”；Dify 仍返回 4 条低分资源 |

- 模型按用户确认使用 Dify 中的 `deepseek-v4` 系列；应用界面实际显示 `deepseek-v4-flash`。阶段一后端的 `deepseek-chat` 仅用于旧接口，不用于本阶段 RAG 评测；评测报告需记录 Dify 实际模型名。
- S4 映射要求：只把 `event: message` 的增量映射为 SSE `message`；忽略 `reasoning_chunk`；拒答时 `citations.items` 必须为空，不能输出非空引用；`citations` 必须在 `done` 前发送。

### 8.1 A/B 双应用准备（2026-11-25）

单个 Chatflow 的知识检索节点不能按请求动态切换数据集，多选 A/B 会同时检索两库并混排结果，无法做严格 A/B 对比。因此采用两个 Dify 应用：

| 项目 | B 应用 | A 应用 |
|---|---|---|
| 应用名 | `RAG对话` | `RAG对话-A` |
| 检索数据集 | B 库 `9bff9430...` | A 库 `14ebeb37...` |
| 生成模型 | `deepseek-v4-flash` | `deepseek-v4-flash` |
| Direct Reply | 引用 `llm.text` | 引用 `llm.text` |
| 服务 API Key | 现有 `DIFY_APP_API_KEY` | 本地 `.env` 的 `DIFY_APP_API_KEY_A`（不记录） |

验证结果：

- 两个应用的服务 Key 调 `GET /v1/parameters` 均返回 200。
- A 应用流式问答 HTTP 200；事件包含 `message`、`message_end`、`workflow_started/node_finished`，无 `error`。
- 用“中医药传统知识保护条例什么时候施行？”验证，回答包含 2027-01-01；`message_end.metadata.retriever_resources` 返回 4 条，`dataset_id` 全部为 A 库，首条 `segment_position=1`、score 约 0.7844。
- 回答无 `<think>`；仍有 `reasoning_chunk`，S4 后端不得转发。
- 结论：A/B 双应用路由可行；S4 按 `datasetId` 映射到对应应用 Key。

## 9. S3 前端上传/列表（2026-11-25）

### 9.1 已实现

- 新增“知识库”标签页与 `frontend/src/views/KnowledgeView.vue`，保留阶段一“流式对话”和“结构化抽取”。
- 新增 `frontend/src/api/kb.js`：封装 `GET/POST /api/kb/documents`，统一处理 HTTP 错误、`ApiResponse.code`、Dify 5002 和网络错误；上传使用 `FormData`，不手写 multipart 边界，不接触任何 Key。
- 上传校验：仅允许 `pdf/docx/txt/md`、非空、≤20MB；前后端双重校验，前端错误为可读中文提示。
- 列表展示文件名、大小、状态、分段数、创建时间和错误信息；状态映射为排队中/处理中/可用/失败。
- 存在 `PENDING/INDEXING` 文档时每 2 秒轮询；处理完成、离开页面或轮询失败时停止。上传成功或 Dify 5002 失败后自动刷新列表。
- 离开“知识库”标签时卸载页面并中止请求，避免后台定时器和请求泄漏。
- `VITE_USE_MOCK=true` 时显示“需要真实后端”的提示，不使用 Mock 数据冒充上传链路。

### 9.2 已完成的验证

| 项目 | 方式 | 结果 |
|---|---|---|
| 构建 | `npm run build` | 通过，30 模块，产物正常 |
| 诊断 | IDE diagnostics | `KnowledgeView.vue`、`kb.js` 无诊断错误 |
| 页面渲染 | Headless Edge + CDP，真实后端 | 三个标签正常切换；知识库页空态、上传区、文档列表布局正常 |
| 列表接口 | 真实后端 `GET /api/kb/documents` | 200 + `code=0`，空列表 |
| 非法文件 | 浏览器选择 `.exe` | 前端提示“仅支持 PDF、DOCX、TXT、MD 文件”，未发起上传 |
| Dify 不可用 | 浏览器上传合法 `.txt`（Dify 未启动） | 页面显示“知识库服务暂不可用”（5002）与 traceId；列表自动刷新并显示失败记录、无上游堆栈/Key |
| 真实上传成功 | Dify 启动后，浏览器上传 185 B 测试文本 | 2 秒内显示“处理中”，约 18 秒后自动变为“可用”，分段数 1；全程无需手动刷新，无上传/列表错误 |
| 清理 | Dify Dataset API 删除测试文档 | HTTP 204；B 库文档数恢复为 20，测试文档不存在；A/B 清单不受影响 |
| 阶段一回归 | 构建与页面加载 | 聊天/抽取页未改动，仅新增标签 |

### 9.3 验收结论

S3 通过（2026-11-25）。前端上传、状态轮询、成功/失败展示和真实 Dify 索引链路均已验证；临时验收文档已删除，知识库恢复为 A/B 各 20 份。

## 10. S4 后端 RAG 流式代理（2026-11-25）

### 10.1 已实现

- 新增 `backend/src/main/java/com/ailyrx/aichat/rag/`：
  - `RagController`：`POST /api/rag/chat/stream`，沿用 `SseEmitter`（120s）与 `chatExecutor`；
  - `RagService`：A/B 路由、SSE 编排、拒答识别、错误终止；
  - `DifyChatClient`：调用 Dify Chatflow `POST /chat-messages`（`response_mode=streaming`），只转发 `event: message` 的 `answer`，忽略 `reasoning_chunk` 与 workflow/node 事件；
  - `CitationCollector`：从 `message_end.metadata.retriever_resources` 映射、去重、按分数排序；
  - DTO：`CitationItem`、`CitationPayload`、`DifyRetrieverResource`、`DifyChatResult`、`RagRequest`。
- 新增 `app.dify-chat.*` 配置，将应用 Key 与数据集 Key 分离：
  - B 应用：`DIFY_APP_API_KEY`；
  - A 应用：`DIFY_APP_API_KEY_A`；
  - `DIFY_CHAT_TIMEOUT_SECONDS` 默认 120；Key 只从后端本地 `.env` 读取，不打印、不返回前端。
- `datasetId` 缺省时使用默认数据集（当前为 B）；显式值接受 `A`/`B` 别名或配置中的实际数据集 ID，最终归一化到 A/B 白名单；非法值在 SSE 建立前返回 HTTP 400 + `4000`。
- `question` 非空且 ≤1000 字符；空白和超长请求由 Bean Validation 拒绝。
- 正常事件顺序：`message* → citations（一次）→ done`；`done` 包含 `runId`（优先 Dify `message_id`）、`traceId`、`elapsedMs`。
- 引用字段：`docId`（优先 Dify document ID，缺失时用文档名+段落稳定哈希）、`docName`、`segmentIndex`（来自 `segment_position`）、`excerpt`（空白折叠、最多 500 个 Unicode 码点，S6 修复前为 200）、`page=null`（Dify 当前无页码，不伪造）、`score`。
- 拒答识别固定话术（`资料不足`、`无法回答该问题` 等）：发送 `citations.items=[]` 和 `done`，不当作错误，也不保留 Dify 返回的低分资源。
- 非拒答但引用为空：发送 `error`（5002，“本次引用不可用”），不伪装成功。
- Dify HTTP 错误、流内 `error` 事件、超时或网络异常：统一返回脱敏后的 SSE `error`（5002），不暴露上游响应体、Key 或堆栈。

### 10.2 单元与接口测试

`mvn -q test`：69 个测试全部通过（0 failure / 0 error / 0 skipped）。S4 新增 16 个，S5 为前端 A/B 别名补充 1 个回归测试，共 17 个：

| 测试 | 覆盖 |
|---|---|
| `CitationCollectorTest`（4） | 字段映射、去重取高分、排序、500 字截断、稳定兜底 ID、拒答标记 |
| `DifyChatClientTest`（4） | 只转发 message、忽略 reasoning、解析 message_end 引用、HTTP/流内错误脱敏、缺 Key 提前拒绝 |
| `RagControllerTest`（9） | 事件顺序、A/B Key 路由、`A` 别名路由、实际数据集 ID 兼容、拒答空引用、无引用错误、上游错误无 done、非法 datasetId、空/超长 question |

`RagControllerTest` 使用 `@MockBean DifyChatClient`，不调用真实模型；测试属性使用假数据集 ID 和假 Key。

### 10.3 真实验收

服务：Dify 1.19.0 本地运行，模型 `deepseek-v4-flash`；后端连接真实 Dify，B 库默认、A 库显式路由。原始 SSE 保存在 `data/eval/s4/`（已被 gitignore），未提交真实资料或回答。

| 编号 | 问题 | 结果 | 引用 | 终止顺序 | 耗时 |
|---|---|---|---|---|---|
| q1 | 2027年元旦放假几天？ | 回答“共 3 天”，与《国务院办公厅关于2027年部分节假日安排的通知》一致 | 4 条，首条为该通知 segment 1 | `citations → done` | 2.66s |
| q2 | 根据《事业单位登记管理条例》，全国事业单位登记管理工作由哪个机关负责？ | 回答“国家事业单位登记管理机关” | 4 条，均指向该条例 | `citations → done` | 2.21s |
| q3 | “双友好医院”建设要求医疗机构在哪些方面做到友好？ | 回答五个方面；与原文一致 | 4 条，均指向该通知 | `citations → done` | 2.64s |
| a-smoke | 同 q1，`datasetId=A` | 回答“3 天”；走 A 应用/A 库 | 4 条 | `citations → done` | 2.44s |
| n1–n5 | 诺贝尔文学奖、巧克力蛋糕、火星距离、红楼梦作者、特斯拉交付量 | 5/5 输出“资料不足，无法回答该问题。” | 0 条（即使 Dify 返回低分资源也不透传） | `citations → done` | 1.58–2.04s |

- 有答案请求 4/4（B 库 3 + A 冒烟 1）均带 4 条引用，引用完整率 100%；无答案拒答率 5/5。
- 所有成功请求均先收到 `citations` 再收到 `done`；无 `error`。真实 `page` 为 `null`，未伪造页码。
- A/B 路由由单元测试验证 Key 选择；A 冒烟使用真实 A 应用，引用文档与 A 库内容对应。Dify 数据集 ID 不对外暴露。
- SSE 中未出现 `reasoning_chunk`、内部 workflow 事件或应用 Key；后端日志记录 traceId、数据集、耗时和引用数，不记录问题、回答或 Key。
- 错误路径（无引用、上游异常、非法参数）由自动化测试覆盖。本轮未主动停止用户手动启动的 Dify 来做真实断连演练。

### 10.4 契约偏差与注意事项

- `docs/08` S4 任务 3 写有“发 error 并以 done 收尾”，与 `docs/07` 的“每次请求必须以 done 或 error 结束”以及阶段一实现冲突。S4 采用后者：**error 是终止事件，之后不再追加 done**；前端应按终止事件处理，不等待 done。
- 拒答不是错误，但会发送一次 `citations` 且 `items=[]`，随后 `done`；不输出非空引用。
- 参数错误发生在 SSE 建立前，按约定返回 HTTP 400 + JSON。前端 S5 发送 POST 时应使用 `Accept: text/event-stream, application/json`（或默认 `*/*`）；若只声明 `text/event-stream`，Spring 无法协商预检错误的 JSON 响应，可能返回 406。成功路径仍返回 `text/event-stream`。
- SSE 建立后，若上游最终没有引用，已流出的回答无法撤回；后端会发送 5002 error，前端必须将该轮标记为失败/不可引用，不能计入“已回答”。
- S5 前端使用 `A`/`B` 别名，后端在 S5 联调时补充别名归一化，同时保留实际数据集 ID 的兼容入口；两者共用同一白名单和 Key 路由。
- `RagControllerTest` 关闭了 Spring Boot 测试的默认 MockMvc 打印（`MockMvcPrint.NONE`），避免异步 SSE 写响应头与测试结果打印并发读取导致的偶发 `ConcurrentModificationException`。

## 11. S5 前端 RAG 问答与引用高亮（2026-11-25）

### 11.1 已实现

- 新增前端文件：
  - `frontend/src/views/RagView.vue`：问答页容器、示例问题、自动滚动、Mock 模式提示；
  - `frontend/src/components/RagMessage.vue`：回答气泡、引用编号/卡片、拒答态、错误态、点击定位与 2 秒高亮；
  - `frontend/src/components/RagComposer.vue`：A/B 知识库选择、≤1000 字输入、发送/停止、Enter 发送；
  - `frontend/src/composables/useRagChat.js`：消息、流式、引用、拒答、错误、首字/总耗时、AbortController；
  - `frontend/src/api/rag.js`：`POST /api/rag/chat/stream` 的 SSE 读取与错误映射。
- `App.vue` 与 `ChatHeader.vue` 新增“知识库问答”标签；阶段一“流式对话”和“结构化抽取”保留。聊天/RAG/抽取用 `v-show` 保留各自状态，知识库页仍按需挂载以停止轮询。
- 请求头使用 `Accept: text/event-stream, application/json`，兼容参数校验失败时的 JSON 错误体和 Dify 预检失败时的 HTTP 200 JSON 错误。
- SSE 解析：
  - `message` 增量追加并显示流式光标；
  - `citations` 保存本回答引用；
  - `done` 结束 loading；空引用 + `done` 判定为拒答；
  - `error` 显示可读错误块；网络中断映射为“无法连接后端/连接中断”；
  - 防御性处理：`done` 前未收到 `citations`、引用结构非法时标记为错误，不冒充成功或拒答。
- 引用展示文档名、段落序号、页码（有则显示）、摘录和分数；点击顶部引用编号滚动到对应卡片，高亮 2 秒后自动清除。`page=null` 不渲染页码。
- 拒答显示“资料中未找到依据”，不渲染引用卡片；错误显示独立红色错误块并保留错误码与 traceId。
- `VITE_USE_MOCK=true` 时禁用 RAG 输入并提示需要真实后端，不使用 Mock 或伪造引用。
- 未新增运行时依赖。

### 11.2 验证结果

| 项目 | 方式 | 结果 |
|---|---|---|
| 构建 | `npm run build` | 通过，35 modules，产物正常 |
| 后端测试 | `mvn -q test` | 69/69 通过（0 failure / 0 error / 0 skipped） |
| 浏览器 | Headless Edge 158 + CDP，真实后端与 Dify | 页面、流式、引用、拒答、A/B、停止、错误、移动端、刷新全部通过 |
| 逐字流式 | B 库“2027年元旦放假几天？” | 1.9s 内采集到 18 个不同长度的中间态，最终回答“2027年元旦放假3天” |
| 引用 | 同题 | 4 条引用卡片与 4 个编号；首条为《国务院办公厅关于2027年部分节假日安排的通知》第 1 段，含文档名、摘录、分数 0.76 |
| 引用定位 | 点击编号 [1] | 引用卡片获得高亮类，列表滚动约 200px 且卡片进入可视区；约 2 秒后高亮清除 |
| 拒答 | “诺贝尔文学奖得主是谁？” | 回答“资料不足，无法回答该问题。”，显示“资料中未找到依据”，引用 0 条，无错误 |
| A/B 选择 | 切换 A 库后重问元旦题 | 请求使用 A 应用/A 库，回答正常，4 条引用，footer 显示“知识库 A” |
| 停止 | 发送后立即点击停止 | 状态“已停止”，无引用、无错误，发送按钮恢复 |
| 错误态 | CDP 离线模拟后发送 | 显示“错误码 NETWORK / 无法连接后端，请确认服务已启动”，无引用、无拒答标记 |
| 标签切换 | 切到“流式对话”再切回 | RAG 页隐藏/恢复，3 轮消息保留，无残留流 |
| 移动端 | 375×812 | 无横向溢出，标签、输入框、引用卡片均在视口内 |
| 刷新（流式进行中） | 发送后等待停止按钮出现，立即 `Page.reload` | 刷新前 `aria-busy=true`；刷新后仅欢迎消息，无用户消息/引用/停止按钮，`aria-busy=false` |
| 刷新（完成后） | 正常完成后 `Page.reload` | 仅保留欢迎消息，无用户消息/引用/停止按钮，`aria-busy=false` |
| 浏览器异常 | Runtime.exceptionThrown | 0 条 |

- 验收脚本与截图保存在 `data/eval/s5/`（已被 gitignore）：`browser-acceptance.json`、`stop-acceptance.json`、`refresh-stream-acceptance.json`、`rag-answer-desktop.png`、`rag-citation-highlight.png`、`rag-mobile.png`。
- 浏览器验收通过 Headless Edge 的 CDP 执行，不新增 npm 依赖；真实调用 Dify `deepseek-v4-flash`，拒答题与 A/B 切换均为真实链路。

### 11.3 问题与修复

- 首次浏览器验收发现回答内容不更新：`messages` 是 `ref` 数组，`push` 后继续修改 push 前的原始对象不会触发 Vue 渲染。改为在 push 后从数组中取响应式代理再更新，复测通过。
- Mock 模式下 `RagView` 不生成任何演示数据，并明确提示 RAG 依赖真实后端，符合“不伪造引用”的约束。
- 后端 `resolveDatasetId` 原来只接受实际 UUID；S5 增加 `A`/`B` 别名归一化，前端无需持有或暴露数据集 ID。

### 11.4 验收结论

S5 通过（2026-11-25）。前端 RAG 问答、引用卡片与点击定位高亮、拒答/错误区分、A/B 选择、停止、移动端和流式中刷新清理均已验证；构建与 69 个后端测试通过，未新增运行时依赖。

## 12. S6 15 题 A/B 评测（2026-11-25）

- 15 题（10 有答案 + 5 无答案）在 A/B 两库各跑两轮；最终 A/B 均 10/10 命中、5/5 正确拒答、非拒答引用完整 10/10。
- badcase：引用摘录 200 字截断导致事实不可核验（已修复为 500 字）；低相关文档混入引用、A 库切分过细/半句开头（已记录，待后续单变量优化）。
- 完整指标、badcase、限制与证据索引见 `docs/stage2-eval-report.md`；全量后端测试 69/69 通过。

## 13. S7 Dify Workflow 与校验工具（2026-11-25）

### 13.1 已实现

- 格式检查：`POST /api/format/check`，校验标题、文种、主送机关、落款、日期、正文缩进与三级序号连续性；返回 `passed/issues`，`ERROR` 决定是否通过，`WARN` 只提示；最多返回 50 条正文问题；不修改原文。
- 用语检查：`POST /api/terms/check`，内置 12 个错别字/规范用语词条，支持 `app.terms.overrides` 覆盖；返回 `term/suggestion/category/position/line/column/context`，`position` 从 0 开始，行列从 1 开始。
- Dify Workflow「公文起草与校验-Workflow」（应用 ID `39259284-937e-41de-b975-a093f8da7a14`）：
  - 开始 → 草稿是否为空 → 知识检索/LLM 生成或直接使用草稿 → 草稿合并；
  - Code 节点构造 JSON → HTTP 格式校验 → 条件分支；
  - 格式通过后执行术语校验 → 条件分支；
  - 格式不通过输出问题列表与原草稿；术语命中输出命中位置与原草稿；全部通过输出草稿；
  - 两个 HTTP 节点均配置 `fail-branch`，失败进入独立兜底节点，保留草稿并提示人工复核。
- 后端代理：`POST /api/workflow/run/stream`，SSE 为 `node* → message* → done`；失败发送 `error`（5004）且不发送 `done`；接受 Dify 的 `succeeded` 与 `partial-succeeded`；请求携带 `X-Trace-Id`，`user=ai-chat-workflow-<traceId>`；日志不记录正文与问题明细。
- 发布版本：`s7-v1.0-final`（发布记录 ID `b6eae619-b334-4fe3-af11-b15863e34f60`）；DSL 见 `dify/dsl/official-doc-workflow-s7.yml`，已用 `include_secret=false` 导出，并把数据集 ID 替换为占位符。
- 技能文档：`docs/skills/official-doc-format.md`，与工具接口、规则、分支和错误处理保持一致。

### 13.2 验证结果

| 项目 | 方式 | 结果 |
|---|---|---|
| 工具单测 | `FormatCheckerTest`、`TermsCheckerTest` | 9/9 通过（正常、字段全空、非法日期、序号不连续、缩进、覆盖词条、位置） |
| 接口单测 | `CheckControllerTest` | 5/5 通过（不改原文、空对象 6 个问题、50001 字返回 400/4000） |
| Workflow 单测 | `DifyWorkflowClientTest`、`WorkflowControllerTest` | 10/10 通过（节点/文本/结果、partial-succeeded、错误不泄漏、HTTP 503、缺 Key、缺 workflow_finished、SSE 终态） |
| 全量后端测试 | `mvn -q test` | 93/93 通过（0 failure / 0 error / 0 skipped） |
| Dify 控制台生成 | 空草稿 + 安全生产检查任务 | 930 字，15 个业务节点全部成功；知识检索 → LLM → 格式/术语校验 → 通过输出 |
| 格式失败分支 | 日期 `2026-02-30` | 输出“格式校验未通过：date: 日期格式不合法或日期不存在”+ 原草稿；不进入术语检查 |
| 术语命中分支 | 草稿含“做为” | 输出“做为→作为（第1行第8列）”+ 原草稿 |
| 格式工具超时 | HTTP 节点临时指向不可达端口 9 | 进入“格式工具兜底”，状态 `partial-succeeded`，输出保留草稿，工作流不中断 |
| 术语工具超时 | 同上，仅术语工具不可达 | 进入“术语工具兜底”，状态 `partial-succeeded`，输出保留草稿 |
| 后端干净路径 | `/api/workflow/run/stream` + 合规草稿 | `done.status=succeeded`，返回“格式与术语检查均通过”+ 草稿，含 `runId/traceId` |
| 后端格式失败 | input 含换行导致标题非法 | `done.status=succeeded`，返回格式问题与原草稿 |
| 后端术语命中 | 草稿含“做为” | `done.status=succeeded`，返回命中位置与原草稿 |
| 后端工具超时 | 临时发布不可达工具版本 | `done.status=partial-succeeded`，返回“格式校验工具暂时不可用”+ 原草稿；无 `error`，569 ms |
| 真实生成路径 | 空草稿 + 安全生产任务 | 9.5 s，节点含知识检索/草稿生成，最终通过格式与术语检查，输出 930 字 |
| traceId | 单测 + 后端 SSE | 请求头 `X-Trace-Id` 与 `done.traceId` 一致；Dify `user` 带同一 traceId |

### 13.3 问题与修复

- Dify Code 节点经控制台同步后会把源码字符串中的 `\n` 转成真实换行，导致语法错误；改用 `chr(10)` 拼接换行，避免在代码源码中写转义换行。
- Dify 工具失败后的工作流状态是 `partial-succeeded`，后端原先只接受 `succeeded` 会误报 5004；已改为接受 `partial-succeeded`，并增加单测。
- Dify HTTP Request 节点默认经 SSRF 代理拒绝私网地址；在 Dify 部署中增加 `SSRF_PROXY_ALLOW_PRIVATE_DOMAINS=host.docker.internal` 并重启 `ssrf_proxy`，只放行该域名。
- 真实 Dify 调用需要服务 API Key；Key 只写入被 gitignore 的 `.env`（`DIFY_WORKFLOW_API_KEY`），未提交、未打印。

### 13.4 限制与后续

- 格式规则是静态启发式，只覆盖结构、缩进、序号和字段必填；不判断文种适用性、语义完整性和政策依据真实性。
- `WARN` 不阻止输出；S8 人工确认可把 `WARN`/术语命中作为确认项。
- `documentIds` 已进入后端请求契约，但 S7 Workflow 未使用；知识检索固定使用 B 库，后续 S8/S9 再决定是否按文档范围检索。
- 生成草稿中的“资料不足，需人工补充”属于模型依据不足时的显式提示，不是格式错误；S7 不自动改写。

### 13.5 验收结论

S7 通过（2026-11-25）。格式/术语工具、Dify Workflow、后端 SSE 代理、Dify 控制台与后端两条运行路径均跑通；格式不通过、术语命中、格式/术语工具超时四种情况均按预期分流，工具失败不中断工作流；全量后端测试 93/93 通过。

## 14. S8 人工确认、状态查询与导出（2026-11-25）

### 14.1 已实现

- Dify Workflow 发布 `s8-v1.0`（发布记录 ID `83f85623-25f2-4e46-8f77-22d7648ee60d`）：在 `final_agg` 与 `end` 之间新增 `human-input` 节点 `human_confirm`，动作 `approve/modify/reject`，三个动作均连接到 `end`；结束节点输出改为 `human_confirm.content`。DSL 见 `dify/dsl/official-doc-workflow-s8.yml`。
- 运行状态模型：`WorkflowRunStatus`（`RUNNING/WAITING_CONFIRM/SUCCEEDED/FAILED/REJECTED`）、草稿、文档元信息、工具结果、节点快照、确认结果与状态历史；`form_token` 仅保存在后端内存，响应中不暴露。
- `GET /api/workflow/{runId}`：返回状态、草稿、工具结果、节点、历史、错误与确认信息；过期确认惰性标记为 `FAILED` + `5005`。
- `POST /api/workflow/{runId}/confirm`：仅允许 `approve/modify/reject`；`modify` 内容非空且最多 50000 字；重复确认、非法动作、已过期或记录不存在返回 `5005`；确认成功后通过 Dify 服务 API 提交表单，状态转为 `SUCCEEDED` 或 `REJECTED`。
- `GET /api/workflow/{runId}/export?format=md|docx`：仅 `SUCCEEDED` 允许导出；未确认、已驳回、失败、记录不存在返回 `5006`。Markdown 包含标题、主送、正文、落款、日期、校验摘要与确认结果；DOCX 使用 Apache POI 4.1.2，宋体、标题居中，文件名清洗并设置 UTF-8 `Content-Disposition`。
- 前端：新增“公文工作流”页、`useWorkflow` 组合式函数与 `workflow.js` API；支持发起/停止、节点进度、工具结果、待确认草稿编辑、通过/修改/驳回、状态历史、Markdown/DOCX 下载与 5 秒轮询。`npm run build` 通过（38 modules）。

### 14.2 验证结果

| 项目 | 方式 | 结果 |
|---|---|---|
| 后端聚焦测试 | `WorkflowS8ControllerTest`、`DifyWorkflowClientTest`、`WorkflowControllerTest` | 15/15 通过（暂停、表单提交、修改/驳回、非法动作、过期、未确认导出、未知 run 导出、Markdown/DOCX） |
| 全量后端测试 | `mvn -q -f backend/pom.xml test` | **98/98 通过（0 failure / 0 error / 0 skipped）** |
| 前端构建 | `npm run build` | 通过，`dist` 正常生成 |
| Dify 真实暂停 | `/api/workflow/run/stream`，空草稿 + 安全生产检查任务 | 16.1 s，16 个节点，SSE 以 `done.status=WAITING_CONFIRM` 结束，`runId=f1d6111c-c56e-4f02-b18e-c2ff2503a064` |
| 状态查询 | `GET /api/workflow/{runId}` | `WAITING_CONFIRM`，草稿 1443 字，`format.passed=true`、`terms.has_hits=false`，不返回 `formToken` |
| 非法动作 | `POST confirm` body `{"action":"bad"}` | HTTP 200 + `code=5005`，无未处理 500 |
| 修改后确认 | `POST confirm` body `modify` + 修改正文 | 0.23 s，`status=SUCCEEDED`，`confirmation.action=modify`；Dify `workflow_run` 为 `succeeded`，`human_confirm` 与 `end` 节点均 `succeeded` |
| 导出 Markdown | `GET export?format=md` | HTTP 200，1566 字，标题/主送/正文/落款/日期/校验摘要/确认结果齐全，含人工追加内容 |
| 导出 DOCX | `GET export?format=docx` | HTTP 200，`unzip -t` 无错误；`document.xml` 可读出标题、正文、落款与校验摘要，中文未乱码 |
| 未确认导出 | 第二次暂停后直接导出 | `code=5006`，HTTP 200 |
| 驳回 | 第二次暂停后 `reject` | `status=REJECTED`，历史保留 `WAITING_CONFIRM → REJECTED`；Dify run `succeeded`；驳回后导出再次返回 `5006` |
| 重复确认 | 同一 run 再次 `reject` | `code=5005`，不重复提交表单 |
| 未知 run 导出 | `GET export` 不存在 run | `code=5006`（修复后与导出契约一致） |

### 14.3 问题与修复

- **暂停时草稿为空**：S8 初版仅在收到 `text_chunk/message` 或 `workflow_finished.outputs` 时才补 answer；真实 Workflow 在 `human_input` 暂停前由 `variable-aggregator` 产出最终文本，既没有流式文本也没有 `workflow_finished`，导致 `draft/answer` 为空。已改为解析 `human_input_required.data.form_content`（并回退 `resolved_default_values.content`），暂停时用它补全文本；S8 聚焦测试改为不发送流式增量，覆盖该路径。
- **未知 run 导出码不一致**：`requireExportable` 原先复用 `requireRun` 返回 `5005`；已改为直接返回 `5006`，并补充接口测试。
- **Dify 表单 token 格式**：真实 token 为 22 位 URL-safe 字符串，符合后端 `[A-Za-z0-9_-]{1,200}` 校验，无需放宽。
- **Dify 表单提交**：真实服务 API 接受 `{inputs:{content}, action, user}`，提交后 Dify 异步恢复；后端不会回读恢复流，演示时用 Dify `workflow_runs` 证据说明恢复完成。

### 14.4 限制与后续

- 运行状态仅保存在后端进程内存（`ConcurrentHashMap`），重启清空，不支持多实例；暂停后不要重启后端。
- 确认成功即返回 `SUCCEEDED/REJECTED`，Dify 若在恢复后执行失败，后端不会反向更新状态；S9 可考虑消费恢复流或落库。
- 导出使用确认时保存的草稿/正文；DOCX 样式为最小可用版（宋体、标题居中、落款右对齐），不替代正式红头模板。
- 过期时间由 Dify `human_input` 节点决定（当前 1 天），后端仅在查询/确认时惰性标记；未实现后台定时清理。

### 14.5 验收结论

S8 通过（2026-11-25）。真实链路完成“发起 → 工具校验 → 待确认 → 修改 → 确认 → 导出 Markdown/DOCX”，并验证驳回、未确认导出、非法动作、重复确认与过期/未知 run；无未处理 500。全量后端测试 98/98 通过。

## 15. S9 文档、DSL、数据清单与四批回归（2026-11-26）

### 15.1 范围与原则

- 本阶段不新增业务功能、不修改 S8 工作流、不新增 Dify 发布版本、不打 `stage2-v1.0` tag。
- 只做工程化收口：文档/API 契约、DSL 说明、数据清单、演示脚本、验证记录、回归脚本与证据。
- 回归按四批执行：静态检查、构建测试、真实 Workflow E2E、文档/DSL 一致性；完整日志在 `logs/regression/20261126-1441/`（已 gitignore），本文只记录摘要。
- 不引入 springdoc；接口契约以 README + `docs/api.md` 的 Markdown/curl/SSE 示例为准，并由静态一致性脚本检查路径、环境变量、错误码和链接。

### 15.2 文档与数据变更

- `README.md`：状态更新到 S9；补齐 `DIFY_WORKFLOW_API_KEY`、`DIFY_WORKFLOW_TIMEOUT_SECONDS`、轮询/超时/分段等环境变量；补充 OpenAPI 选择、FR-10/FR-13 差异与数据来源状态；克隆说明改为默认分支，`stage2-v1.0` 留给 S10。
- `docs/api.md`：升级 v2.0，补充知识库上传/列表、RAG SSE、格式/术语检查、Workflow 运行/状态/确认/导出、状态机与 5002–5006；阶段二测试清单。
- `docs/dataset-manifest.md`：从 PDF 内嵌链接提取并核实 3 条中国政府网 PDF 来源（HTTP 200）；其余 17 条保留“URL 待补录”，不编造。
- `docs/demo-script.md`：新增阶段二 RAG 与 Workflow 的 12 分钟演示、备用命令和风险兜底。
- `docs/dify-setup.md`、`docs/skills/official-doc-format.md`、`dify/dsl/README.md`：补充 S8/S9 版本与最终 DSL 口径，明确 S9 不复制重复 DSL。
- `scripts/regression/workflow_e2e.py`、`scripts/regression/check_docs_consistency.py`：新增可重复执行的真实验收和静态一致性检查脚本。

### 15.3 批次 1：静态检查

| 项目 | 结果 |
|---|---|
| `git status --short` / `git diff --check` | 通过；`core.whitespace=cr-at-eol`，无空白错误 |
| `.env` 是否被跟踪 | 未被跟踪 |
| 真实资料/评测导出是否被跟踪 | `data/docs/` 未被跟踪；`data/eval/` 仅保留脱敏 15 题夹具 `questions.json`，其余忽略 |
| 跟踪文件密钥扫描 | 未发现 `sk-*`/`app-*`/Bearer 真实 Key 模式 |
| `stage2-v1.0` tag | 不存在（S10 创建） |
| 3 条新增来源 URL | 均返回 HTTP 200 |
| 过期状态文字 | 已清理 README 的“下一步 S9/S10”“阶段二截至 S8 尚未完成”等 |

### 15.4 批次 2：构建、测试与阶段一冒烟

| 项目 | 结果 |
|---|---|
| 后端全量测试 | `mvn -f backend/pom.xml clean test`：**98/98 通过，0 failure / 0 error / 0 skipped**，`BUILD SUCCESS` |
| 前端构建 | `npm run build`：通过，38 modules transformed，`dist` 正常生成 |
| `/api/health` | HTTP 200，`code=0` |
| `/api/extract` | 正常样例返回五字段，`code=0`，中文未乱码 |
| `/api/chat/stream` | UTF-8 请求文件（避免 Windows shell 编码问题）返回 21 个 `message` + 1 个 `done`，无 error |

### 15.5 批次 3：真实 Workflow E2E

前置：Dify 1.19.0 已启动，`docker-api-1` 可用；后端 8080；`.env` 已配置真实 Workflow Key（未打印、未提交）。脚本 `scripts/regression/workflow_e2e.py` 串行执行 12 步：

| 步骤 | 结果 |
|---|---|
| 空草稿真实生成并暂停 | `done.status=WAITING_CONFIRM`，31 个 node、1 个 message；runId `8cf6e28a-415c-4ac1-9976-aa3dd50e03c8` |
| 查询状态 | `WAITING_CONFIRM`，草稿 1054 字，响应不含 `formToken`/`form_token` |
| 非法 action | `code=5005`，HTTP 200，无未处理 500 |
| `modify` 并追加唯一标记 | `status=SUCCEEDED` |
| 导出 Markdown | 3357 字节，`text/markdown;charset=UTF-8`，含唯一标记 |
| 导出 DOCX | 4144 字节；`zipfile` 读取 `word/document.xml`，XML 可解析，含唯一标记与“校验摘要”，中文未乱码 |
| 新 run 未确认导出 | `code=5006` |
| `reject` | `status=REJECTED` |
| 驳回后导出 | `code=5006` |
| 重复确认 | `code=5005` |
| 未知 run 导出 | `code=5006` |
| 不支持格式 `pdf` | `code=5006` |

- 总耗时约 32.5 s；证据：`logs/regression/20261126-1441/e2e/e2e.json`、`export.md`、`export.docx`。
- 运行状态保存在后端内存；脚本执行后不重启后端，临时运行记录随进程结束清理。

### 15.6 批次 4：文档/DSL 一致性

`scripts/regression/check_docs_consistency.py` 的 8 项检查均通过：

- 控制器路径全部出现在 README 与 `docs/api.md`；
- `.env.example`、`application.yml`、README 环境变量表一致（`DIFY_SEPARATOR` 为内部可选默认值，单独告警）；
- `ErrorCodes` 的 4000/5001–5006 与 `5000` 均出现在 API 文档；
- S8 DSL 含 `human-input`、`human_confirm`、`REPLACE_WITH_DATASET_B_ID`，不含真实数据集 ID、Key 或 Bearer；
- 数据清单 20 行、A 229 段、B 138 段、17 条待补录 URL；
- README/API 文档相对链接有效；
- README 状态已更新到 S9，无过期 S9/S10 文字；
- 跟踪文件不含 `.env`、真实资料或评测导出（15 题夹具例外已记录）。

### 15.7 已知差异与未完成项

- **FR-10**：需求要求驳回后回退生成节点并最多重试 1 次；当前实现为 `REJECTED` 终态，重新发起任务。S9 未改功能，记录为已知差异。
- **FR-13**：需求提到导出含引用列表；当前导出含正文、格式/术语摘要和确认结果，不含知识检索引用列表。S9 未改功能，记录为已知差异。
- **来源 URL**：17 条待用户按文件名补充；补齐后可用 `scripts/dify/import_datasets.py --source-map` 重新生成清单。
- **S10**：干净环境彩排、15 题最终评测复核、汇报材料与 `stage2-v1.0` tag。

### 15.8 验收结论

S9 通过（2026-11-26）。文档、API 契约、DSL 说明、数据清单、演示与验证记录已收口；98/98 后端测试、前端构建、阶段一 health/chat/extract 冒烟和真实 Workflow 12 步 E2E 均通过；5005/5006 无未处理 500；8 项文档/DSL 一致性检查通过。未打 tag、未 push、未提交密钥或真实资料。
