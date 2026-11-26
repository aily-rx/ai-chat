# 接口文档（阶段一 + 阶段二）

| 项目 | 内容 |
|---|---|
| 文档版本 | v2.0（2026-11-26） |
| 适用范围 | 阶段一 W1–W2；阶段二 S1–S9 |
| 后端地址 | `http://localhost:8080` |
| 前端开发地址 | `http://localhost:5173` |
| 协议 | HTTP + JSON；流式接口使用 SSE（`text/event-stream`） |
| 主链路 | 阶段一 Vue3 → Spring Boot → DeepSeek API；阶段二 RAG/Workflow：Vue3 → Spring Boot → Dify → 知识库/Workflow |

> 本文档定义阶段一和阶段二后端对前端暴露的接口契约。Dify 控制台和 Dify Service API 是外部依赖，不在本文档展开。接口示例采用 Markdown + curl/SSE；本项目不引入 springdoc，也不维护单独的 OpenAPI YAML。登录鉴权、会话历史、消息持久化和私有 vLLM 不在当前范围。

## 一、通用约定

### 1.1 请求格式

- 请求体统一使用 `Content-Type: application/json; charset=UTF-8`。
- 前端默认通过 Vite 代理访问 `/api`；后端同时仅对 `http://localhost:5173` 和 `http://127.0.0.1:5173` 开放跨域，并暴露响应头 `X-Trace-Id`。生产环境应由网关配置来源白名单。
- 真实 DeepSeek Key 只保存在后端 `.env`，前端不得持有，也不得出现在日志和响应中。

### 1.2 非流式响应格式

除 SSE 接口外，统一返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "traceId": "a1b2c3d4"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | integer | 业务码，`0` 表示成功 |
| `message` | string | 提示信息；失败时为可读原因 |
| `data` | object / string / null | 业务数据 |
| `traceId` | string | 本次请求的追踪 ID，同时写入响应头 `X-Trace-Id` |

### 1.3 错误码

| 业务码 | HTTP 状态 | 含义 | 前端处理 |
|---|---|---|---|
| `0` | 200 | 成功 | 正常展示 |
| `4000` | 400 | 参数校验失败 | 提示用户修改输入 |
| `5000` | 500 | 未处理的服务端异常 | 展示通用错误和 traceId |
| `5001` | 200 | 模型调用失败、超时、网络异常或输出被截断 | 展示对应错误信息和 traceId |
| `5002` | 200 | Dify 不可用、超时、知识库/RAG 应用未配置或引用不可用 | 展示可读提示和 traceId；SSE 中为终止事件 |
| `5003` | 200 | 结构化抽取结果无法解析，且重试一次后仍失败 | 展示“解析失败，请重试”和 traceId |
| `5004` | SSE `error` | Workflow 执行失败 | 停止流程，展示错误和 traceId；不等 `done` |
| `5005` | 200 | 确认动作非法、重复确认、已过期或运行记录不存在 | 刷新状态或重新发起；不要重复提交 |
| `5006` | 200 | 未确认/已驳回/失败/过期/记录不存在，或导出格式不支持 | 不下载；按提示重新确认或发起 |

说明：

- 非流式接口的“业务失败”统一使用 HTTP 200，前端以 `code` 判断；参数格式错误使用 HTTP 400，未处理异常使用 HTTP 500。
- 流式接口一旦建立 SSE 连接，后续异常通过 `event: error` 返回，不再依赖 HTTP 状态码。

---

## 二、健康检查

### 2.1 基本信息

| 项目 | 内容 |
|---|---|
| 方法 | `GET` |
| 路径 | `/api/health` |
| 用途 | 检查后端是否启动，供演示和验收使用 |
| 鉴权 | 无 |

### 2.2 响应示例

```json
{
  "code": 0,
  "message": "ok",
  "data": "ok",
  "traceId": "a1b2c3d4"
}
```

### 2.3 验收

```bash
curl http://localhost:8080/api/health
```

返回 HTTP 200，且 `code=0`。

---

## 三、流式对话

