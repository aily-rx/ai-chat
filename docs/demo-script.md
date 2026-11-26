# 阶段一/阶段二演示脚本

> **2026-11-27 W4 现场演示请使用 [阶段二 8 分钟演示脚本](demo-script-stage2.md)。** 本文件保留阶段一现场脚本（第 0–6 节）和第 7 节阶段二 12 分钟历史版本，作为对照与备用材料，不再作为 W4 现场主脚本。
>
> 目标：在 8 分钟内完成阶段一“Vue 页面 → Spring Boot → DeepSeek 流式返回”、Prompt 变量切换和结构化抽取演示；阶段二补充 RAG 问答与公文 Workflow 人工确认/导出演示。失败时按备用方案切换。
>
> 阶段一提交版本：`stage1-v1.4`（commit `b73d91b`）；阶段二当前代码在默认分支，`stage2-v1.0` 由 S10 完成彩排后创建。
>
> 汇报时间：2026-11-13 阶段一现场演示，不播录屏；阶段二演示按第 7 节执行。

## 0. 8 分钟速查

完整命令和异常兜底见下文；现场按这张表控制时间，超时优先保留 4.2、4.3、4.5。

| 时间 | 现场动作 | 预期 | 兜底 |
|---|---|---|---|
| 0:00–0:30 | 打开仓库，确认 tag、README、`.env` 未跟踪 | 无真实 Key，仓库可复现 | 展示 `git ls-files` 与 `docs/release-tag.md` |
| 0:30–2:00 | 正式风格提问“介绍一下RAG” | 逐字流式输出，首字 0.90 s，总耗时 2.41 s | 切 Mock 或展示 `docs/streaming-test.md` |
| 2:00–3:00 | 切换简洁风格，再问同一问题 | 输出 420 → 66 字符，风格差异明显 | 展示两次回答文本对比 |
| 3:00–3:30 | 输入长任务并点击“停止” | 停止后不再追加，显示“已停止生成” | 展示截图/历史测试记录 |
| 3:30–5:00 | 正常样例 + 稀疏样例结构化抽取 | 五字段正确；稀疏样例全为 null | 用 `docs/samples/` + curl 备用 |
| 5:00–6:00 | 展示 traceId 与后端日志 | 前后端 traceId 可对应 | 日志检索历史 traceId |
| 6:00–7:00 | 打开 Dify「公文问答助手」提一个问题 | 返回规范中文答案 | 展示 `docs/dify-setup.md` 的复测记录 |
| 7:00–8:00 | 预留异常演示、导师提问和收尾 | 不播录屏，现场可操作 | 进入备用方案 |

## 0.1 演示前检查（提前 10 分钟）

```bash
cd ai-chat
git status
git log --oneline -3
git tag -l

java -version
mvn -version
node -v
npm -v
docker version
```

本地 `.env` 必须存在且包含 DeepSeek Key（不要投屏 Key 内容）：

```bash
cp .env.example .env
# 编辑 .env，填入 LLM_API_KEY
```

前端真实模式：

```bash
cd frontend
cp .env.example .env
# 确认 VITE_USE_MOCK=false（默认值，连接真实后端）
npm install
```

## 1. 启动后端（约 1 分钟）

```bash
cd ai-chat
mvn -f backend/pom.xml test
mvn -f backend/pom.xml spring-boot:run
```

另开终端验证：

```bash
curl -i http://localhost:8080/api/health
```

预期：HTTP 200，响应体 `code=0`，响应头包含 `X-Trace-Id`。

## 2. 启动前端（约 30 秒）

```bash
cd ai-chat/frontend
npm run dev
```

打开 http://localhost:5173，右上角状态应为“后端正常”。

## 3. 启动 Dify（独立交付物）

```bash
cd E:/Dify/dify/docker
docker compose up -d
docker compose ps
```

打开 http://localhost，进入 Chatbot「公文问答助手」。若 Docker Desktop 报 `unable to start`：

```powershell
wsl --shutdown
docker desktop restart
```

## 4. 演示步骤与话术

### 4.1 仓库与密钥检查（1 分钟）

```bash
git ls-files | grep -E "(^|/)\.env$" || echo "no .env tracked"
git log --all -p | grep -E "sk-[A-Za-z0-9]{20,}" | head
```

预期：没有 `.env` 被跟踪；没有真实 Key（只允许 `.env.example` 中的 `sk-xxxxxxxx-placeholder`）。

话术：模型 Key 只放在本地 `.env`，通过 `application.yml` 的 `optional:file` 加载，仓库和 Git 历史不包含密钥。

### 4.2 流式对话 + 首字延迟（2 分钟）

1. 在页面输入：`介绍一下RAG`。
2. 保持“正式”风格，点击发送。
3. 观察逐字输出、首字耗时、总耗时和 traceId。

