# 测试与公开验收记录

普通测试和中间件整链测试会自行生成输入，不需要下载饮料视频，也不读取个人素材。

## 普通测试

准备 Windows、JDK 21+、Maven 3.9+、Node.js 22.12+、Python 3 和 FFmpeg 后，在工作区根目录运行：

```powershell
./video-search/scripts/build.ps1
```

这会运行前端行为测试、后端单元及业务测试，再生成可执行 JAR。真实中间件测试默认不运行，测试报告会显示这些显式关闭的项目。

## 真实中间件整链

另需准备启用 Docker Engine 与 Compose 的 WSL2 发行版，以及其中的 Tesseract 中英文语言包。

```powershell
./video-search/scripts/verify-stack.ps1
```

脚本在独立的 `framelock_alignment` MySQL 库中测试，使用专用 MQ 主题、消费组和测试对象。FFmpeg 生成六秒无音轨 H.264 图案视频；模型返回明确的测试响应。该路径验证上传、处理调度、索引、检索、追问和删除，不验证图案视频里是否有人喝饮料，不调用收费模型。

## 真实视频与模型实验

七段饮料开发素材没有放入公开仓库。相关实验需要本机准备 `samples/beverage/fixture-v1/` 中的 `clips/`、`annotations.json` 和 `queries.json`，并配置模型凭据。现有脚本缺少这些文件会直接说明原因；不要把它当作公开仓库必须通过的普通测试。

`run-lab.ps1 -Stage evaluate -SkipBuild` 只重算已有预测，还需要本机已有 `output/m0/predictions.json` 及构建好的 JAR；该命令不会生成缺失的预测，也不调用模型。

[2026-11-26 技术栈验收摘要](2026-11-26-stack-alignment.json)记录此前已完成的测试、真实检索和费用范围。[发布准备验收](release-readiness.json)记录本轮修复与干净目录检查。

旧开发集 Recall@5 93.75% 来自已有预测与历史服务对照；它不代表本次预算策略调整后重新取得相同成绩，也不代表未知视频上的效果。

## 干净目录检查

`python video-search/scripts/check-release.py --export <新目录>` 使用独立的临时 Git 索引读取忽略规则，不在工作区初始化正式仓库，不提交或推送。导出目录只包含可公开文件。

在导出目录准备同样的系统工具后执行构建。连接已存在的测试中间件时，可通过进程环境提供 VS_DB_PASSWORD、VS_REDIS_PASSWORD、VS_MINIO_ACCESS_KEY、VS_MINIO_SECRET_KEY；测试不要求复制原 `.env`。Maven 和 npm 可以复用外部依赖缓存，但不复制原项目的 node_modules、target 或素材。
