# 技能：公文格式与用语校验

## 1. 何时使用

- 用户要求起草、润色或审核通知、通报、报告、请示、批复、意见、决定、公告、通告、函、纪要、方案等公文。
- Dify Workflow 生成草稿后、输出给用户前，需要做一次确定性格式检查和规范用语检查。
- 用户只要求“检查格式/错别字/规范用语”，不需要改写原文时。

本技能只报告问题，不修改原文。修改必须由用户确认后另行执行。

## 2. 输入

### 2.1 格式检查 `POST /api/format/check`

请求头：`Content-Type: application/json`。响应头包含 `X-Trace-Id`。

```json
{
  "title": "关于开展安全生产检查的通知",
  "docType": "通知",
  "recipient": "各部门：",
  "signature": "XX办公室",
  "date": "2026-11-25",
  "body": "　　各部门要立即开展自查。\n　　一、检查重点\n　　　　（一）消防设施"
}
```

字段均可缺省，缺省按空值检查并返回问题。长度上限：title 100、docType 20、recipient 200、signature 100、date 40、body 50000。

### 2.2 用语检查 `POST /api/terms/check`

```json
{ "text": "同志们不能做为旁观者。" }
```

`text` 可缺省；上限 50000 字。检查全部出现位置，不做替换。

## 3. 输出

### 3.1 格式检查

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

- `passed`：不存在 `ERROR` 时为 `true`；只有 `WARN` 仍算通过。
- `severity`：`ERROR`（必须修复）或 `WARN`（建议修复）。
- `field`：`title`、`docType`、`recipient`、`signature`、`date`、`body`。
- `location`：字段名；正文问题为 `body:第 N 行`。
- `issues` 不修改原文，只描述问题与建议。

### 3.2 用语检查

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

- `passed`：未命中时为 `true`。
- `position`：从 0 开始的字符偏移；`line`、`column`：从 1 开始。
- `context`：命中词前后各最多 12 字，跨行以空格代替，截断处加 `…`。
- `category`：`错别字`、`规范用语`、`自定义`。

## 4. 格式规则

### 4.1 标题 `title`

- 不能为空（ERROR）。
- 不能包含换行（ERROR）。
- 超过 50 字给出 WARN。
- 指定 `docType` 时，标题末尾应使用该文种（WARN）。

### 4.2 文种 `docType`

- 不能为空（ERROR）。
- 常用文种：通知、通报、报告、请示、批复、意见、决定、公告、通告、函、纪要、方案。
- 其他文种给出 WARN，不直接判定失败。

### 4.3 主送机关 `recipient`

- 不能为空（ERROR）。
- 不能包含换行（ERROR）。
- 建议以全角或半角冒号结尾（WARN）。

### 4.4 落款 `signature`

- 不能为空（ERROR）。
- 超过 50 字给出 WARN。

### 4.5 成文日期 `date`

- 不能为空（ERROR）。
- 支持 `YYYY-MM-DD` 与 `YYYY年M月D日`。
- 格式非法或日期不存在（如 `2026-02-30`）为 ERROR。

### 4.6 正文 `body`

- 不能为空（ERROR）。
- 空行跳过；普通段落应使用两个全角空格或四个半角空格首行缩进，否则 WARN。
- 标题行不要求缩进。标题层级：
  - 一级：`一、`、`二、`……
  - 二级：`（一）`、`（二）`……
  - 三级：`1.`、`2.` 或 `1、`、`2、`……
- 同一层级序号必须连续；二级不得早于一级，三级不得早于二级。
- 正文问题最多返回 50 条，超过时追加“仅显示前 50 条”的 WARN。

## 5. 用语规则

内置词表（精确包含匹配，区分全角/半角）：

| 命中 | 建议 | 类别 |
| --- | --- | --- |
| 做为 | 作为 | 错别字 |
| 按排 | 安排 | 错别字 |
| 布署 | 部署 | 错别字 |
| 查觉 | 察觉 | 错别字 |
| 竟见 | 意见 | 错别字 |
| 即然 | 既然 | 错别字 |
| 截止目前 | 截至目前 | 规范用语 |
| 截止到 | 截至 | 规范用语 |
| 一把手 | 主要负责人 | 规范用语 |
| 二把手 | 分管负责人 | 规范用语 |
| 敲定 | 确定 | 规范用语 |
| 搞定 | 完成 | 规范用语 |

可通过后端 `application.yml` 的 `app.terms.overrides` 增加或覆盖词条（键为命中词，值为建议词），自定义词条类别为 `自定义`。所有命中按 `position` 升序返回。

## 6. Dify Workflow 中的调用约定

已发布工作流：`公文起草与校验-Workflow`。

1. 开始节点接收 `task`、`draft`、`title`、`doc_type`、`recipient`、`signature`、`date`。
2. 若 `draft` 为空，先做知识检索并生成草稿；非空时跳过生成。
3. Code 节点把草稿字段组装为 JSON 字符串，HTTP Request 节点调用：
   - `POST http://host.docker.internal:8080/api/format/check`，`Content-Type: application/json`。
   - 格式通过后：`POST http://host.docker.internal:8080/api/terms/check`。
4. 格式不通过 → 输出“格式校验未通过 + 问题列表 + 原草稿”；不进入用语检查。
5. 用语命中 → 输出“术语检查发现需调整项 + 命中位置 + 原草稿”。
6. 全部通过 → 输出草稿。
7. HTTP 节点启用 `fail-branch`：连接失败、超时或非 2xx 时进入兜底节点，输出“工具暂时不可用 + 原草稿”，工作流状态为 `partial-succeeded`，不得向用户返回上游异常细节。
8. 后端代理 `/api/workflow/run/stream` 接受 `succeeded` 与 `partial-succeeded`；工具失败不会中断 SSE，仍发送 `message` 与 `done`。
9. 后端调用 Dify 时携带 `X-Trace-Id`，并以 `ai-chat-workflow-<traceId>` 作为 Dify `user`；日志只记录 traceId、状态和数量，不记录正文、问题明细或密钥。
10. Dify 的 HTTP 节点经 SSRF 代理访问本机后端，需在 Dify 部署中允许 `host.docker.internal`（本次验证通过 `SSRF_PROXY_ALLOW_PRIVATE_DOMAINS=host.docker.internal`）。

## 7. 错误处理

| 场景 | HTTP | 业务码 | 行为 |
| --- | --- | --- | --- |
| JSON 无法解析、超长字段 | 400 | 4000 | 返回参数错误，不执行检查 |
| 空对象/空文本 | 200 | 0 | 返回完整问题列表或“通过” |
| 后端内部异常 | 500 | 5000 | 返回通用错误与 traceId，不返回堆栈 |
| Workflow 执行失败 | SSE `error` | 5004 | 发送 error 后结束，不发送 done |
| 工具超时/非 2xx | 工作流内 | — | 进入 fail-branch 兜底，不发 error |

调用方不应根据 `WARN` 阻止输出；需要严格把关时，以 `passed=false` 或 `count>0` 决定是否进入人工确认。

## 8. 测试覆盖

- 格式：合法样例、字段全空、非法日期、序号不连续、缺首行缩进、中文日期、非常用文种。
- 用语：空文本、多次命中、行列位置、自定义覆盖、正常文本。
- 接口：不修改原文、空对象、超长 50001 字、traceId 响应头。
- Workflow：格式不通过、术语命中、格式工具超时、术语工具超时、正常生成。
