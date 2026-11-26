# FRAMELOCK · 运行说明

FRAMELOCK 从多个本地视频中检索目标画面，经过原画面核验后返回可回看的时间片段。项目预览、核心能力、系统流程和实测结果见[工作区 README](../README.md)。

## 启动本机版本

日常使用时，双击工作区根目录的 `启动FRAMELOCK.cmd`。脚本先等待数据库健康，再启动后台应用，检查 `/health` 后打开页面。重复启动会复用已有服务；关闭启动窗口不会停止应用。重启电脑后需要重新双击启动。

在工作区根目录也可以执行：

```powershell
./video-search/scripts/launch.ps1
```

打开 http://127.0.0.1:8090/。账号、密码分别位于本目录 `.env` 的 `VS_ADMIN_USER`、`VS_ADMIN_PASSWORD`。构建脚本先打包 Vue，再将页面嵌入 API / Worker 的同一 JAR；启动后不需要另开前端进程。

停止时双击工作区根目录的 `停止FRAMELOCK.cmd`，或执行 `./video-search/scripts/stop.ps1`。它只停止本项目服务及保活进程，不删除数据。`launch.ps1 -NoBrowser` 可只启动服务；代码修改后先停止，再运行 `launch.ps1 -Rebuild` 构建并启动。

前端打包进后端，统一从 8090 打开。localhost / 127.0.0.1 指当前电脑；服务器部署需要另外配置。本版本没有公网发布，也没有设置开机自启。

轻量模式使用 MySQL、MinIO、数据库持久队列及本地向量计算。完整模式由根目录 `启动FRAMELOCK完整模式.cmd` 启动，实际使用 Redis、RocketMQ 和 Qdrant；同一后端进程运行 API 与 Worker。整链已使用独立 MySQL 库及模型替身验证，细节见[技术实现说明](docs/technical-design.md)。

## 配置与路径

需要 Java 21、Maven 3.9+、Node.js 22.12+、Python 3、FFmpeg 和 Docker Compose。本轮支持 Windows＋WSL2，发行版内需运行 Docker Engine 与 Compose。脚本优先使用现有 JAVA_HOME，否则从 PATH 查找 Java 并校验 JDK 21+；Maven 从 MAVEN_CMD 或 PATH 查找。VS_WSL_DISTRO 可指定发行版，未设置时使用系统默认发行版。无需修改脚本里的个人路径。

启动脚本先读取工作区 `../.env` 的云配置，再读取本目录 `.env` 的应用配置。字段参考 [`.env.example`](.env.example)，至少配置数据库密码、登录账号和模型 / OSS 凭据。`scripts/init-env.ps1` 会在应用 `.env` 不存在时生成本机密码，不会自动提供云密钥。

FFmpeg 按 `FFMPEG_PATH`、`tools/ffmpeg/win-x64/ffmpeg.exe`、PATH 的顺序查找。前端依赖通过 `web/package-lock.json` 独立安装，首次构建缺少依赖时会执行 `npm ci`。

| 路径                           | 内容                                  |
| ------------------------------ | ------------------------------------- |
| `server/`、`web/`              | 后端及前端源码                        |
| `deploy/`                      | 完整模式 Compose、Dockerfile 配套配置 |
| `scripts/`                     | 构建、启动、样本处理和验收脚本        |
| `samples/beverage/fixture-v1/` | 冻结的七段样本、人工标注和十二条查询  |
| `docs/`                        | 实现、验证、实验和规划                |
| `output/`                      | 模型预测、评测、截图和运行日志        |
| `.data/`                       | 上传、媒体缓存、模型缓存和费用账本    |

`.env`、`.data`、样本及模型调用产物不提交。清空模型缓存可能导致重复付费，删除费用账本会破坏预算控制。具体整理边界见[工作区维护](docs/workspace.md)。

## 开发与验证

单独开发页面时，在 `web/` 下执行 `npm run dev`，访问 http://127.0.0.1:5174/，API 请求由 Vite 转发到 8090。

完整构建会运行后端测试；API 正在占用 JAR 时，先运行 `scripts/stop.ps1` 停止本项目，再构建和重启。不要结束无关 Java 程序。`scripts/start-local.ps1` 仍可用于前台调试，日常打开使用 `scripts/launch.ps1`。

