# 阶段 1：Java 本机服务实现与验收说明

更新日期：2026-11-23。状态：功能代码与自动化验证已实现，真实 electerm GUI 和四协议远程服务验收待完成。

## 1. 已实现范围

- electerm 下载、启动、检查并确认更新、确认删除、打开所在目录。
- SSH / SFTP / RDP / VNC：Web 提交 IP、端口、用户名，Java 直接通过固定 EXE 的 CLI 发起连接。
- 下载和更新使用异步任务；页面展示进度、错误码和请求标识，刷新页面接续已有任务。
- 官方资产唯一筛选、SHA-256、下载/解压配额、Windows 路径校验、链接与重解析点拒绝。
- 更新先准备完整候选，再保留便携用户数据并切换；失败恢复旧版，重启检查切换记录。
- 删除只针对本服务拥有的安装；保留已识别用户数据，不结束用户进程，不接管旧 ToolHelper 或其他 electerm 安装。

Java 只提交 GUI/连接请求，不声称窗口已就绪或远程已连接。密码、密钥选择、MFA、首次信任和协议错误在 electerm 中处理，不在 Web 保存凭据。

## 2. 运行与构建

环境：Windows x64、Java 21、Maven 3.9+；前端使用项目已有 Node/npm 依赖。在当前登录用户的桌面启动服务，不安装为 Windows 系统服务。

以下命令均在项目根目录执行。开发时两个终端分别运行：

```powershell
mvn -f backend-java/pom.xml spring-boot:run
```

```powershell
npm ci
npm run dev
```

开发地址 `http://127.0.0.1:5173`，Java `127.0.0.1:18081`。默认端口冲突直接报错。正式本机运行：

```powershell
npm run build
mvn -f backend-java/pom.xml clean package
java -jar backend-java/target/toolhelper-local-service-0.1.0.jar
```

访问 `http://127.0.0.1:18081`。Java 提供 Vue 静态产物与 History 路由回退，API 不回退成 HTML。Maven 不自动执行 npm；必须先生成 `dist/`。仅开发 Java API 时可以不构建前端，但包含页面响应验证的完整 HTTP 测试应先执行 `npm run build`。

可选配置均为**启动本进程**的配置，不需要改变系统环境：

| 配置 | 默认/用途 |
| --- | --- |
| `TOOLHELPER_DATA_ROOT` | 项目下 `.runtime/local-data`，插件与任务专用根目录 |
| `TOOLHELPER_PORT` | `18081`；修改时同步调整 Vite 代理 target |
| `TOOLHELPER_LOG_FILE` | 数据根的 `logs/service.log`，10 MiB 轮转、保留 7 天 |
| `--toolhelper.data-root=...` | 命令行指定隔离数据目录 |
| `--logging.file.name=...` | 单独指定日志；未指定时自动跟随最终生效的数据根目录 |

默认数据根为 `<项目根目录>/.runtime/local-data`，本项目对应 `D:\DevlopProject\ToolHelper_Web\.runtime\local-data`；安装包暂存、插件、便携用户数据、任务与日志均保存在该目录内。VS Code 的 Java 调试配置和 Maven `spring-boot:run` 已将工作目录固定到项目根目录；执行 JAR 时也应先进入项目根目录。相对数据路径以进程工作目录为基准，不依赖项目在某一盘符上的固定位置。

例如临时指定另一个项目内数据目录，日志自动写入该目录的 `logs/service.log`：

```powershell
java -jar backend-java/target/toolhelper-local-service-0.1.0.jar `
  --toolhelper.data-root=".runtime/custom-data"
