# 阶段一/阶段二交付 Tag 说明

> 2026-11-19：仓库由 `ai-chat-stage1` 更名为 `ai-chat`。阶段一 tag 内的文件仍保留旧仓库名，属于历史快照；GitHub 旧链接自动重定向。
>
> 阶段一最终 tag 为 `stage1-v1.4`；阶段二本地交付 tag 为 `stage2-v1.0`（2026-11-26 创建，远程推送/Release 待授权），状态见文末“阶段二 tag（本地创建）”。

## 当前最终 tag

| 项目 | 内容 |
|---|---|
| Tag 名称 | `stage1-v1.4` |
| Tag 类型 | annotated tag |
| Tag 对象 | `d55024cf8096842f3893dadd31fb84e31fbdf3d6` |
| 指向 commit | `b73d91bf0f8178b85ee1a1baa48cee8ac9bed7a6` |
| 提交信息 | `docs(release): 补 stage1-v1.4 交叉评审收口` |
| 打 tag 日期 | 2026-11-12 |
| GitHub 链接 | https://github.com/aily-rx/ai-chat/tree/stage1-v1.4 |

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
git clone --branch stage1-v1.4 --depth 1 https://github.com/aily-rx/ai-chat.git
cd ai-chat
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

## 阶段二 tag（本地创建）

| 项目 | 内容 |
|---|---|
| Tag 名称 | `stage2-v1.0` |
| Tag 类型 | annotated tag |
| 创建日期 | 2026-11-26 |
| 当前状态 | **已在本地创建**；`origin` 未推送，GitHub Release 未创建 |
| 代码范围 | FR-10 驳回回退、FR-13 导出引用、W4/S10 文档与脚本、已知边界清单 |
| 当前代码 | 本地 `main` 最终提交；`origin/main` 仍为 `e057d0d` |
| 前置条件 | 已明确授权提交与打本地 tag；S10 临时快照彩排已通过 |
| 后续校验 | 从 tag 做无覆盖克隆，补跑构建/启动/上传临时库/RAG/Workflow UI/异常路径；完成后再推送与 Release |
| 说明 | 本地 tag 不等同于远程交付；不伪造远程 tag、Release 或 PR；边界见 `docs/stage2-known-boundaries.md` |

本地校验命令（不访问远程）：

```bash
git tag -n99 stage2-v1.0
git show -s --format="%H %s" stage2-v1.0
git describe --tags --exact-match
```

远程发布前需另获 push/Release 授权；不得用 `stage1-v1.4` 替代阶段二交付，也不得把临时快照彩排写成 tag 无覆盖通过。
