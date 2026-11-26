# FRAMELOCK 技术实现

2026-11-26。本文说明视频项目、上传、媒体解析、检索、Agent 和任务恢复的具体实现。

## 组件与职责

| 组件或机制 | 用途与实现 |
| --- | --- |
| Java 21、Spring Boot 3.5.9、Undertow | Spring Security 继续负责 Session、CSRF 和权限 |
| MyBatis-Plus、Flyway、MySQL 8 | 保留；已有 MySQL 8.4 数据卷不降级，迁移追加 V7/V8 |
| MinIO | 新上传默认保存到 MinIO；上传初始化固定存储后端，旧 OSS 对象继续可读 |
| RocketMQ Spring Boot Starter | `RocketMQTemplate` 发布 Outbox，注解消费者执行阶段任务；失败记录另发失败主题 |
| Redis、Redisson Starter | 用户及全局令牌桶、合并锁、检查点热缓存、追问记忆、Pub/Sub 通知 |
| Qdrant | 关键词与向量混合检索；按用户、项目、视频、版本与执行代号过滤 |
| FFmpeg、Tesseract | 连续视觉采样保留；OCR 采用场景变化与静态保底采样、差分感知哈希去重 |
| 独立有界线程池 | ASR、OCR 各并发一、排队四；饱和后由持久任务重试，不无限扩线程 |
| VideoContext | 统一语音区间、OCR、视觉描述、原始证据、片段向量与整数毫秒时间 |
| Planner → Executor → Critic | Planner 通过 LangChain4j 选工具；Executor 检索；Critic 重读原画面并校验结构、条件和时间边界 |
| 分阶段 Checkpoint | 上下文、计划、执行和 Critic 状态持久化；恢复沿用原期限及工具预算 |
| Redis 多轮记忆 | 当前结果下“接着找”；最多五轮、24 小时过期，原目标及父结果保存在 MySQL |
| SSE | Redis 通知触发授权查询；定时查询补偿丢失通知，不以通知作为任务事实 |
| OkHttp | 视觉、Embedding、ASR 与 Qdrant 的 HTTP 调用统一使用 OkHttp |

MyBatis-Plus 处理实体读写，事务与条件更新控制任务状态；Jackson 负责 JSON 序列化。

## 设计取舍

模型仍为已验证的 Qwen3-VL、Fun-ASR、text-embedding-v4；动作检索需要连续画面信息，不能仅凭语音与 OCR 判断“喝了”还是“拿着”。

ASR 服务需要公网可读取的音频地址，因此派生音频继续写私有 OSS 并生成短期链接。原视频和上传分片默认在本机 MinIO。MinIO 对象键带 `minio:` 路由标记；不带标记的历史对象仍按 OSS 读取，不批量搬迁用户视频。

Qdrant 新集合为 `framelock_text_embedding_v4_1024_visual_v2`。完整模式从数据库已有向量补建已发布版本，不调用 Embedding；索引失败会稍后重试。检索遇到 Qdrant 故障可用已有向量和关键词继续，并在结果显示降级原因。

项目的业务仍是素材片段查找。Outbox、数据库幂等、执行代号和权限隔离继续保留。

## 本机启动和重跑

- `启动FRAMELOCK.cmd`：轻量模式，MySQL、MinIO、数据库任务调度和本地向量计算。
- `启动FRAMELOCK完整模式.cmd`：同一后端进程运行 API 与 Worker，实际使用 Redis、RocketMQ 和 Qdrant。切换模式前先停止应用。
- `停止FRAMELOCK.cmd`：停止本项目服务，保留数据。
- `scripts/start-full.ps1`：另外提供 API/Worker 分容器部署配置；本轮没有验证这套分容器应用部署。

Windows 的本地 OCR 经现有 WSL Ubuntu 调用 Tesseract，需安装 `tesseract-ocr` 和 `tesseract-ocr-chi-sim`。启动脚本默认启用；`VS_OCR_ENABLED=false` 可关闭，`VS_OCR_MODE=native` 可使用宿主机安装。OCR 缺失或失败时保留视觉分支，并在片段解析证据中记录状态。

MinIO 官方容器地址在本机返回 401，Docker Hub 同名镜像也无法拉取。`prepare-minio.ps1` 改从同一[官方发布](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z)下载 Linux 二进制，验证发布方 SHA-256 后构建本机镜像，并保留许可证。缓存位于忽略的 `.data/minio-build`。

重跑真实组件及整链测试：

```powershell
./video-search/scripts/verify-stack.ps1
```

它会创建独立的 `framelock_alignment` MySQL 库。整链测试经过实际上传、FFmpeg、MinIO、MQ、Qdrant、Redis、检索、追问与删除，但模型响应明确使用零费用替身。真实组件测试另覆盖 MinIO Range、Redis 锁/令牌桶/通知、Qdrant 用户及版本过滤、本地 OCR。59 项测试全部通过，未跳过项目。另用真实模型验证了三个饮用片段及一次瓶口饮用追问；新增费用估算及预留合计 0.081232 元。证据见 `output/alignment-check.json` 和相应日志。

## 实现中的关键问题

| 常见追问 | 结合本项目的回答依据 |
| --- | --- |
| 为什么不用一个锁保证幂等？ | Redisson 锁减少重复工作；唯一约束、条件更新、租约及执行代号才约束失锁、重启和重复投递 |
| 消息提交后发送失败怎么办？ | 业务事实与 Outbox 同事务提交，发送失败后补发；消费者仍需幂等 |
| 为什么用令牌桶？ | 限制用户和全局 AI 请求速率；与模型并发信号量、费用账本分别控制速率、并发及费用 |
| Worker 挂在 Planner 后面呢？ | 计划及候选持久化，恢复已完成检索和待执行计划，工具次数及原期限继续累计 |
| Redis 挂了会丢任务吗？ | MySQL 为真源；缓存和通知可缺失，完整模式的 Redis 限流不能宣称故障时仍有效 |
| Critic 怎样避免自说自话？ | 重读原画面，保留原目标，拒绝无证据或越界时间；不能以角色数量代替准确率验证 |
| 追问能否跨项目找？ | 父任务归属、项目、视频范围由服务端约束；已删除或换版本的父结果拒绝继续引用 |
| Qdrant 故障怎么办？ | 结果记录降级，使用已有向量及关键词；新版本仍需索引完成后发布 |

简历可写实现的机制与实际测试范围。旧版七段素材的 93.75% Recall@5 不代表此次改造重新取得相同成绩；没有新的实测对照时，不写 Agent 带来准确率提升，也不写生产吞吐或多机压测。

联调测试使用独立 MQ 主题、消费组和生产者组，避免与日常应用争抢消息。