```

旧版本位于 `%LOCALAPPDATA%\ToolHelper_Web` 的数据不会自动搬迁或删除。切换到新默认目录后只读取新目录；如果旧目录已有安装或用户数据，须先停止后端和 electerm，再核对迁移。本次检查旧目录没有 `current/install.json` 安装清单，未迁移旧目录。

`application.yml` 可配置配额：下载 512 MiB、解压总量 2 GiB、单文件 1 GiB、最多 30000 条目/24 层、用户数据 2 GiB、任务 7 天/1000 条。默认预留空间按上限保守估算；不足时停止安装或更新。

### 开发代理与后端启动排查

页面提示 `SERVICE_UNAVAILABLE` 时，先检查 Java 启动日志是否出现 `Started ToolHelperApplication`，并确认实际监听 `127.0.0.1:18081`。Vite 开发/预览代理在连接失败时返回 HTTP 503 JSON；GET 显示服务不可用，POST 保留 `REQUEST_OUTCOME_UNKNOWN`，避免把不确定的操作自动重发。正常业务响应仍按原样转发。

如果 Java 启动失败并提示“已有服务管理该数据目录”，说明有另一个 JVM 持有数据根的进程锁。先核对已有启动终端和进程，再停止对应服务或复用已在正确端口运行的实例；仅修改端口不能解除同一数据目录的互斥。不要通过删除 `service.lock` 或结束所有 Java 进程处理。结束临时验证服务时，须确认实际 JVM 已退出，而不是只结束 Java 启动器。

### 下载网络策略

Java 启动入口在 Spring 和网络客户端初始化前默认启用 `java.net.useSystemProxies=true`。从 VS Code 的 Java 入口运行、Maven `spring-boot:run` 或可执行 JAR 启动，均执行相同初始化；不修改 Windows 设置，不增加第三方依赖。

- 通过 JDK 的系统代理选择器，分别为 GitHub API、安装包地址及重定向后的资产域名选择路由；遵循系统代理和绕过规则，无适用代理时直接下载，不要求用户安装代理软件。
- 如果代理软件使用规则模式，请求交给系统选择的 HTTP 代理后，再由代理软件判断直连或转发；应用不读取特定代理软件的私有配置。TUN/VPN 下的直连仍可能由系统网络层接管，日志不代表最终出口。
- 代理不可用时明确返回 `UPSTREAM_CONNECTION_FAILED`，不会自行重试直连。只选择系统返回的首选路由；不探测常见代理端口、不切换镜像，也不修改 TLS 或官方 SHA-256 校验。
- JDK `HttpClient` 的代理通道使用 HTTP CONNECT 承载 HTTPS。若选择器返回 SOCKS，则报告 `PROXY_TYPE_UNSUPPORTED`，需将系统代理配置为代理软件的 HTTP/Mixed 监听端口；不把 SOCKS 悄悄当作直连。
- 保留用户显式提供的 JVM `-D` 代理配置；`-Djava.net.useSystemProxies=false` 可关闭系统代理读取。仅设置浏览器扩展、npm/Maven 代理或 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量，不视为已经配置了 Java 的系统代理。自动配置脚本的解析与支持范围由当前 JDK/Windows 的系统代理机制决定。

每个目标首次选路或路由发生变化时，在控制台及服务日志记录 `mode=DIRECT` 或 `mode=HTTP_PROXY proxyHost=... proxyPort=...`，不在选路日志中记录签名下载 URL。该日志表示应用选择的连接方式，不保证代理软件的最终出口或下载速度。

切换本机代理设置后，建议重启 Java 后端并重新检查版本/下载，确保新的系统配置生效；正在传输的连接不会中途切换。若使用已有 JAR，须重新打包才能包含本次修改。

### GitHub API 限流

`GITHUB_RATE_LIMITED` 表示官方版本查询或下载服务返回限流响应，不表示本机代理未生效。GitHub 未认证 REST API 的主配额通常为每个出口 IP 每小时 60 次；共用代理出口的其他请求也可能消耗同一配额，不能仅按本项目的点击次数判断。项目当前未配置 GitHub Token，安装前需要官方 API 返回的发行信息和 SHA-256。

后端遵守 `Retry-After`，主配额耗尽时同时等待 `X-RateLimit-Reset`；两者均无有效未来时间时至少冷却一分钟。页面错误包含预计可重试的本机时间及 UTC 偏移。冷却期间，重复查询在本地返回同一提示，不再访问该域名；到期后由用户重新点击触发查询，不后台自动重试。

冷却按上游域名隔离，并保存在当前进程内存中；已有合法资产选择的安装不因 API 域名冷却而直接中断，但选择仍遵守原来的 15 分钟有效期。重启后端会清除本地冷却记录，不能重置 GitHub 的服务端配额。普通 403 权限响应不误报为限流；上游日志记录状态、配额和恢复时间，便于根据请求标识定位。

出现此错误时，先等到提示时间后再试。应用不会自动关闭代理、轮换节点或使用第三方镜像来规避限流。若使用场景经常触及匿名配额，可另外评估可选的用户 GitHub Token 认证；当前版本尚未实现该配置，不应把 Token 提交到代码库或前端。

## 3. 分层与数据目录

```text
RemoteTool.vue → useRemotePlugin.js → remotePlugin.js
  → LocalRequestFilter → RemotePluginController → RemotePluginService
    ├─ TaskCoordinator：互斥、幂等、持久任务与中断标记
    ├─ ReleaseClient / DownloadProxySelector：官方发行选择、系统代理选路与下载校验
    ├─ ArchiveExtractor：受限 TAR.GZ / ZIP 解压
    ├─ InstallationStore：清单、便携数据、目录切换与恢复
    └─ ProcessAdapter：平台、进程观察、固定参数启动与目录打开
