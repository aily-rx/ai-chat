# 阶段二技术验证记录（S0）

> 状态：S0 已通过（2026-11-24）；S1 未开始
> 关联：`docs/08-阶段二执行计划.md` v1.1 S0；`docs/07-阶段二需求文档.md` v1.0
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

## 3. 进展与待验证（S0 进行中）

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

S0 通过（2026-11-24）。文本上传、索引、检索、引用字段与 human_input（Web App 暂停-提交-恢复）均验证通过；可以进入 S1。
