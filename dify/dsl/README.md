# Dify Workflow DSL（S7/S8/S9）

> S9 最终交付口径：以 `official-doc-workflow-s8.yml` 为最终 DSL，`s8-v1.0` 为验收发布版本。S9 不复制 S9 DSL、不修改工作流，也不新增发布；导入和占位符替换仍按本文执行。

## 版本记录

| 项目 | 值 |
| --- | --- |
| 应用名 | 公文起草与校验-Workflow |
| 应用 ID | `39259284-937e-41de-b975-a093f8da7a14` |
| 草稿工作流 ID | `1c83f0f4-7ce1-46f3-91ad-6a33221179ec` |
| 当前验收发布版本 ID | `83f85623-25f2-4e46-8f77-22d7648ee60d` |
| 当前验收发布标记 | `s8-v1.0` |
| S8 DSL 文件 | `official-doc-workflow-s8.yml` |
| S7 基线 DSL 文件 | `official-doc-workflow-s7.yml` |
| 导出方式 | Dify 控制台导出 API，`format=yaml`、`include_secret=false` |

发布历史中包含 `s7-v1.0`、`s7-tool-timeout-temp`（仅用于验证工具超时兜底）、`s7-v1.0-final`（S7 基线）和 `s8-v1.0`（人工确认）。S8 DSL 与 S7 的差异仅在 `final_agg → human_confirm → end` 与结束节点输出。

## 工作流结构

```text
开始
  → 是否已有草稿
      ├─ 是 → 草稿合并
      └─ 否 → 知识检索 → 草稿生成 → 草稿合并
  → 构造格式校验参数 → 格式校验工具 ──失败──→ 格式工具兜底
  → 格式是否通过
      ├─ 否 → 格式问题输出
      └─ 是 → 构造术语校验参数 → 术语校验工具 ──失败──→ 术语工具兜底
                 → 术语是否命中
                     ├─ 是 → 术语问题输出
                     └─ 否 → 通过输出
  → 最终输出合并 → 人工确认 → 结束（输出确认后的 content）
```

- 工具调用：后端 `POST /api/format/check`、`POST /api/terms/check`。
- HTTP 节点启用 `fail-branch`，连接失败、超时或非 2xx 不会中断工作流；兜底输出保留草稿。
- 工具失败时 Dify 工作流状态为 `partial-succeeded`；后端代理将其视为可接受结果并转发兜底文本。
- 后端调用 Dify 携带 `X-Trace-Id`，`user` 为 `ai-chat-workflow-<traceId>`。

### 人工确认节点

- 节点类型 `human-input`，节点 ID `human_confirm`，超时 1 天。
- `form_content` 为 `{{#final_agg.output#}}`，Dify 暂停事件中的 `form_content` 同时作为后端缺失流式文本时的草稿兜底。
- 一个 `paragraph` 输入 `content`，默认值取自 `final_agg.output`；后端提交确认时总是携带该输入。
- 动作 `approve`（通过）、`modify`（修改后通过）、`reject`（驳回），三个动作均连到 `end`。
- 交付方式仅 `webapp`；后端使用服务 API `POST /v1/form/human_input/{form_token}` 提交，表单 token 一次性，不返回前端。
- 结束节点输出 `human_confirm.content`，修改后的正文会进入 Dify 最终输出；后端导出以确认时保存的内容为准。
- Dify 暂停后初始 `/workflows/run` 流结束；后端返回 `WAITING_CONFIRM`，确认提交后 Dify 异步恢复 `human_confirm → end`。

## 导入前必须修改

1. 把 `REPLACE_WITH_DATASET_B_ID` 替换为本环境的 B 知识库 ID（知识检索节点）。
2. 如后端不在 Dify 宿主机 8080 端口，修改两个 HTTP 节点 URL：
   - `http://host.docker.internal:8080/api/format/check`
   - `http://host.docker.internal:8080/api/terms/check`
3. 安装 DSL 中声明的插件：DeepSeek、SiliconFlow；并确认模型 `deepseek-v4-flash`、`Pro/BAAI/bge-m3` 可用。
4. 导入后重新发布，在 Dify 控制台创建服务 API Key，写入后端被 gitignore 的 `.env`：
   `DIFY_WORKFLOW_API_KEY=<新 Key>`。
5. 人工确认节点需要应用为 WebApp 可交付方式，并确保服务 API 可访问；后端提交表单不依赖前端 cookie。

## Dify 侧网络要求

Dify HTTP Request 节点经 SSRF 代理访问本机，需允许 `host.docker.internal`。在 Dify 部署中配置：

```env
SSRF_PROXY_ALLOW_PRIVATE_DOMAINS=host.docker.internal
```

然后重建/重启 `ssrf_proxy`。只开放该域名，避免放开全部私网地址。

## 安全说明

- DSL 导出时未包含密钥；`dependencies` 中的插件市场标识不是密钥。
- 数据集 ID 已替换为占位符，导入前需按环境替换。
- 后端 `.env` 中的 `DIFY_WORKFLOW_API_KEY` 不得提交、打印或写入文档。
- `form_token` 只保存在后端内存，不写入日志、不返回前端；表单一次性，提交失败或过期需重新发起任务。