### 3.1 基本信息

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/chat/stream` |
| 用途 | 接收用户问题，流式返回模型增量内容 |
| 响应类型 | `text/event-stream` |
| 鉴权 | 阶段一为本地单用户，暂不鉴权 |

### 3.2 请求体

```json
{
  "message": "写一份关于开展安全生产检查的通知",
  "style": "formal"
}
```

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `message` | string | 是 | 去除首尾空白后 1–2000 字符 | 用户问题或写作要求 |
| `style` | string | 是 | `formal` 或 `concise` | Prompt 变量，分别对应正式、简洁风格 |

校验失败时返回 HTTP 400：

```json
{
  "code": 4000,
  "message": "message 不能为空",
  "data": null,
  "traceId": "a1b2c3d4"
}
```

### 3.3 SSE 事件

连接建立后，服务端按顺序发送 `message` 增量事件，最后必须发送一个终止事件。

#### 增量事件

```text
event: message
data: {"delta":"关于"}

event: message
data: {"delta":"开展"}

event: message
data: {"delta":"安全生产检查"}

```

`delta` 为本次新增的文本片段，前端按到达顺序追加。

#### 正常结束

```text
event: done
data: {"traceId":"a1b2c3d4"}

```

#### 异常结束

```text
event: error
data: {"code":5001,"message":"模型调用失败","traceId":"a1b2c3d4"}

```

当上游返回 `finish_reason=length` 时，说明输出达到长度上限，返回：

```text
event: error
data: {"code":5001,"message":"模型输出被截断，请缩短问题或重试","traceId":"a1b2c3d4"}

```

### 3.4 事件约定

| 项目 | 约定 |
|---|---|
| 事件终止 | 每次请求必须以 `done` 或 `error` 之一结束 |
| 增量事件 | 只使用 `message`，`data` 为 JSON |
| 空增量 | 可以不发送，但不得发送空字符串导致前端误判 |
| 错误信息 | 不返回上游原始堆栈、模型 Key 或内部地址 |
| traceId | `done` 和 `error` 必须携带，便于前后端排查 |
| 客户端中断 | 用户点击停止时前端取消请求；后端应停止向上游读取并释放资源 |

### 3.5 curl 示例

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"用三句话介绍RAG","style":"formal"}'
```

预期看到多条 `event: message`，最后收到 `event: done`。

### 3.6 前端接入方式

不能使用 `EventSource`，因为 `EventSource` 只支持 GET，而本接口需要 POST。使用 `fetch` + `ReadableStream`：

```js
const res = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ message, style }),
  signal
});

const reader = res.body.getReader();
const decoder = new TextDecoder();
let buffer = '';

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });

  const parts = buffer.split('\n\n');
  buffer = parts.pop() ?? '';
  for (const part of parts) handleSse(part);
}
```

前端需要：

- 按空行切分 SSE 事件；
- 解析 `event:` 和 `data:` 字段；
- 将 `delta` 追加到当前助手消息；
- 收到 `done` 结束生成状态；
- 收到 `error` 展示错误信息和 traceId；
- 使用 `AbortController` 支持停止生成；
- 记录从发送到第一个 `delta` 的耗时，用于验收首字 ≤3 秒。

---

## 四、结构化抽取

### 4.1 基本信息

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/extract` |
| 用途 | 从公文文本中抽取固定字段并返回 JSON |
| 响应类型 | `application/json` |
| 鉴权 | 阶段一为本地单用户，暂不鉴权 |

### 4.2 请求体

```json
{
  "text": "……公文原文，长度 1–10000 字符……"
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `text` | string | 是 | 去除首尾空白后 1–10000 字符 |

### 4.3 成功响应

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "docType": "通知",
    "title": "关于加强安全生产检查的通知",
    "recipient": "各部门",
    "signature": "XX办公室",
    "date": "2026-11-01"
  },
  "traceId": "a1b2c3d4"
}
```

字段要求：

| 字段 | 类型 | 是否可为空 | 说明 |
|---|---|---|---|
| `docType` | string \| null | 是 | 文种，如通知、请示、报告 |
| `title` | string \| null | 是 | 公文标题 |
| `recipient` | string \| null | 是 | 主送单位 |
| `signature` | string \| null | 是 | 落款单位或个人 |
| `date` | string \| null | 是 | 成文日期，统一为 `YYYY-MM-DD` |

五个字段必须全部存在；无法识别的字段返回 `null`，不能省略字段名。

### 4.4 失败响应

模型输出无法解析，重试一次后仍失败：

```json
{
  "code": 5003,
  "message": "模型输出无法解析，已重试 1 次",
  "data": null,
  "traceId": "a1b2c3d4"
}
```

模型调用失败：

```json
{
  "code": 5001,
  "message": "模型调用失败",
  "data": null,
  "traceId": "a1b2c3d4"
}
```

### 4.5 后端处理要求

1. 调用 DeepSeek `stream=false` 的 `/chat/completions`。
2. 抽取请求设置 `temperature=0.0`（可通过 `LLM_EXTRACT_TEMPERATURE` 调整），降低输出随机性。
3. 要求模型只输出 JSON，不输出解释文字。
4. 去除 Markdown 代码围栏，例如 ```json 和 ```。
5. 使用 Jackson 解析为固定字段对象。
6. 校验字段是否存在、类型是否正确、日期是否为 `YYYY-MM-DD`。
7. 解析失败时重试一次；仍失败返回 `code=5003`。
8. 原始模型输出只写日志，不返回给前端。
9. 不出现未处理的 HTTP 500 堆栈。

### 4.6 curl 示例

```bash
curl -X POST http://localhost:8080/api/extract \
  -H "Content-Type: application/json" \
  -d '{"text":"关于开展安全生产检查的通知\n各部门：\n……\nXX办公室\n2026年11月1日"}'
