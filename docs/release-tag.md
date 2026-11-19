# 阶段一交付 Tag 说明

## 当前最终 tag

| 项目 | 内容 |
|---|---|
| Tag 名称 | `stage1-v1.4` |
| Tag 类型 | annotated tag |
| Tag 对象 | `d55024cf8096842f3893dadd31fb84e31fbdf3d6` |
| 指向 commit | `b73d91bf0f8178b85ee1a1baa48cee8ac9bed7a6` |
| 提交信息 | `docs(release): 补 stage1-v1.4 交叉评审收口` |
| 打 tag 日期 | 2026-11-12 |
| GitHub 链接 | https://github.com/aily-rx/ai-chat-stage1/tree/stage1-v1.4 |

Tag 说明：

> 阶段一最终交付 v1.4：纳入协作人 PR #4 交叉评审记录。

## 包含内容

- 阶段一前后端完整代码：Vue 3 前端、Spring Boot 后端；
- `README.md`：启动步骤、`.env` 样例、验收步骤；
- `docs/report-stage1.md`：阶段一汇报单；
- `docs/demo-script.md`：现场演示脚本；
- `docs/pr-log.md`：PR 记录及协作人 PR #4 交叉评审记录；
- Dify 独立交付物说明及相关验收记录；
- 34 个后端测试通过、前端 `npm run build` 通过。

## 获取与校验

干净目录获取最终 tag：

```bash
git clone --branch stage1-v1.4 --depth 1 https://github.com/aily-rx/ai-chat-stage1.git
cd ai-chat-stage1
```

已有仓库切换到最终 tag：

```bash
git fetch --tags
git checkout stage1-v1.4
git show -s --format="%H %s" stage1-v1.4
```

预期 commit：

```text
b73d91bf0f8178b85ee1a1baa48cee8ac9bed7a6
```

## Tag 演进

| Tag | Commit | 说明 |
|---|---|---|
| `stage1-v1` | `1c96bb3` | 初始里程碑 |
| `stage1-v1.1` | `fb832dd` | 阶段一最终收口 |
| `stage1-v1.2` | `235ff63` | README 启动步骤与 `.env` 样例完善 |
| `stage1-v1.3` | `6471e4e` | PR #1 协作评审意见修复后的代码快照 |
| `stage1-v1.4` | `b73d91b` | 纳入协作人 PR #4 交叉评审记录的最终交付版本 |

## 注意

- 最终交付以 `stage1-v1.4` 为准；
- `stage1-v1.3` 保留为代码修复后的历史快照，不包含后续交叉评审文档；
- 不要用 `stage1-v1` 或 `stage1-v1.1` 替代最终交付版本。