```

```text
<项目根目录>/.runtime/local-data/  # 默认 dataRoot
├─ service.lock
├─ plugins/electerm/
│  ├─ current/{app/,install.json}
│  ├─ staging/<taskId>/
│  ├─ backup/<taskId>/          # 更新旧版本，含原用户数据
│  ├─ preserved/<taskId>/       # 卸载保留的便携数据
│  └─ transaction.json         # 仅切换事务进行中存在
├─ tasks/<taskId>.json
└─ logs/service.log
```

备份和保留数据不自动删除。任务记录过期清理仅清任务元数据，不删除备份。用户 home 下 `.ssh` 不在管理范围。检测到未知文件、junction、占用、布局不明或修订冲突时停止操作并保留现场。

单次目录移动要求文件系统支持同盘 `ATOMIC_MOVE`，两个移动不构成整体原子事务。事务记录用于失败恢复；不能安全恢复时进入 `RECOVERY_REQUIRED`，只允许查询及打开管理目录，人工核对后再处理。失败候选/无法清理目录会保留用于诊断；不要通过反复点击更新来处理恢复状态。

## 4. 本机接口契约

所有接口返回 `{requestId,data}` 或 `{requestId,error:{code,message,retryable}}`。仅允许 loopback、受控 Host/Origin；不开放跨域 OPTIONS。不支持任意程序、路径、URL 或 CLI 参数。

先 `GET /api/v1/session`，请求头 `X-ToolHelper-Client: web`，将 `data.token` **仅保存在内存**。后续请求带 `X-ToolHelper-Session`。运行时 token 随 Java 重启变化；GET 可更新 token 后重试一次，POST 不自动重放。

| 方法 | `/api/v1` 下路径 | 请求/行为 |
| --- | --- | --- |
| GET | `/plugins/electerm` | 安装状态、版本、修订、进程观察、当前任务 |
| GET | `/plugins/electerm/update-check` | 官方候选版本、大小、兼容性及 15 分钟有效选择 ID；不下载安装 |
| GET | `/tasks/{taskId}` | 任务阶段、进度、结果/错误 |
| POST | `/plugins/electerm/install` | `releaseSelectionId`，后台安装，202 返回任务 |
| POST | `/plugins/electerm/update` | `releaseSelectionId`、`expectedInstallRevision`、`confirmed:true` |
| POST | `/plugins/electerm/uninstall` | `expectedInstallRevision`、`confirmed:true` |
| POST | `/plugins/electerm/launch` | 空 JSON `{}`，请求启动或报告已运行 |
| POST | `/plugins/electerm/open-directory` | 空 JSON `{}`；未安装或恢复状态打开管理目录 |
| POST | `/plugins/electerm/connect` | 下面所示四字段；只报告 `CONNECTION_REQUESTED` |

POST 要求 `Content-Type: application/json`、16～128 位 `[A-Za-z0-9_-]` 格式的 `Idempotency-Key`。同键同请求返回已有结果，同键不同请求 409；超过 7 天不保证去重。请求体最多 16 KiB，未知字段及非整数端口被拒绝。

```json
{"protocol":"RDP","host":"2001:db8::1","port":3390,"username":"DOMAIN\\user"}
```

只支持 IPv4/IPv6 字面量，暂不支持主机名、IPv6 zone ID；端口 1～65535。SSH/SFTP/RDP 需要用户名，VNC 可空。拒绝控制字符，用户名最多 256 字符。

常见状态：`NOT_INSTALLED`、`INSTALLED`、`BROKEN`、`RECOVERY_REQUIRED`。进程状态独立，为 `NOT_OBSERVED`、`RUNNING`、`UNKNOWN`，均不代表真实远程会话状态。常见错误包括 `PLUGIN_BUSY`、`PLUGIN_IN_USE`、`PLUGIN_INSTANCE_CONFLICT`、`INSTALL_REVISION_CONFLICT`、`PLUGIN_VERSION_UNVERIFIED`、`GITHUB_RATE_LIMITED`、`CHECKSUM_MISMATCH`。保留后台任务 ID 或 HTTP 请求标识，结合服务日志定位。

安装/更新成功结果包含 `cleanupPending`、`cleanupDirectory`；更新结果包含 `backupDirectory`，卸载结果包含 `preservedDirectory`。页面日志会展示需保留或人工检查的目录。

## 5. electerm 版本与连接适配

首期适配白名单为 **5.12.6**。官方发行查询每次选择精确的 Windows x64 portable 资产，默认要求 GitHub 提供 SHA-256；缺失、歧义、不支持版本或校验失败均停止，不切换未知镜像、不忽略 TLS。

2026-11-23 已核实官方 tag `v5.12.6`，源码 commit `67048909b11a9f66b0ef7580166358c5aedc0d03`；安装包 `electerm-5.12.6-win-x64-portable.tar.gz`，152677808 字节，SHA-256：

```text
352aa63e90ea78847416f01f7c0b7a4bf57da97336810f45fab4df54f5e78cf8
```

连接使用 `--tp ssh|rdp|vnc --opts <JSON>`，SFTP 为 `--tp ssh` 加 `--sftp-only`。源码 `command-line.js`、`load-data.js` 已确认结构化参数入口和发起会话分支。Java 在当前 JVM 固定 JDK 21 Windows 的安全 EXE 参数编码模式，原生子进程测试验证 JSON 引号、空格、IPv6、域用户名及特殊字符完整传递，不使用 shell。

子进程 `DATA_PATH` 固定为本安装 EXE 同级的 `electerm` 数据目录，清理继承的 `ELECTRON_RUN_AS_NODE` / `NODE_OPTIONS`。不主动注册 URI 协议；electerm 自身的运行行为仍需目标环境验收。

该发行版存在跨安装单实例转交：发现其他 electerm 进程时拒绝发起连接；新实例启动后的 10 秒内拒绝转交第二个连接请求。10 秒只是保守防误发窗口，不是“窗口已就绪”的确认；进程观察也不能提供绝对的 GUI/会话就绪证明。

未来官方版本更新时不会绕过白名单安装。应先核对该版本源码、包结构、数据迁移和四协议实测，再修改 `supported-versions`；仅改配置不能视为完成适配。

## 6. 验证与待验收

常规验证：

```powershell
npm test
npm run build
mvn -f backend-java/pom.xml test
```

自动测试使用 `target` 中的隔离夹具；不会删除真实用户插件或连接真实设备。官方包验收是显式可选项，包路径不写死到生产逻辑：

```powershell
mvn -f backend-java/pom.xml `
  "-Dtoolhelper.acceptance.archive=D:\DevlopProject\ToolHelper_Web\.runtime\electerm-5.12.6-win-x64-portable.tar.gz" test
```