```

---

## 五、阶段二接口（S1–S9）

> 阶段二接口分为知识库/RAG 和公文 Workflow。Dify 控制台与 Dify Service API 属于外部依赖，不在本文件定义；后端对前端暴露的契约以下文为准。

### 5.1 知识库文档上传与列表

#### `POST /api/kb/documents`

上传文件到默认数据集（`DIFY_DEFAULT_DATASET_ID`，当前为 B 库），并异步轮询索引状态。

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/kb/documents` |
| 请求类型 | `multipart/form-data`，文件字段名 `file` |
| 限制 | pdf/docx/txt/md；单文件 ≤20MB；空文件拒绝 |
| 鉴权 | 阶段二为本地单用户，暂不鉴权 |

成功响应（HTTP 200）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "1c8f...",
    "fileName": "基金会管理条例.pdf",
    "contentType": "application/pdf",
    "size": 401935,
    "status": "INDEXING",
    "chunkCount": null,
    "createdAt": "2026-11-26T06:00:00Z",
    "errorMessage": null,
    "difyDatasetId": "9bff...",
    "difyDocumentId": "4417..."
  },
  "traceId": "a1b2c3d4"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 本地记录 ID |
| `fileName` | string | 清洗后的文件名 |
| `contentType` | string | 解析后的 MIME |
| `size` | long | 字节数 |
| `status` | string | `PENDING`/`INDEXING`/`READY`/`FAILED`；API 通常返回 `INDEXING` 或 `FAILED` |
| `chunkCount` | integer \| null | 索引完成后为分段数 |
| `createdAt` | ISO-8601 | 创建时间 |
| `errorMessage` | string \| null | 失败原因（脱敏） |
| `difyDatasetId` / `difyDocumentId` | string \| null | Dify 侧 ID |

- 校验失败：HTTP 400 + `code=4000`；
- Dify 不可用/超时：HTTP 200 + `code=5002`，文档记录标记为 `FAILED`；
- 上传成功返回 `INDEXING`，后台按 `DIFY_POLL_INTERVAL_MS` 轮询，最长 `DIFY_POLL_TIMEOUT_MS`；完成后为 `READY` 并写 `chunkCount`；
- 文档元数据保存在后端内存，重启后清空；Dify 侧文档不删除。

#### `GET /api/kb/documents`

返回内存中的文档列表，按 `createdAt` 倒序：

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": "1c8f...",
      "fileName": "基金会管理条例.pdf",
      "contentType": "application/pdf",
      "size": 401935,
      "status": "READY",
      "chunkCount": 27,
      "createdAt": "2026-11-26T06:00:00Z",
      "errorMessage": null,
      "difyDatasetId": "9bff...",
      "difyDocumentId": "4417..."
    }
  ],
  "traceId": "a1b2c3d4"
}
```

### 5.2 RAG 流式问答

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/rag/chat/stream` |
| 响应类型 | `text/event-stream` |
| 前置 | Dify 已启动；`.env` 已配置 A/B 数据集、`DIFY_APP_API_KEY`、`DIFY_APP_API_KEY_A` |

