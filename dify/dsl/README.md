# Dify Workflow DSL（S7）

## 版本记录

| 项目 | 值 |
| --- | --- |
| 应用名 | 公文起草与校验-Workflow |
| 应用 ID | `39259284-937e-41de-b975-a093f8da7a14` |
| 草稿工作流 ID | `1c83f0f4-7ce1-46f3-91ad-6a33221179ec` |
| 验收发布版本 ID | `b6eae619-b334-4fe3-af11-b15863e34f60` |
| 验收发布标记 | `s7-v1.0-final` |
| DSL 文件 | `official-doc-workflow-s7.yml` |
| 导出方式 | Dify 控制台导出 API，`format=yaml`、`include_secret=false` |

发布历史中另有 `s7-v1.0`（首个正式版本）和 `s7-tool-timeout-temp`（仅用于验证工具超时兜底，工具地址指向不可达端口）。

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
  → 最终输出合并 → 结束（输出 answer）
```

- 工具调用：后端 `POST /api/format/check`、`POST /api/terms/check`。
- HTTP 节点启用 `fail-branch`，连接失败、超时或非 2xx 不会中断工作流；兜底输出保留草稿。
- 工具失败时 Dify 工作流状态为 `partial-succeeded`；后端代理将其视为可接受结果并转发兜底文本。
- 后端调用 Dify 携带 `X-Trace-Id`，`user` 为 `ai-chat-workflow-<traceId>`。

## 导入前必须修改

1. 把 `REPLACE_WITH_DATASET_B_ID` 替换为本环境的 B 知识库 ID（知识检索节点）。
2. 如后端不在 Dify 宿主机 8080 端口，修改两个 HTTP 节点 URL：
   - `http://host.docker.internal:8080/api/format/check`
   - `http://host.docker.internal:8080/api/terms/check`
3. 安装 DSL 中声明的插件：DeepSeek、SiliconFlow；并确认模型 `deepseek-v4-flash`、`Pro/BAAI/bge-m3` 可用。
4. 导入后重新发布，在 Dify 控制台创建服务 API Key，写入后端被 gitignore 的 `.env`：
   `DIFY_WORKFLOW_API_KEY=<新 Key>`。

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