截至 2026-11-23：

- 后端常规验证 51 项中 50 项通过、1 项可选官方包验收跳过；此前已单独完成官方包解压验收，本次目录配置修改未重复执行该项。
- 前端 11 项请求、开发代理和任务生命周期测试通过，Vue 构建及 Java 可执行 JAR 打包通过。
- 覆盖正式资产与摘要、下载长度/散列、ZIP/TAR 越界与链接/配额/大小写冲突、四协议参数、原生 Windows 进程参数、更新失败保留旧版、切换回滚、用户数据保留、互斥/幂等、重启中断、Host/Origin/session、请求实际长度、未知字段及 HTTP 路由。
- 官方包实际下载、SHA-256、安全解压、唯一 EXE 和安装清单通过；Java 实际 GitHub 版本检查、开发代理 session、状态接口和打包后的页面 HTTP 响应通过。
- 浏览器检查开发代理和 JAR 同源页面均正确显示未安装状态；停止隔离测试 Java 服务后，页面显示“本机服务不可用”并禁用插件操作。
- 新增 10 项代理测试，覆盖无代理直连、按域名绕过、首选路由与变化、不支持的 SOCKS、无效配置、独立 JVM 初始化及本机 HTTP CONNECT 代理集成；新 JAR 在隔离数据目录与随机端口启动后，实际通过 Windows 系统代理完成 GitHub 版本查询，HTTP 200。未重新下载完整安装包，未进行代理/直连速度对比。
- 新增 6 项限流测试，覆盖主配额恢复时间、冷却期间不重复联网、到期恢复及原有缓存、秒数/HTTP 日期形式的 Retry-After、无效/过期响应头、普通 403 区分和下载域名隔离。实际只读核对了代理出口的 GitHub 403、60/60 已用配额与恢复时间；不会通过反复请求执行真实限流恢复测试。
- 新增 2 项数据目录测试，验证默认路径不依赖用户目录、显式相对/绝对路径仍生效；HTTP 集成验证只覆盖数据根时日志自动跟随。新 JAR 从项目根目录、不传数据和日志参数启动后，已验证默认目录、日志文件及本机状态接口；随机端口验证进程已停止，未停止原有后端。
- 新增 2 项 Vite 代理集成测试，覆盖后端未监听、正常业务 JSON 转发及 POST 断线不重发。实际验证 `18081` 直连与 `5173` 代理的会话和状态接口均返回 JSON；停止验证 JVM 后，开发代理返回 `503 SERVICE_UNAVAILABLE`。已核对实际 JVM PID 并确认退出，释放数据目录进程锁。

**尚不能标记为完成的验收：**真实 electerm GUI 启动/目录打开，SSH/SFTP/RDP/VNC 各协议使用实际服务、非默认端口、IPv6/域用户名的连接，首次认证/已有凭据、单实例转交、窗口就绪后再次连接，以及真实 GUI 生成的数据在升级后的可用性。当前没有用户提供的测试目标或凭据，不能把参数和源码验证写成真实连接成功。

建议在专用测试目录中安装，通过用户授权的四种测试服务逐项验收；更新验收还需两个已适配发行版。本轮未执行真实用户安装的卸载、系统配置变更或远程推送。