请求体：

```json
{
  "question": "2027年元旦放假几天？",
  "datasetId": "B"
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `question` | string | 是 | 去除首尾空白后 1–1000 字符 |
| `datasetId` | string | 否 | `A`/`B` 别名或已配置的实际数据集 ID；省略时使用默认库（当前 B） |

- `datasetId` 非法：SSE 建立前返回 HTTP 400 + `code=4000`；
- 知识库/RAG 应用未配置：SSE 建立前返回 HTTP 200 + `code=5002`；
- 正常事件顺序：`message*` → `citations` → `done`；
- 拒答：固定拒答话术 + `citations.items=[]` + `done`；
- 非拒答但引用为空，或 Dify/网络异常：`event: error`（`code=5002`）为终止事件，不发送 `done`；
- 前端不得把 error 后的 SSE 当作成功，也不得为无引用回答伪造引用。

事件示例：

```text
event: message
data: {"delta":"根据资料，"}

event: citations
data: {"items":[{"docId":"...","docName":"...pdf","segmentIndex":1,"excerpt":"...","page":null,"score":0.83}]}

event: done
data: {"runId":"...","traceId":"a1b2c3d4","elapsedMs":2656}
```

`citations.items[]` 字段：`docId`、`docName`、`segmentIndex`（1 起始）、`excerpt`（最多 500 字）、`page`（当前不可得，固定 `null`，不伪造）、`score`。

### 5.3 公文格式检查

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/format/check` |
| 用途 | 只报告格式问题，不修改原文 |

请求体字段均可缺省；缺省按空值检查。长度上限：`title` 100、`docType` 20、`recipient` 200、`signature` 100、`date` 40、`body` 50000。

```json
{
  "title": "关于开展安全生产检查的通知",
  "docType": "通知",
  "recipient": "各部门：",
  "signature": "XX办公室",
  "date": "2026-11-25",
  "body": "　　各部门要立即开展自查。\n　　一、检查重点"
}
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "passed": false,
    "issues": [
      {
        "field": "date",
        "severity": "ERROR",
        "message": "日期格式不合法或日期不存在",
        "location": "date",
        "suggestion": "使用 YYYY-MM-DD 或 YYYY年M月D日"
      }
    ]
  },
  "traceId": "a1b2c3d4"
}
```

`passed` 仅在不存在 `ERROR` 时为 `true`；`WARN` 不阻止输出。完整规则见 [公文格式与用语校验技能](skills/official-doc-format.md)；超长/JSON 错误返回 HTTP 400 + `4000`。

### 5.4 规范用语检查

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/terms/check` |
| 用途 | 返回错别字/规范用语命中，不替换原文 |

```json
{
  "text": "同志们不能做为旁观者。"
}
```

`text` 可缺省，最长 50000 字。响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "passed": false,
    "count": 1,
    "hits": [
      {
        "term": "做为",
        "suggestion": "作为",
        "category": "错别字",
        "position": 6,
        "line": 1,
        "column": 7,
        "context": "同志们不能做为旁观者。"
      }
    ]
  },
  "traceId": "a1b2c3d4"
}
```

`position` 从 0 开始；`line`/`column` 从 1 开始；`context` 为命中词前后各最多 12 字。词表与规则见 [公文格式与用语校验技能](skills/official-doc-format.md)。

### 5.5 公文 Workflow 运行、确认与导出

#### `POST /api/workflow/run/stream`

| 项目 | 内容 |
|---|---|
| 方法 | `POST` |
| 路径 | `/api/workflow/run/stream` |
| 响应类型 | `text/event-stream` |
| 前置 | Dify 已启动并发布 `s8-v1.0`；`DIFY_WORKFLOW_API_KEY` 已配置 |

请求体：

```json
{
  "input": "起草一份安全生产检查通知",
  "draft": "",
  "documentIds": []
}
```

| 字段 | 类型 | 必填 | 约束/说明 |
|---|---|---|---|
| `input` | string | 是 | 1–2000 字任务描述 |
| `draft` | string | 否 | ≤20000 字；为空时执行知识检索与生成 |
| `documentIds` | string[] | 否 | ≤20 项；当前仅保留契约，不参与 Dify 入参 |

SSE 事件：

