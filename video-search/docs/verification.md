# 验证记录与故障演示

日期：2026-11-22。完整中间件联调按用户要求跳过，不能宣称通过。

## 真实执行

| 项目 | 证据 |
| --- | --- |
| 七段样本、十二条查询 | output/m0/evaluation.json、review.html、历史失败报告 |
| 1s/2s 描述采样 | output/m0、m0-2s，核验均为 500ms |
| 真实 MySQL/OSS、分片、重复完成、Range | output/m1-api-smoke.json |
| 页面上传三段视频、跨视频检索 | output/local-acceptance.json、desktop.png、mobile.png |
| Agent 核验失败、扩展上下文、无答案结束 | bf0458ee-1b33-4f65-aa10-7bd556c3e49e 的原始轨迹 |
| 六秒音视频 ASR/视觉/发布 | output/exercise-6db6a761-c290-4368-9c78-e34c18b25266.json，约 28.36s |
| 删除后播放/缩略图拒绝、旧结果和收藏撤销 | local-acceptance.json 的 deleteRevocation |
| SSE 断开重连 | 同文件 sseReconnect |
| 4 并发、40 次库列表读取 | 同文件 readLoad，约 299 req/s、p95 23.4ms；不是持续负载或模型吞吐 |
| 390px 页面宽度 | document.scrollWidth=390，无横向溢出 |
| 浏览器真实分片失败恢复 | 36.52 MiB、8 片；请求顺序 0,1(失败),1,2,3,4,5,6,7，首片未重传 |
| 强制结束 JVM 后自动恢复 | output/crash-recovery.json；VISUAL/RUNNING 时终止，重启后 attempts 1→2，最终发布 |
| 新版边界细化 | exercise-504e8e5c-70b2-46a2-aa3b-5ef085f5b53f.json：真实返回 2286–3619ms，单例结果不替代 M0 指标 |
| 完整 Compose | config --quiet 通过；未启动整套服务 |

M0 Recall/Precision 为正例查询宏平均，无答案单独统计；同视频且 IoU≥0.5，一对一事件匹配。全部是开发集。最终保存的缓存重跑耗时不是实时模型延迟。

原 M1 视频已在撤权验收中删除。演示使用“饮料素材演示”库三段视频，或“ASR 流水线验收”库。

## 确定性测试

server/src/test 覆盖认证、CSRF、跨用户权限、名额、重复片、Range、实际 FFmpeg 时间戳、费用并发/缓存/未知费用、供应商错误 JSON、RRF、合并、边界越界、Claim、租约、旧 Worker、取消和重试。

SearchEngineTest 使用供应商替身验证：初次失败后扩展成功、伪造候选拒绝、补查不改变原目标、无答案有依据、恢复不重置期限。这不能当成真实模型质量或 MQ 故障结论。

## 五个演示

1. 续传：已在真实浏览器对第二片注入一次断网，重选相同文件后仅补缺片，首片只发送一次。
2. 重复消息：PersistentTasksTest 的 duplicateDelivery 用例验证 Claim 幂等；没有注入真实 RocketMQ 重复消息。
3. 中断恢复：已在真实 MySQL/轻量 Worker 执行强制终止与重启，任务自动恢复。oldWorkerCannotCommitAfterRecovery、expiredLeaseCannotBeRevivedOrScheduleADeferredStage 另外验证旧执行无法提交。脚本 crash-recovery.ps1 只允许终止核实为本项目 JAR 的进程。
4. 删除撤权：真实内容/缩略图 404、旧结果过滤、收藏清除，后续清理任务处理对象与索引。
5. Agent：真实无答案案例展示 Planner→expandContext→Verifier→finish；补查成功使用稳定替身测试，不能冒充真实供应商找回漏检。

## 未验收

- MQ/Redis/Qdrant 联合运行与故障（用户要求跳过）。
- Qdrant 发布失败注入。
- 大容量合成媒体流程、持续压力、五小时素材解析费用。
- 独立视频测试集、《饥荒》的提及/出现/实际交战区分。
- 独立保留集三路径对照；开发集静音 OCR-only/视觉/Agent 同集对照已完成。
- 派生帧/转码缓存按对象 GC；缓存目录不对外静态暴露。

## 复现

运行 scripts/build.ps1 构建与测试，scripts/start-local.ps1 -SkipBuild 启动。打开 http://127.0.0.1:8090/，账号在项目 .env。试搜“找出正在喝饮料的片段”“找出从瓶子往杯子里倒饮料的片段”“有人用牙刷刷牙”；最后一个选择 Agent。

run-lab.ps1 -Stage evaluate -SkipBuild 只重算，不付费。exercise.py --cloud 才运行小样本真实调用。账本在 .data/model-ledger.json，供应商账单另核。

