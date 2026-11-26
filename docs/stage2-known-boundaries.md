# 阶段二已知边界与后续处理

> 记录日期：2026-11-26
> 范围：W3–W4 / S10 本地收口
> 关联：[阶段二汇报单](report-stage2.md)、[技术验证记录](stage2-tech-validation.md)、[评测报告](stage2-eval-report.md)、[PR 与评审记录](pr-log.md)、[Tag 说明](release-tag.md)
>
> 结论：阶段二 P0 功能已完成，W4 硬指标和 S10 临时快照彩排已通过。本文件记录仍未完成或未覆盖的边界；它不是通过证明，后续处理前不得改写成“已完成”。

## 1. 功能与质量边界

| 编号 | 边界 | 当前事实与证据 | 影响 | 后续处理 |
|---|---|---|---|---|
| B-01 | BC-02 低相关引用 | B 库 q01/q02 各 2 条、q10 1 条低相关引用，分数约 0.43–0.51；W4 命中、拒答、引用完整仍达标。证据：`docs/stage2-eval-report.md`、`logs/regression/20261126-w4/live-eval/`（未跟踪） | 引用精度和可读性不足，不影响当前硬指标 | 固定题集、模型和 Embedding，只改相似度阈值（如 0.55）或 TopK 一个变量，复测命中/拒答/引用精度；未改善则保留现状并记录 |
| B-02 | BC-03 A 库切分过细 | A 库存在 5 字碎段；B 库无碎段。证据：`docs/stage2-eval-report.md`、`docs/stage2-tech-validation.md` S6 记录 | A 库引用可能从半句开始，影响可读性 | 合并短段后重导 A 库，复测 A/B；未重导前不把 A 库作为默认演示库 |
| B-03 | 17 条资料来源 URL 未补录 | 3 条已核实为 HTTP 200；17 条保留“URL 待补录”。证据：`docs/dataset-manifest.md` | 数据溯源信息不完整，不影响问答链路 | 用户按文件名补充 URL，使用 `scripts/dify/import_datasets.py --source-map` 重新生成清单；不得编造链接 |
| B-04 | Workflow 页面不渲染引用卡片 | Workflow 视图 API 与 Markdown/DOCX 导出包含引用；`WorkflowView.vue` 不画引用卡片。证据：FR-13 验证与 W4 UI 彩排 | 页面内不能直接点引用，需在导出文件中核验 | 若业务要求页面展示，再补 UI 引用卡片；当前验收口径为视图 API + 导出 |
| B-05 | 状态在后端内存 | 文档元数据、Workflow 运行状态在内存，重启清空；Workflow 暂停后重启会丢失；无后台过期清理。证据：`docs/api.md`、`docs/stage2-tech-validation.md` | 生产可用性不足，适合本地单用户演示 | W5 使用 MySQL/MyBatis-Plus 持久化文档、会话、消息、模板和运行状态；加密/短期存储 `form_token` |
| B-06 | 未接入独立 Rerank | 当前检索使用 Dify 默认 TopK/相似度，没有独立 Rerank 模型 | BC-02 的低相关过滤能力有限 | W6 接入私有 Embedding/Rerank 后，用同一固定题集对比命中率和引用精度 |
| B-07 | P1 可选项未全部实现 | 删除文档等 P1 功能未做；计划允许时间不足时砍掉，不影响 P0 上传与列表 | 后台管理能力有限 | 后续按需求补删除、拖拽上传、状态时间线等；不要与 P0 验收混写 |
| B-08 | 真实 Embedding 限额未耗尽 | 只做了本地模拟 429，验证 RAG 返回 `5002`；未真实耗尽供应商额度。证据：`logs/regression/20261126-s10/upstream-429.sse`（未跟踪） | 真实限额行为未观测，当前只能证明错误映射 | 在可牺牲额度的隔离环境做一次受控测试；在此之前材料中必须标注“模拟 429”，不得写成真实额度实测 |
| B-09 | 干净环境覆盖不完整 | S10 临时快照只重跑 B 库 RAG 和 Workflow E2E；上传/列表、Workflow UI 未从快照重跑。证据：`docs/stage2-tech-validation.md` 第 19 节 | 不能把临时快照等同于 tag 无覆盖验收 | 从 `stage2-v1.0` 做无覆盖克隆，补跑构建、启动、上传临时库、Workflow UI、导出与异常路径 |
| B-10 | 新演示顺序尚未人工走台 | 脚本已改为“上传 → 引用问答 → 拒答 → Workflow → 校验 → 人工确认 → 导出”；上传临时库和单列拒答尚未完整走台。证据：`docs/demo-script-stage2.md`、`docs/report-stage2.md` | 不能声称现场新顺序已通过 | 11/27 汇报前按 8 分钟完整走台，补上传临时库、q11 拒答、实际下载和驳回兜底证据 |