```text
event: node
data: {"nodeId":"...","name":"格式校验","type":"http-request","status":"started"}

event: message
data: {"delta":"草稿如下：\n..."}

event: done
data: {"runId":"...","status":"WAITING_CONFIRM","traceId":"a1b2c3d4"}
```

- `node*`：节点开始/结束快照；字段按可用性返回；
- `message*`：最终文本一次性或增量输出，由 Dify 决定；
- `done.status`：暂停时为 `WAITING_CONFIRM`；未暂停时为 Dify 的 `succeeded` 或 `partial-succeeded`；
- 工具失败由 Dify fail-branch 兜底，状态可为 `partial-succeeded`，仍返回 `message` 与 `done`；
- 执行失败发送 `event: error`（`code=5004`），不发送 `done`；
- 暂停时后端保存 `form_token`，但**不向前端或接口返回**；前端只能用 `runId` 查询和确认。

#### `GET /api/workflow/{runId}`

返回运行状态、草稿、工具结果、节点快照、确认结果和状态历史：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "runId": "...",
    "status": "WAITING_CONFIRM",
    "traceId": "a1b2c3d4",
    "document": {
      "title": "起草一份安全生产检查通知",
      "docType": "通知",
      "recipient": "各部门：",
      "signature": "AI 公文助手",
      "date": "2026-11-26"
    },
    "draft": "草稿全文",
    "answer": "模型/流程输出全文",
    "toolResults": {
      "format": {"passed": true, "issue_text": ""},
      "terms": {"has_hits": false, "hit_text": ""}
    },
    "nodes": [
      {"nodeId": "format_parse", "name": "格式校验", "type": "http-request", "status": "succeeded"}
    ],
    "errorCode": null,
    "errorMessage": null,
    "confirmation": null,
    "history": [
      {"status": "WAITING_CONFIRM", "action": null, "traceId": "a1b2c3d4", "at": "2026-11-26T06:00:00Z"}
    ],
    "createdAt": "2026-11-26T06:00:00Z",
    "updatedAt": "2026-11-26T06:00:00Z"
  },
  "traceId": "a1b2c3d4"
}
```

- `status`：`RUNNING`、`WAITING_CONFIRM`、`SUCCEEDED`、`FAILED`、`REJECTED`；
- 记录不存在、已失效或确认已过期：HTTP 200 + `code=5005`；
- 响应不包含 `form_token`；
- 运行状态保存在后端内存，重启清空。

#### `POST /api/workflow/{runId}/confirm`

```json
{
  "action": "modify",
  "content": "修改后的正文"
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `action` | string | 是 | `approve`、`modify`、`reject` |
| `content` | string | `modify` 时必填 | 非空且 ≤50000 字；`approve`/`reject` 可省略 |

- 成功：HTTP 200 + `code=0`，`data` 为更新后的 `WorkflowRunView`；`approve`/`modify` → `SUCCEEDED`，`reject` → `REJECTED`；
- 非法动作、重复确认、已过期、记录不存在：HTTP 200 + `code=5005`；
- 动作为空字符串等 Bean Validation 失败：HTTP 400 + `code=4000`；
- 确认成功后后端调用 Dify 服务 API 提交表单；Dify 异步恢复，后端不消费恢复流；
- `reject` 为终态，不自动回退到生成节点；需要重试时重新发起任务。

#### `GET /api/workflow/{runId}/export?format=md|docx`

- 仅 `SUCCEEDED` 可导出；未确认、已驳回、失败、过期、记录不存在或格式不支持：HTTP 200 + `code=5006`；
- `format=md`（默认）返回 `text/markdown; charset=UTF-8`；`format=docx` 返回 Word MIME；
- 响应头包含 UTF-8 `Content-Disposition: attachment` 与 `Cache-Control: no-store`；
- Markdown/DOCX 包含标题、主送机关、正文、落款、日期、格式/术语校验摘要和确认结果；当前不包含知识检索引用列表（S9 已知差异，见 README“已知限制”）。

```bash
curl -OJ "http://localhost:8080/api/workflow/$RUN_ID/export?format=md"
curl -OJ "http://localhost:8080/api/workflow/$RUN_ID/export?format=docx"
```

#### 状态机

```text
RUNNING
  ├─ Dify 暂停 → WAITING_CONFIRM
  │                 ├─ approve/modify → SUCCEEDED → 允许导出
  │                 └─ reject → REJECTED → 禁止导出
  ├─ 正常完成 → SUCCEEDED（无人工节点时）
  └─ 失败/过期 → FAILED
```

### 5.6 可选调试接口（阶段一未实现）

> 按 `02-阶段一全栈链路执行计划.md`，`POST /api/chat` 为可选接口，阶段一未实现。本文档仅保留契约占位；如后续启用，须先按计划变更流程确认并更新文档。

| 项目 | 内容 |
|---|---|
| 状态 | **未实现**（阶段一不提供） |
| 方法 | `POST` |
| 路径 | `/api/chat` |
| 用途 | 调试 Prompt 和模型参数，不参与前端流式展示 |
| 请求体 | 与 `/api/chat/stream` 一致 |
| 响应 | `data` 为完整回答字符串 |

---

## 六、测试用例清单

### 6.1 健康检查

- [ ] 后端启动后 `GET /api/health` 返回 `code=0`。
- [ ] 响应头包含 `X-Trace-Id`。

### 6.2 流式对话

- [ ] 正常问题：收到多条 `message`，最后收到 `done`。
- [ ] 长回答：内容不截断，必须收到终止事件。
- [ ] `formal` 与 `concise` 输出风格差异明显。
- [ ] 空 `message` 返回 HTTP 400 和 `code=4000`。
- [ ] 非法 `style` 返回 HTTP 400 和 `code=4000`。
- [ ] 上游模型异常时收到 `error`，且不暴露 Key 和堆栈。
- [ ] 上游 `finish_reason=length` 时收到 `error`，不发送 `done`。
- [ ] 流式响应包含 `Cache-Control: no-cache, no-transform` 与 `X-Accel-Buffering: no`。
- [ ] 用户中断请求后，后端停止读取上游并释放连接。
- [ ] 连续 5 次测试记录首字耗时，最大值 ≤3 秒。

### 6.3 结构化抽取

- [ ] 正常公文返回五个固定字段。
- [ ] 字段缺失时返回 `null`，字段名仍存在。
- [ ] 模型输出带 Markdown 围栏时可正常去除并解析。
- [ ] 日期非法时置空或按约定处理，并在日志中记录。
- [ ] 解析失败时重试一次。
- [ ] 重试仍失败返回 `code=5003`，不出现未处理 500。
- [ ] 模型调用失败返回 `code=5001`。

### 6.4 阶段二

- [ ] 知识库上传合法文件返回 `INDEXING`，轮询后变 `READY` 并写 `chunkCount`；
- [ ] 非法扩展名、空文件、超过 20MB 返回 HTTP 400 + `4000`；
- [ ] RAG 正常回答按 `message* → citations → done` 结束；拒答时引用为空；
- [ ] RAG 非法 `datasetId` 返回 HTTP 400 + `4000`；无引用非拒答为 SSE `5002`，不发送 `done`；
- [ ] 格式/用语检查不修改原文，超长返回 400 + `4000`；
- [ ] Workflow 空草稿真实暂停，`done.status=WAITING_CONFIRM`，可查询到非空草稿；
- [ ] 非法 action、重复确认、过期/未知 run 返回 `5005`，无未处理 500；
- [ ] `modify` 后 `SUCCEEDED`；导出 Markdown/DOCX 包含修改内容和中文；
- [ ] 未确认、已驳回导出返回 `5006`；不存在的 run 导出同样返回 `5006`；
- [ ] 响应与日志不出现 `form_token`、Dify Key、真实模型 Key。

---

## 七、未纳入范围

以下能力不在阶段一/阶段二当前交付内：

- 登录、鉴权、用户信息、多用户隔离；
- 会话历史与消息持久化；
- 私有 vLLM 切换；
- 独立 OpenAPI/springdoc 文档。
- Workflow 运行时状态落库、多实例共享和 Dify 恢复流回读。

## 八、变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-11 | 归纳阶段一健康检查、流式对话、结构化抽取接口契约 |
| v1.2 | 2026-11-12 | 增加输出截断错误、SSE 反缓冲响应头和抽取温度配置说明 |
| v2.0 | 2026-11-26 | 补充知识库、RAG SSE、格式/术语检查和 Workflow 状态机/确认/导出契约；扩展 5002–5006；明确以 Markdown/SSE 示例为契约，不引入 springdoc |
