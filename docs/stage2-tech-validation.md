# 阶段二技术验证记录（S0–S2）

> 状态：S0 已通过（2026-11-24）；S1 已完成（2026-11-24）；S2 实现与真实数据验收已完成（A/B 各 20 份 completed；来源 URL 待补录，2026-11-25）
> 关联：`docs/08-阶段二执行计划.md` v1.1 S0–S2；`docs/07-阶段二需求文档.md` v1.0
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
- **策略 A 碎段风险**：Dify `max_tokens` 是 token 上限，不是字符数；A 在 500 token 下出现标题行孤立（《基金会管理条例》最短 5 字）。A 的整体字符范围 5–500，B 为 186–800。S6 评测前需评估是否接受该偏差，或调整 A 参数/后处理；不得在评测报告中隐去。
- **Dify 应用 Key 未就绪**：当前只有知识库 dataset Key；S4 的 RAG 流式对话需要 Dify 应用 API Key，尚未配置。
- 文档元数据仍为内存存储，后端重启清空，属于计划内已知限制。