## 2. 交付与版本边界

| 编号 | 边界 | 当前事实与证据 | 影响 | 后续处理 |
|---|---|---|---|---|
| D-01 | tag 与远程发布 | 已创建并推送 annotated tag `stage2-v1.0`；远程分支含阶段二提交；未创建 GitHub Release | 可通过远程 tag 获取阶段二版本，Release 说明仍缺失 | 补 GitHub Release 说明；tag 无覆盖复跑与演示材料仍按 D-03/D-04 处理 |
| D-02 | 阶段二 PR 与同伴评审 | 阶段二已在 `main` 纵向提交并推送，但无 GitHub PR、无同伴评审；`docs/pr-log.md` 已记录偏差 | 缺少外部评审证据 | 按增量创建 PR，邀请同伴评审；若截止前无法完成，保留偏差说明，不伪造 |
| D-03 | tag 无覆盖复跑 | 当前只有 S10 临时快照彩排；从 `stage2-v1.0` 的无覆盖完整复跑尚未执行 | 正式 S10 “tag 指向可复现提交”的证据仍不完整 | 从 tag 克隆到干净目录，按 README 构建/启动，复跑核心链路、15 题、Workflow E2E/UI 和异常路径，留痕 |
| D-04 | 演示与汇报材料 | 架构图、≤3 分钟 Workflow 录屏尚未生成；一页汇报单和 8 分钟脚本已生成；若计划要求 PPT/大纲，尚未单独制作 | 材料不齐，可能影响汇报 | 补架构图、Workflow 录屏和 PPT/大纲；按计划截止时间提交，发送动作由用户完成 |
| D-05 | 最终版本说明 | `docs/release-tag.md` 已记录本地 tag 与远程推送状态，但 tag 指向的提交内无法写入其后的文档状态提交 hash | 版本说明与 Release 链接仍需人工核对 | 补充 tag 对象/commit 校验结果与 Release 链接；不伪造远程状态 |

## 3. 后续阶段，不属于阶段二 P0

- W5：MySQL/MyBatis-Plus 持久化、JWT 登录鉴权、历史列表/详情/模板管理页面。
- W6：私有 vLLM、私有 Embedding/Rerank、LangGraph 示例。
- 这些是培养计划后续周次，不作为阶段二完成条件；不得用它们反过来扩大当前缺陷清单。

## 4. 处理原则

1. 硬指标（15 题命中/拒答/引用完整、100% 引用、Workflow 2 工具/1 分支/1 人工确认/1 兜底、Markdown/DOCX 导出）不因本文件降级。
2. 每条边界处理时保留原始证据；失败也记录，不删题、不改结果、不伪造通过。
3. 涉及真实 Key、真实资料、评测导出和 `.env` 的内容不得提交；不执行 `docker compose down -v`。
4. 状态变化后同步更新本文件、`docs/report-stage2.md`、`docs/release-tag.md` 与 `docs/pr-log.md`。