普通测试会自行生成输入，在工作区根目录运行 `./video-search/scripts/build.ps1`。需要验证真实中间件整链时，准备好 WSL2、Docker 与 Tesseract，再运行 `./video-search/scripts/verify-stack.ps1`；整链模型使用零费用替身。具体要求见[测试与验收说明](docs/evaluation/README.md)。

### 已有本机开发数据时

以下命令用于历史饮料实验，公开仓库不提供对应视频、数据库项目和预测。离线评测需另行准备 `samples/beverage/fixture-v1/` 下的 `clips/`、`annotations.json`、`queries.json`，以及 `output/m0/predictions.json` 中对应的已有模型预测；新下载源码时请先运行上面的普通测试。

满足这些条件后，可以只重算已有预测，不调用云服务：

```powershell
./video-search/scripts/run-lab.ps1 -Stage evaluate -SkipBuild
```

下面的小样本调用还要求本机服务中已有“饮料素材演示”项目及已解析视频，并已配置模型和 OSS 凭据。显式指定 `--cloud` 后会调用收费服务，受共享模型账本限制：

```powershell
python ./video-search/scripts/exercise.py --library "饮料素材演示" --query "找出正在喝饮料的片段" --cloud
```

结果入口为 `output/m0/review.html`、`output/comparison-summary.json` 和 `output/local-acceptance.json`。历史对照对应当时制品版本，不与后续单例修复合并计分。真实验证、替身测试和未测项目见[验收记录](docs/verification.md)。

## 常见问题

| 现象                   | 检查方式                                                                 |
| ---------------------- | ------------------------------------------------------------------------ |
| 页面无法打开           | 先双击根目录的启动脚本；失败时查看 `output/api-background.log` 和 `api-background-error.log`，确认 8090 没有被其他程序占用 |
| 登录后提示会话失效     | 重新登录；任务状态仍保存在数据库                                         |
| 视频不能上传           | 确认 H.264 MP4、最长 30 分钟、最大 2 GiB，且素材库未满                   |
| 解析失败或长时间等待   | 打开任务窗口查看阶段与错误；修复配置后重试，不要直接删除数据库记录       |
| 模型不可用或预算不足   | 检查模型配置及 `.data/model-ledger.json`；不会自动切换到更贵的供应商     |
| 有画面命中但边界未确认 | 回看上下文；该标记表示定位仍有不确定性，不是精确剪辑点                   |

完整配置见 `deploy/compose.yml` 与 `scripts/start-full.ps1`。本次已验证真实中间件整链，API/Worker 分容器部署未验收。架构、实现细节和面试问答见[实现说明](docs/implementation.md)。

默认 MinIO 镜像由 `scripts/prepare-minio.ps1` 使用校验过的官方二进制构建。ASR 派生音频及历史对象仍使用 OSS；不要删除原 OSS 凭据。Windows OCR 默认通过 WSL 的 Tesseract 执行。重跑真实组件与整链验证可执行 `scripts/verify-stack.ps1`，模型替身不会调用收费服务。

公开测试、真实中间件测试和私有模型实验的区别见[测试与验收说明](docs/evaluation/README.md)。

## 从公开源码开始

先准备上面列出的系统工具，再运行根目录启动脚本。应用 `.env` 不存在时会生成本机登录和数据库密码；模型和 OSS 凭据需要自行填写。不要直接复制一份所有密码均为空的 `.env.example` 后启动，应先填写配置或让初始化脚本生成本机密码。

可以在进程环境或应用 `.env` 设置工具位置；已有进程环境优先：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
$env:MAVEN_CMD='C:/tools/apache-maven/bin/mvn.cmd'
$env:FFMPEG_PATH='C:/tools/ffmpeg/bin/ffmpeg.exe'
$env:VS_WSL_DISTRO='MyUbuntu'
```

只在有不同安装位置时设置这些值。`MAVEN_REPO_LOCAL` 可指定已有依赖缓存；没有设置时使用工作区 `.cache/m2`。运行 `scripts/test-runtime.ps1` 检查工具解析，运行 `python scripts/check-release.py` 检查可发布文件和文档链接。