本次中断实验遗留 0.50 元未结算预留，账本未将其释放为零费用。中断恢复辅助脚本需要重新登录新 API 会话，不能把 Session 失效当作任务丢失。


## 后续补充验收

- 40 项 Java 测试通过，见 output/test-summary.json；其中包含模型最大上下文预留与缓存费用审计。
- output/comparison-summary.json：同一开发集三路径，A=0、B=0.9375、C=0.9375 Recall@5；B/C Precision@5=1.0，无答案误报均为零。Agent 没有提高召回。
- A 只读取静音样本的 OCR，没有 ASR 信号。B/C 结果属于服务 v1，制品 SHA 在 comparison-version.json；后续自动邻接修复另外保存单例，不混入旧总体分数。
- B/C 起点误差中位数 100ms，最坏为 5000ms，不能只报告中位数。修复自动邻接读取后，39fea985-f318-4238-9783-b7d3fa8b90f0 中该动作起点从 10000ms 变成 5333ms（标注 5000ms），边界仍有不确定标记。
- B 总耗时约 548s，C 约 142s；C 复用了 B 预热后的缓存，不能据此宣称 Agent 更快或更省钱。
- output/expiry-before.jsonl、expiry-after.jsonl：九个真实 OSS 分片从有回执到清空；历史失败任务保留，改用精确键清理后的新任务成功。原视频未被当作过期分片删除。
- output/capacity-check.json：8 并发客户端/25 次初始化仅十个成功，其余十五个 409。仅声明 2GiB，实际没有上传大文件，不证明长视频吞吐。
- output/browser-final-check.json：一次多选两个文件都成为 READY；会话失效自动返回登录界面；重取 CSRF 后刻意错误密码得到账号密码错误，未卡在旧 CSRF。
- exercise-c8cd5699-4cde-4963-ac0d-446b168ffea1.json：真实 Agent 检索保存逐工具 modelUsage，视觉/向量缓存复用为零新增费，Planner 此次估算 0.007620 元。
- 最终 JAR 在 8090 端口提供的页面完成检索验收：任务 97a63fd9-8fa7-4176-b63a-63c933247c72 从七段素材返回三段饮用结果，没有混入倒液体和仅展示容器样本；长动作仍显示“边界未确认”。这是一次演示检查，不替代十二条查询的整体评测。
- output/final-desktop.png、final-mobile.png 保存最终打包页面；390px 视口的文档宽度为 390px，没有水平溢出。

## FRAMELOCK 界面改版

### 2026-11-23 指定使用流程修复

Flyway V6 为项目增加软删除标记。新增项目/收藏夹改名删除、收藏来源元数据；删除收藏夹回到未分类，非空项目或有未完成上传的项目拒绝删除。历史搜索返回受账号约束的原项目、原问题和模式，页面完整恢复上下文；列表显示问题、项目与时间。无索引视频时创建及重试都拒绝，不排入模型任务。

46 项后端测试通过，覆盖权限、CSRF、收藏保留、空/未处理项目、指定视频无可用索引、重试拒绝、历史上下文和删除后历史仍可读。实际浏览器复现并验证了原来的历史项目错位修复；延迟旧历史请求后切换项目，旧响应被忽略。另验证项目/收藏夹改名删除、源项目跳转、390px 布局和真实标准无答案后的 Agent 补查仍无答案。临时测试分组已移除，原有七段饮料视频和一条收藏保留。

证据见 output/workflow-fixes-check.json、history-list-fixed.png、history-context-fixed.png、project-settings-safe-delete.png、no-results-next-actions.png。此轮不重跑整套质量评测，不更新服务 v1 的整体 Recall 数字。

### 2026-11-23 收藏夹

Flyway V5 新增按账号隔离的收藏夹和收藏归属。支持创建、收藏时选择或内联创建、移动及未分类；同一片段仍只有一条收藏，移动不会改动视频和起止时间。已有未指定收藏夹的数据按未分类显示。

43 项后端测试通过，包含同名、输入校验、CSRF、跨账号隔离、重复收藏不重复、移动与删除视频后的权限检查。真实浏览器验证了创建“饮料片段”、内联创建“待剪辑”、指定收藏、移动到另一个夹和未分类、刷新保留及 390px 布局。未重新运行全套模型质量评测。证据见 output/favorite-folder-check.json。

随后仅检查使用逻辑，没有修改检查中发现的业务问题，待讨论清单见 user-flow-review.md。

### 2026-11-23 侧栏精简