预期：首字 < 3 秒；回答完整；Markdown 标题/列表正常；底部显示 traceId。

实测参考：正式风格首字 0.90 s，总耗时 2.41 s。

### 4.3 Prompt 变量切换（1 分钟）

1. 将“回答风格”从“正式”切换为“简洁”。
2. 再次发送同一个问题。
3. 对比两次输出长度和语气。

预期：简洁版明显更短、直入主题。实测参考：420 字符 vs 66 字符。

### 4.4 停止生成（30 秒）

1. 输入长任务：`写一份关于开展安全生产检查的通知，正文不少于800字。`
2. 点击“停止”。
3. 观察消息状态。

预期：停止后不再追加内容，显示“已停止生成”，不出现错误横幅。

### 4.5 结构化抽取（2 分钟，重点）

1. 点击顶部“结构化抽取”。
2. 点击“加载正常样例”，再点击“开始抽取”。
3. 展示五字段表格和原始 JSON。

预期：

```json
{
  "docType": "通知",
  "title": "关于开展安全生产检查的通知",
  "recipient": "各部门",
  "signature": "XX办公室",
  "date": "2026-11-01"
}
```

4. 点击“加载稀疏样例”或直接粘贴 `今天天气不错，适合户外活动。`，再次抽取。

预期：五个字段全部为 `null`，HTTP 200，不报 500。

5. 说明兜底逻辑：模型输出带 Markdown 围栏会先剥离；JSON 解析失败重试 1 次；两次失败返回 `5003`；模型调用失败返回 `5001`。

#### 备用验证命令（页面异常时）

```bash
curl -s -X POST http://localhost:8080/api/extract -H "Content-Type: application/json" --data-binary @docs/samples/extract-normal.json
```

预期返回 `code=0` 和五个字段。

### 4.6 traceId 与日志（30 秒）

在页面底部记录 traceId，然后查看后端终端日志：

```text
INFO [xxxxxxxx] [chat-stream-1] ... chat stream completed traceId=xxxxxxxx chars=...
```

说明：前端展示的 traceId 与后端日志一致，可按 traceId 串联一次请求。

### 4.7 Dify Chatbot（1 分钟）

1. 打开 http://localhost，进入「公文问答助手」。
2. 提问：`请示与报告的主要区别是什么？`
3. 展示返回的规范中文答案。

说明：Dify 是独立交付物，用于第一周成果演示，不接入主链路；主链路仍是 Vue3 → Spring Boot → DeepSeek。

## 5. 失败案例与备用方案

| 风险 | 备用方案 |
|---|---|
| DeepSeek 接口超时/限流 | 展示 `docs/streaming-test.md` 的历史记录；前端切换到 Mock 模式演示流式效果；说明错误码 5001 与 traceId 提示 |
| 前端无法访问后端 | 将 `frontend/.env` 的 `VITE_USE_MOCK` 改为 `true`，重启 Vite；演示 Mock 流式对话和 5003 失败样例 |
| 结构化抽取现场解析失败 | 运行 `mvn -f backend/pom.xml -Dtest=ExtractionServiceTest test`，展示重试与 5003 测试；或切到 Mock 失败样例 |
| Dify 无法登录/容器异常 | 展示 `docs/dify-setup.md` 与已记录的 3 个测试结果；执行 `docker compose ps` 说明版本和端口 |
| 端口被占用 | 后端默认 8080，前端严格使用 5173；先关闭占用进程，或按 README 修改 `SERVER_PORT` 与 Vite 代理 |

## 6. 演示后收尾

```bash
# 停止前端/后端：在对应终端按 Ctrl+C
cd E:/Dify/dify/docker
docker compose down
```

## 7. 阶段二演示历史版本（RAG + Workflow，约 12 分钟）

> W4 现场请使用 [阶段二 8 分钟演示脚本](demo-script-stage2.md)；本节仅保留为早期 12 分钟版本与备用命令。

### 7.1 演示前检查

1. Dify 已启动，`docker-api-1` 为 `healthy`；三个应用可用：`RAG对话`（B）、`RAG对话-A`、`公文起草与校验-Workflow`（发布 `s8-v1.0`）。
2. 后端根目录 `.env` 已配置 A/B 数据集 ID、`DIFY_APP_API_KEY`、`DIFY_APP_API_KEY_A`、`DIFY_WORKFLOW_API_KEY`；不要投屏 Key。
3. 后端 8080、前端 5173 已启动；`GET /api/health` 返回 `code=0`。
4. 前端“知识库问答”和“公文工作流”页均连接真实后端，不用 Mock。

### 7.2 12 分钟速查

| 时间 | 现场动作 | 预期 |
|---|---|---|
| 0:00–1:00 | 说明阶段二链路：Vue3 → Spring Boot → Dify → 知识库/Workflow | 主链路与阶段一区分清楚 |
| 1:00–4:00 | “知识库问答”选 B 库，问“2027年元旦放假几天？” | 逐字输出，引用卡片可定位；答案与资料一致 |
| 4:00–5:30 | 问资料外问题，再切 A 库重复一次 | 拒答固定话术、空引用；A/B 路由正确 |
| 5:30–8:30 | “公文工作流”空草稿发起，等待 `WAITING_CONFIRM` | 节点进度、格式/术语校验结果、待确认草稿 |
| 8:30–10:30 | 修改正文后通过，下载 Markdown/DOCX | `SUCCEEDED`；导出含修改内容、校验摘要、引用列表与确认记录 |
| 10:30–12:00 | 再发起一次，填写驳回意见并驳回，等待重新生成后再次驳回，尝试导出 | `WAITING_CONFIRM → RETRYING → WAITING_CONFIRM → REJECTED`；导出返回 `5006`，不产生文件 |

### 7.3 RAG 问答演示

1. 打开“知识库问答”，选择 B 库。
2. 提问：`2027年元旦放假几天？`
3. 观察：逐字输出、引用编号、引用卡片（文档名、段落序号、摘录、分数）、点击编号定位高亮。
4. 提问资料外问题，例如：`请预测2030年世界杯冠军。`
5. 预期：固定拒答话术，`citations.items=[]`，不伪造引用。
6. 切换 A 库，重复一个节日问题，说明后端按 `datasetId` 白名单路由。

说明：RAG 的引用来自 Dify 检索资源；无引用且非拒答时后端按错误处理，不把无依据回答计为成功。

### 7.4 Workflow 人工确认与导出

1. 打开“公文工作流”，任务填写：`起草一份关于开展安全生产检查的通知`，草稿留空。
2. 点击发起，观察节点进度；等待 SSE 以 `WAITING_CONFIRM` 结束。
3. 展示格式/术语校验结果和待确认草稿；说明 `form_token` 只在后端保存，前端只拿 `runId`。
4. 在草稿末尾追加可识别文本，例如 `【演示修改】请各部门于本周五前反馈。`，选择“修改后通过”。
5. 预期：状态 `SUCCEEDED`，确认历史出现 `WAITING_CONFIRM → SUCCEEDED`。
6. 下载 Markdown 和 DOCX；验证中文、标题、正文、“校验摘要”、引用列表（文档名/段落序号/相关度/摘录）和确认记录（动作/时间/traceId）。若本次直接用了已有草稿，导出会明确写“未执行检索”，不伪造引用。
7. 再发起一次，等待暂停后填写驳回意见并点击“驳回并重新生成”；预期 `RETRYING`，后台重新生成后回到 `WAITING_CONFIRM`，草稿按驳回意见更新。
8. 对新草稿再次点击“驳回”；预期 `REJECTED`，导出返回 `5006`。
9. 说明限制：运行状态在后端内存，暂停后重启后端会丢失；驳回回退通过重新调用同一 Workflow 实现（清空 `draft`，上一版草稿与驳回意见写入任务描述以强制重新生成），不是 Dify 节点级回滚；最多重试 1 次；引用列表来自检索节点，检索未命中时导出会如实注明。

#### 备用命令

```bash
# 发起（空草稿，真实 Dify）
curl -sN -X POST http://localhost:8080/api/workflow/run/stream \
  -H 'Content-Type: application/json' \
  -d '{"input":"起草一份关于开展安全生产检查的通知","draft":""}'

# 查询、确认、导出；RUN_ID 替换为上一步 done.runId
curl -s http://localhost:8080/api/workflow/$RUN_ID
curl -s -X POST http://localhost:8080/api/workflow/$RUN_ID/confirm \
  -H 'Content-Type: application/json' \
  -d '{"action":"modify","content":"修改后的正文"}'
curl -OJ "http://localhost:8080/api/workflow/$RUN_ID/export?format=md"
```

### 7.5 阶段二风险与兜底

| 风险 | 兜底 |
|---|---|
| Dify 未启动/应用未发布 | 展示 `docs/dify-setup.md`、`dify/dsl/README.md` 和 S8 验收记录；不伪造实时结果 |
| Workflow 生成超时 | 检查 Dify 容器与 `DIFY_WORKFLOW_API_KEY`；展示 `logs/regression/` 下的真实 E2E 证据 |
| 导出失败 | 先确认状态为 `SUCCEEDED`；未确认/重新生成中/已驳回返回 `5006` 属于预期 |
| 暂停后误重启后端 | 重新发起任务；内存状态无法恢复，这是已知限制 |
| 引用为空 | 检查是否拒答、Dify 检索是否命中；不把无引用回答当作成功 |