按用户要求移除“已收起的项目”及恢复入口，同时移除“收起项目”按钮，避免没有恢复入口时继续隐藏项目。正常创建、切换与按项目查找仍保留；历史验收数据没有删除。真实浏览器确认七段饮料视频仍在，手机端能切换到“饥荒实况”，390px 无水平溢出。证据为 output/sidebar-cleanup-check.json；本次只改前端，后端归档接口未移除。

### 页面用语

主操作改为“查找片段”，删除编号流程条；查找方式用普通话解释等待与额外查找的区别。上传、处理进度、没有结果、时间可能偏差的提示也改为直接说明。详细画面判断折叠到“为什么找到这段？”，技术调用仍在可展开的技术详情中。

真实浏览器验证了两种查找方式的说明、三段饮用结果、画面判断展开、上传提示和 390px 布局。构建通过，检查记录为 output/plain-copy-check.json。这轮文案修改未改变后端检索和模型提示词。

### 按视频项目管理

素材库在普通界面改称“视频项目”，上传和查找范围仍按项目隔离。新增持久化的收起 / 恢复设置（Flyway V4），六个历史验收项目收起保留，未删除其视频与记录。浏览器实际创建“旅行录像”空项目，完成收起、恢复和刷新验证，并切回七段饮料项目。手机端也验证了两个项目的视频范围不同。

后端新增归属、CSRF、重复收起、恢复与视频保留测试，全套 41 项通过。浏览器另外延迟旧项目的视频响应，再切换项目，确认旧响应不会污染当前列表；当前项目检索仍返回三段饮用结果。记录见 output/project-visibility.json 和 output/project-group-browser.json。

2026-11-22 将产品名改为 FRAMELOCK，更新登录、素材库、检索、浮动播放器、任务窗口和上传窗口。实际浏览器验证了三段饮用结果、视频目标时间跳转、任务与上传窗口开关、Escape 关闭、上传弹窗的焦点限制、手机端七个素材库切换、收藏空态与任务记录。390 / 768 / 1024 / 1440px 均无水平溢出；减少动态效果设置下呼吸动画停止。

此次没有修改后端业务逻辑，前端与嵌入页面的 JAR 构建通过。新版截图在 docs/images，检查记录在 output/redesign-check.json；之前的后端测试与检索效果数字不因界面改版更新。


## 2026-11-26 核心技术栈实现

Spring Boot 3.5.9 / Undertow、MinIO、RocketMQ / Redisson Starter、Tesseract、VideoContext、Critic 与 Redis 追问已接入。59 项测试通过，失败、错误、跳过均为零。真实组件测试覆盖 MinIO 范围读取与清理、Redis 锁/令牌桶/通知/记忆、Qdrant 用户与版本过滤、本地 OCR；独立 MySQL 整链经过实际上传、MQ 解析、索引发布、检索、追问、重复终态消息与删除。整链模型使用明确的零费用替身，测试主题及消费组与日常应用隔离。

最终制品运行在完整模式，真实模型检索返回三个饮用片段；随后追问“只看直接对着瓶口喝的片段”，返回 bottle-sip.mp4 的 2286–3619ms，耗时约 72.81 秒。两次检索均在八次工具限制下返回，不能解释为穷尽全部候选。新增费用估算及预留合计 0.081232 元，原账本累计 3.692733 元，其中保留此前不确定费用的预留。

旧预测离线重算仍为 Recall@5 0.9375、无答案误报零；这不是新版全部十二条查询的真实供应商对照。桌面与 390px 页面检查确认追问栏可见、无水平溢出，三个项目与原有一个收藏保留。证据入口为 output/alignment-check.json；新版本完整模式启动及重复启动已通过。API/Worker 分容器部署和 MinIO 新视频经 OSS 音频桥接到云 ASR 的整条链路仍未实测。


## 2026-11-26 发布前五项修复

根目录 tmp/ 已排除，公开验收材料位于 docs/evaluation。中间件整链改用 FFmpeg 现场生成的合成视频，测试不依赖饮料原片。Java、Maven 与 WSL 发行版统一从环境配置解析。

Agent 保留八次工具上限，首轮最多检查两个候选，为补查及核验预留次数。结果新增 completion，页面区分正常无答案和达到次数／时间限制，兼容旧记录，并保留已核验片段。

本机和仅含可公开源码的干净目录均完成 61 项后端测试，另有三项前端行为测试；真实中间件采用独立测试库和模型替身。浏览器覆盖四种结束情况、输入聚焦与 390px 布局。停止后用显式 WSL 发行版重启成功，三个项目和一个收藏保留。本轮没有调用模型，没有新增模型费用。

公开摘要见 [发布准备验收](evaluation/release-readiness.json)，历史技术栈与模型检查见 [此前验收](evaluation/2026-11-26-stack-alignment.json)。没有执行 GitHub 提交或上传。
