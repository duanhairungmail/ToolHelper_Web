# Java 本机服务：阶段 1～7 实现与验收说明

更新日期：2026-11-26。electerm、DBX、极早期接口验证、设备 MAC 台账、Druid 检测、KylinOS运维策略和密钥激活已接入同一个 Java 本机服务。第 1～6 节保留阶段 1 历史说明，第 7～12 节分别说明阶段 2～7。不同阶段的历史验收结果不互相替代。

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

阶段 1 历史验证记录（2026-11-23，阶段 2 当前结果见第 7 节）：

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

## 7. 阶段 2：DBX 数据库外挂

### 已实现范围与分层

数据库页面 `/tools/database` 已接入五项管理操作，以及 MySQL/PostgreSQL 带参连接。没有新增 Java 数据库驱动或第三方依赖，真实数据库会话由 DBX 建立。

```text
DatabaseTool.vue → useDatabasePlugin / useDatabaseConnection → databasePlugin.js
  → LocalRequestFilter → DatabasePluginController → DatabasePluginService
    ├─ plugin/common：官方传输、发行选择、安装事务、进程基础操作、管理流程
    ├─ plugin/dbx：DBX 包名、平铺布局、完整 data、固定 EXE 与深链参数
    └─ LocalDataRoot / TaskCoordinator：共享根锁、任务目录与全局单任务互斥
```

electerm 保留原接口，由薄适配器复用公共能力。任务新增 `pluginId`；旧任务缺失此字段时按 electerm 读取。页面只接续自身插件任务，另一个插件工作时显示占用。两个仓库的发行缓存和选择 ID 分开，同一上游域名的限流冷却共享，不通过切换插件绕过限流。

### 官方便携包与数据目录

适配仓库 `t8y2/dbx`，白名单发行版本 `0.6.72`，只选择 `DBX_0.6.72_x64-portable.zip`，拒绝同名前缀的 `.zip.sig`。2026-11-23 实际通过 Java 下载并校验的 ZIP：

```text
大小：45,910,741 字节
SHA-256：e1fa98463fa89b647f9358ed944d6e1924cee9b2c754c54926de443f722b50a9
```

包内是五个平铺文件：`DBX.exe`、`portable.dbx`、`portable-update.json`、`LICENSE`、`README.md`。实际 metadata 版本与 EXE FileVersion/ProductVersion 为 **`0.6.72-beta.20261119.2`**，与发行 tag `v0.6.72` 不同。代码采用精确映射并校验 metadata 与 EXE 摘要，状态与安装确认同时展示发行版本和包内版本；不通过忽略 beta 后缀接受未知包。

```text
<dataRoot>/
├─ service.lock                    # 两个插件只持有一份根锁
├─ plugins/electerm/               # 保持阶段 1 布局
├─ plugins/dbx/
│  ├─ current/
│  │  ├─ install.json              # 标识、修订、包文件清单与摘要
│  │  └─ app/
│  │     ├─ DBX.exe / portable.dbx / portable-update.json / LICENSE / README.md
│  │     └─ data/                  # DBX 完整便携数据
│  │        ├─ dbx.db              # DBX 运行后生成
│  │        ├─ .dbx/secret.key     # 如有，随完整 data 一起保留
│  │        └─ webview2/、logs/、tmp/
│  ├─ staging/<taskId>/
│  ├─ backup/<taskId>/
│  ├─ preserved/<taskId>/
│  └─ transaction.json            # 仅未完成切换存在
├─ tasks/<taskId>.json
└─ logs/service.log
```

默认 `dataRoot` 仍为项目 `.runtime/local-data`。更新完整复制 `data`，保留原安装备份；卸载先保留完整用户数据，再处理受管程序文件。不得只复制 `dbx.db` 而遗漏可能存在的密钥。程序文件摘要变化、外部自更新、未知文件或运行占用会阻止操作，不覆盖未知状态。

启动子进程时移除继承的 `DBX_DATA_DIR`，将 WebView2 用户数据、启动日志和 TEMP/TMP 定位到受管 `data` 子目录。首次受管 `data/dbx.db` 不存在但用户默认 `%APPDATA%/com.dbx.app/dbx.db` 存在时，返回 `DBX_EXISTING_DATA_CONFLICT`，阻止上游隐式导入。Java 不主动注册 URI 协议；未审计 DBX 的全部系统行为，不能据此承诺第三方程序绝不写入其他目录或注册表。

### DBX 接口与密码边界

沿用第 4 节 session、Host/Origin、请求大小、幂等键和错误响应规则。

| 方法 | `/api/v1` 下路径 | 请求/行为 |
| --- | --- | --- |
| GET | `/plugins/dbx` | 状态、发行版本、`packageVersion`、目录、进程观察、当前任务 |
| GET | `/plugins/dbx/update-check` | 官方候选、实际包版本、兼容性、限时选择 ID |
| POST | `/plugins/dbx/install` | `releaseSelectionId`；返回后台任务 |
| POST | `/plugins/dbx/update` | `releaseSelectionId`、`expectedInstallRevision`、`confirmed:true` |
| POST | `/plugins/dbx/uninstall` | `expectedInstallRevision`、`confirmed:true` |
| POST | `/plugins/dbx/launch` | 空 JSON `{}` |
| POST | `/plugins/dbx/open-directory` | 空 JSON `{}` |
| POST | `/plugins/dbx/connect` | 以下连接字段；成功只表示 `CONNECTION_REQUESTED` |

```json
{
  "type": "MYSQL",
  "host": "127.0.0.1",
  "port": 3306,
  "username": "example_user",
  "database": "example_db",
  "connectionName": "本次连接",
  "ssl": false
}
```

- `type` 仅 `MYSQL` / `POSTGRESQL`；界面默认端口分别 `3306` / `5432`。
- `host` 支持 IPv4、IPv6 字面量或 ASCII DNS 名，最多 253 字符；端口为 1～65535 的整数，不支持 IPv6 zone ID。
- 用户名必填；用户名、数据库名、连接名最多 256 字符，密码最多 2048 字符；拒绝控制字符及无效 Unicode，编码后总命令长度也有限制。
- 密码默认不包含在请求中。用户勾选“本次传递密码”才增加 `password` 字符串，空串与不传不同；发起即清空表单，失败不自动重放，离开页面清空。
- Java 使用固定受管 EXE 加单个逐字段编码的 `dbx://connection/new?...&one_time=true` 参数，不使用 shell 或系统 URL handler。**显式传入的密码会进入进程命令行；有权限的本机程序可能读取，URI 编码不是加密。**
- 含密码请求仅在进程内用随机密钥 HMAC 比较幂等；任务 JSON 保存 `EPHEMERAL`，不保存密码、完整 URI 或密码摘要。重启后相同敏感幂等键返回 `REQUEST_OUTCOME_UNKNOWN`，不重新发起连接。

不传密码时 DBX 可能弹出认证对话框，属于预期行为。`one_time` 用于本次连接；本次实测普通连接表和凭据表记录均为 0，但这不是对所有版本、缓存、恢复状态或第三方行为的“完全无痕”保证。TLS 开关传入上游，握手与证书行为仍需实际目标验收。

### 当前验证结果与使用方式

```powershell
npm test
npm run build
mvn -B -ntp -f backend-java/pom.xml `
  "-Dtoolhelper.dbx.archive=D:\DevlopProject\ToolHelper_Web\.runtime\DBX_0.6.72_x64-portable.zip" test
```

不指定 `toolhelper.dbx.archive` 时跳过可选 DBX 实包测试。2026-11-23 本轮前端 **17 项通过**；后端 **66 项中 65 项通过、1 项 electerm 可选实包测试跳过**，DBX 实包测试已运行。Vue 生产构建与 Java 可执行 JAR 打包通过。

自动测试覆盖发行/缓存隔离、共享根锁与任务、老任务兼容、密码不落任务与重启不重放、URI 编码和原生 Windows 参数、完整 data/密钥保留、未知文件与外部修改拒绝、更新下载失败及切换失败恢复、HTTP 字段校验，以及阶段 1 回归。

真实验收在独立的 `18082` 服务、项目内 `.runtime/phase2-acceptance` 数据根进行：通过 Java 官方下载链完成 DBX 安装；在隔离 MySQL 8.0.34（`127.0.0.1:23306`）和 PostgreSQL 18.6（`127.0.0.1:25432`）上，用低权限测试角色验证 MySQL 冷启动、运行中转交 PostgreSQL、中文连接名和带中文/特殊字符的密码，无需二次输入；GUI 与两个数据库的服务端会话均核对成功。一次性连接未进入本次普通连接/凭据表。

测试数据库文件位于 `.runtime/phase2-db`，未更改原有 MySQL 业务服务或启动原有 PostgreSQL 系统服务。按用户要求保留 DBX 窗口及支撑它的隔离数据库。最后一次默认不传密码测试停留在 DBX 认证对话框，未自动取消。

**尚待验收：**TLS 真实握手、错误/空密码的真实服务行为、IPv6 真实数据库、缺失或受损 WebView2、快速连续/跨安装转交、重启后一次性会话恢复行为，以及两个真实发行版之间的数据迁移。更新/卸载已验证隔离文件事务，不等于真实跨版本 GUI 迁移验收；数据库页面本轮未补做浏览器视觉验收。

本轮未主动停止原有 `18081` 服务；收尾只读检查时该端口未监听，日常使用需启动新 Java 后端，再刷新前端。验收安装不在默认 `.runtime/local-data` 中；默认页面仍需下载一次 DBX。Windows 下构建前先退出正在运行目标 JAR 的对应 Java 实例，避免 Maven 替换文件失败，不要结束其他 Java 进程。

最终新 JAR 检查中，数据库页面及生产资源、DBX 状态接口通过；版本查询返回 `GITHUB_RATE_LIMITED`，日志显示匿名额度 `60`、剩余 `0`，预计可重试时间为北京时间 `2026-11-23 22:45:43`。该时间不是配额恢复保证，之后仍以上游响应为准，本轮未继续重试。

## 8. 阶段 3：极早期接口验证

页面 `/tools/api` 已接入六项操作：开始验证、获取设备 ID、MQTT 主题、自动检测、清空结果（仅日志）、当前表格导出。另提供登录/退出、取消任务、状态刷新和 Cron 预览。MQTT 操作只从设备 HTTP API 读取主题字符串，不建立 broker 会话。

### 分层与生命周期

```text
ApiValidationTool.vue → useApiValidation.js → apiValidation.js
  → LocalRequestFilter → ApiValidationController → ApiValidationService
    ├─ TargetAddressPolicy：显式origin校验与HTTP确认
    ├─ LegacyApiCatalog：旧版16项固定接口、设备ID占位与POST限制
    ├─ TargetApiClient：直连目标、登录、AES兼容、有界HTTP响应和分页解析
    ├─ ValidationCron：现有Spring CronExpression的5/6字段子集
    └─ ValidationExport：JDK ZIP/XML生成真正的xlsx/docx表格
```

未新增第三方依赖。目标连接、Token、结果、最多 20 个任务及最近 10 轮统计仅保留在进程内存；不使用外挂的磁盘 TaskCoordinator，也不保存密码用于续登。浏览器只持有本机 session 和 connectionId；目标 Token 不返回浏览器。密码发起即清空，失败不自动重放登录。

同一进程同时只有一个 API 验证目标、一个执行批次；API 验证和外挂磁盘操作分开管理。所有目标变更、设备查询、批次和自动检测受同一验证服务互斥约束。旧窗口的 connectionId 不能操作新连接。页面离开后自动检测继续，重新进入可接续状态；Java 退出后全部清除。

### 登录与目标网络

登录体字段：`host`、整数 `port`、`scheme`（http/https）、`username`、`password`、`credentialMode`（PLAIN/AES_CBC）、`loginFormat`（JSON/LEGACY_QUERY）、`allowInsecureHttp`。主机只允许 IPv4/IPv6 或 ASCII DNS，不接收完整 URL、路径、shell、额外请求头等参数。默认 HTTPS、8088、AES_CBC、LEGACY_QUERY；HTTP 必须显式确认可信网络。

JSON 格式向固定 `/sys/login` POST `{userName,password}`；旧格式将相同字段编码到 query，POST 空 body。不会在失败后自动切换或重试。AES 参数按旧源码兼容，仅为上游格式，不保护 HTTP 链路或访问日志中的 query。用户名最多 256 字符，密码最多 2048 字符；登录响应仅适配 `data.token`、根 `token`、字符串 `data`。

Java 直连本次指定的目标，不使用外挂下载的系统代理；正常校验证书，不跟随 HTTP 重定向。后续固定请求同时发送 `Authorization: Bearer ...` 和 `token` Header。HTTP/业务码 401/403 表示需要重新登录；另兼容已实测目标的 `code=402`、`success=false`、`msg=请登录系统再操作！` 组合，普通 402 不当作登录失效。

Token 失效停止自动检测、清除 Token，不保存密码后台续登。仍可导出同一连接已保留的结果；手动重新登录、切换目标或失败登录会清除旧结果。

### 执行、分页和 Cron

- 目录与顺序对照旧 `EarlyWarningApiView.cs`；默认 13 个 GET。三个复位/写入 POST 默认不选，手动确认后才允许发送旧版 `{}`；自动检测始终拒绝 POST。测试未调用真实副作用接口。
- 只在选中项依赖设备 ID 时预取设备；无 ID 或预取失败则相关项 `SKIPPED`，不回退设备 1。登录失效则停止整个批次。
- 结果区分 `BUSINESS_OK`、`HTTP_OK`（业务未确认）、`BUSINESS_ERROR`、`HTTP_ERROR`、`INVALID_RESPONSE`、`AUTH_REQUIRED`、`SKIPPED`、`TIMEOUT`、`NETWORK_ERROR`。收到 4xx/5xx 仍表示 HTTP 可达，不表示业务成功。只保存静态路径和通用错误摘要，不保留原始响应体。
- 获取设备/MQTT 时按 `pageSize=100` 顺序读取，兼容数组和 `data.list/records/rows`，总数兼容根或 data 的 `totalCount/total/count`。有总数读至准确条数，无总数继续到空页。总数冲突/变化、重复页/ID、提前空页和配额超限均明确失败，保留上一份完整成功快照。
- 默认每次整体 HTTP 超时 15 秒、响应体 2 MiB、最多 1000 页/100000 条。可通过 `toolhelper.api-validation.timeout-seconds/max-response-bytes/max-pages/max-rows` 配置。白名单字段每项最多 4096 字符、一次设备与主题总文本最多 8 Mi 个 UTF-16 字符；超过上限报错，不静默截断。重复页只保留 SHA-256 指纹。
- Cron 使用现有 Spring 实现，只接受数字、`* ? , - /`，5 字段补秒 0，6 字段原样解析，7 字段含年份直接拒绝。时区为 Java 默认时区，页面显示时区及下次触发时间。不立即执行；上一轮未结束则跳过触发点，不并行、不堆积。最近 10 个正常完成批次按接口条目汇总通过率，接口错误会降低该轮通过率。
- 取消/停止会中止本机等待与后续请求，已到达目标的请求不能撤回。POST/DELETE 的断线或无法解析响应显示结果不确定，前端先刷新核对，不自动重放。

### HTTP 契约

沿用现有 loopback、Host/Origin、本机 session、16 KiB 请求体和严格 JSON 校验。下面路径以 `/api/v1/api-validation` 为前缀，除二进制导出外返回 `{requestId,data}` 或 `{requestId,error}`。本功能不使用外挂安装的 Idempotency-Key 磁盘去重，不能宣称断线时已知执行结果。

| 方法 | 相对路径 | 请求/用途 |
| --- | --- | --- |
| GET | `/session` | 登录状态、目标、表格、任务与调度；不含目标Token |
| GET | `/endpoints` | 固定16项与sideEffect标识 |
| POST | `/login` | 上述登录字段 |
| POST | `/logout` | `{connectionId}` |
| POST | `/verify` | `{connectionId,endpointIds,confirmedSideEffects}`，202返回任务 |
| POST | `/device-ids`、`/mqtt-topics` | `{connectionId}`，202返回任务 |
| GET / DELETE | `/tasks/{taskId}` | 查询/取消；DELETE需connectionId query |
| POST | `/auto-detection` | `{connectionId,endpointIds,cron}` |
| GET / DELETE | `/auto-detection` | 查询/停止；DELETE需connectionId query |
| GET | `/cron-preview?cron=...` | 下一次执行时间与zone |
| GET | `/export?connectionId=...&view=...&format=...` | view=results/devices/topics，format=xlsx/docx |

清空结果按用户确认只清日志，不发送清表或取消请求。导出包含当前 Tab 全部记录，页面每页 50 条只是展示分页。XLSX 使用 inlineStr，保留长数字和 `= + - @` 开头字符串，不创建公式；DOCX 使用可编辑原生表格、重复表头和固定列宽。二者只包含白名单字段，不包含密码、Token、原始业务响应或请求头。导出由浏览器下载，不提供服务端任意文件路径接口。

### 2026-11-24 验证记录

- 前端 26 项通过；Java 87 项中 86 项通过、1 项 electerm 可选实包跳过，DBX 实包运行；Vue 构建和 Java 可执行 JAR 打包通过。新增测试使用真实回环 HttpServer，覆盖两种登录格式、AES固定向量、token shape、超时/大小、重定向、分页、错误分类、取消、互斥、10轮统计、Token失效停止、OOXML ZIP/XML和HTTP契约；未新增第三方依赖。
- 隔离服务使用项目内 `.runtime/phase3-acceptance` 数据根和 18083 端口。经用户明确授权，在可信局域网 HTTP 8088 登录真实目标。JSON 登录返回 400；显式选择 AES_CBC + LEGACY_QUERY 登录成功，不自动降级。443拒绝连接、8088 TLS握手失败，因此本次不宣称HTTPS实测成功。
- Java读取全量设备4条、主题4条，13个GET全部HTTP200；5项有已识别成功业务码、8项仅确认HTTP成功。六类xlsx/docx二进制导出成功。当前真实样本只有一页，多页完整性由回环服务测试覆盖。
- 浏览器检查实际设备表格、13/16默认勾选、自动POST禁用、Cron时间预览、Excel实际下载，以及清空日志后表格保留。导出包可解压且XML解析通过；当前环境无打包文档渲染器，WPS窗口读取不稳定，**Office实际排版仍待视觉验收**。
- 未调用真实复位/写入POST，未修改目标服务配置、系统代理、原有数据库或外挂窗口。真实Token自然到期、长期Cron、真实大数据多页和Office多页排版尚待验收；这些场景的夹具测试不等于真实目标验收。

## 9. 阶段4：设备 MAC 查询与台账

Vue `/tools/mac` → `useDeviceMac` / `deviceMac.js` → `DeviceMacController` / `DeviceMacService` → `WindowsMacResolver` / `DeviceMacStore` / `DeviceMacExport`。新增模块独立管理内存任务，不使用外挂 TaskCoordinator，不改变插件生命周期。`ValidationExport` 与 MAC 导出复用提取后的 `support/TableExport`，阶段3列宽和接口保持不变。

### 查询与环境

仅支持 Windows 本机 IPv4。`NetworkInterface` 处理本机地址；远端查询通过系统目录中的 Windows PowerShell（NoProfile、NonInteractive、Hidden）运行内置固定脚本，不更改执行策略。目标仅作为校验后的环境参数传入，不拼接用户脚本。路由和适配器通过 NetTCPIP 读取，当前实现接受已启用 Ethernet/WiFi 接口（InterfaceType 6/71）；经网关转发、隧道及不支持接口明确返回失败。

探测前按实际网卡前缀拒绝子网广播，发出一次有界 Ping；无 ICMP 响应仍按精确 IP 和出站接口读取 Get-NetNeighbor。查询前后比较路由/源地址/前缀，变化返回 NETWORK_CHANGED。Reachable 显示已观察映射；Stale/Delay/Probe/Permanent 显示缓存映射；Incomplete/Unreachable/空映射不算成功。MAC 必须是有效六字节单播地址，统一大写冒号格式。代理 ARP、本机缓存和观察时间不构成设备身份或当前在线证明。

进程同时有界读取 stdout/stderr（各64 KiB），UTF-8解析JSON，检查退出码和错误输出。超时或取消终止进程与已识别后代并等待清理，清理完成前不释放任务槽位。实际命令或 CIM 权限不足明确失败，不自动提升权限。capabilities 的 supported 表示平台和 PowerShell 文件可用，NetTCPIP/权限在实际执行时校验。

### 台账与任务

- 数据保存在 `LocalDataRoot/device-mac/records.json`；默认项目内 `.runtime/local-data/device-mac/records.json`，服从 toolhelper.data-root。schemaVersion=1，记录使用稳定 UUID，台账有 revision/savedAt，lastObservation 与行结果分离。
- 页面草稿新增/编辑/删除/查询后须手动保存，保存携带 expectedRevision。版本冲突返回 LEDGER_VERSION_CONFLICT；文件损坏、未知版本、读取或写入失败不会显示为空台账再覆盖。复用 JsonFiles 原子替换和 SecurePaths；不安装驱动、不读写 Access、不自动迁移旧台账。
- 台账允许保留待修订的空/非法 IP 文本，查询时逐行严格校验；批量空 IP 为 SKIPPED，非法 IP 为 INVALID_IPV4，同一 IP 合并查询。观察值绑定原 IP，页面不把旧值应用到新 IP；本轮失败可保留明确标注的历史观察。
- 每个 Java 进程同时一个 MAC 批次，与接口验证、外挂任务分开。进度按台账行统计，COMPLETED 表示所有行处理完成，不代表全部成功。公共权限/命令故障使整批 FAILED；目标级无映射继续其他行。
- 任务状态 QUEUED/RUNNING/CANCELLING/COMPLETED/CANCELLED/FAILED；取消幂等，等待运行中的查询清理。内存任务结束后保留30分钟，最多20个任务；重启不恢复、不重发。
- requestKey 为 UUID，仅本进程保留期内去重：相同输入返回原任务，不同输入冲突。前端仅自动重试安全GET，不自动重放POST/DELETE。离开工具可继续执行，返回恢复任务；浏览器刷新会丢弃未保存草稿。

### 配额与 API

`toolhelper.device-mac` 配置：max-rows=1000、concurrency=4、timeout-seconds=10、max-field-characters=256、max-file-bytes=2097152、max-request-bytes=4194304。启动时校验范围。任务保留30分钟/20个为本阶段固定限制。

继续使用现有 loopback、Host/Origin、本机会话、严格 JSON 校验。仅下表的三个 POST 路径允许专用请求体上限（同时检查 Content-Length 与实际流字节），其他接口仍为16 KiB。

统一前缀 `/api/v1/device-mac`；二进制以外返回 `{requestId,data}` 或 `{requestId,error}`。

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | /capabilities | 平台支持状态和配额，不发起网络探测 |
| GET | /ledger | 持久化台账、版本与记录 |
| POST | /ledger/save | `{expectedRevision,records}`，返回新台账；专用请求体上限 |
| POST | /queries | `{requestKey,items:[{recordId,ipAddress}]}`，202返回任务；专用请求体上限 |
| GET | /queries/active | 活跃任务或null |
| GET | /queries/{taskId} | 进度和逐行结果 |
| DELETE | /queries/{taskId} | 取消，返回当前/最终状态 |
| POST | /export | `{format,columns,records}`，返回xlsx/docx；专用请求体上限 |

columns 允许 index（必选）、stationName、ipAddress、deviceName、deviceModel、serialNumber、macAddress、location、status、checkedAt。records 是页面固定草稿快照，全部或所选行由页面选择。Excel使用文本单元格，Word为原生横向表格；数据通过校验并转义XML，客户端不传服务器文件路径。

### 2026-11-25 验证与限制

已完成前端32项、后端全量98项（96通过/2个可选外挂实包跳过，包含新增HTTP契约及进程后代清理测试），Vue生产构建和可执行JAR打包成功。隔离服务使用18084端口、`.runtime/phase4-acceptance`数据根，真实HTTP验收1000行保存、版本冲突、重复请求、取消和两种导出；隔离重启后台账版本与观察值恢复、内存任务清空。

授权目标 `192.168.31.1 → 04:67:61:C6:82:F4` 与 `192.168.31.126 → F4:6A:DD:69:D5:77` 均匹配用户对照；本轮分别观察到Reachable和Probe（缓存映射）。导出可被openpyxl/python-docx读取，XML完整，MAC和长序列号保真。

用户授权后，浏览器已检查两条已保存台账、历史观察与邻居状态、草稿编辑后的未保存标记/保存按钮、横向表格操作区及导出弹窗的Word/Excel和列选择。恢复草稿时浏览器控制超时，未完成重新加载交互验收，测试编辑未保存。Office实际排版、禁ICMP设备、真实跨网段/VPN变化、普通用户权限及1000目标真实并发网络场景未完成真实验收。夹具测试与1000行保存不等于这些网络场景已通过。不修改用户原有服务/网络设置，不新增依赖。

## 10. 阶段5：Druid 监控暴露与访问控制检测

### 分层与检测范围

`DruidScanTool.vue` → `useDruidScan` / `druidScan.js` → `DruidScanController` / `DruidScanService` → `druid` 包中的目标策略、受限 HTTP 客户端、规则、证据判定、执行器和导出。模块独立管理内存任务，不改变外挂生命周期、阶段3登录或MAC台账；导出复用现有 `TableExport`，原模块调用无附录时保留原布局。未新增第三方依赖。

规则版本固定为 `toolhelper-1/druid-1.2.27`，依据Alibaba Druid 1.2.27源码维护，随应用升级，不提供在线更新或脚本执行。对象是数据库连接池的StatViewServlet监控，不是Apache Druid，也不是完整CVE扫描器。

固定13项GET：`index.html`、`basic.json`、`datasource.json`、`sql.json`、`websession.json`、`weburi.json`、`webapp.json`、`spring.json`及五个对应HTML（sql/websession/weburi/webapp/spring）。分页JSON只取 `page=1&perPageCount=10` 样本，不遍历全部统计、明细或重置接口。

### 目标与网络

- 输入完整HTTP/HTTPS URL，允许IPv4/ASCII DNS、应用上下文和自定义监控根；输入主机根时使用 `/druid/`，输入 `index.html` 时移除文件名。规范化后去重，保留监控路径，不猜其他目录；拒绝IPv6字面量、URL凭据、查询串、片段、控制字符、编码路径和路径穿越。
- 默认直连，独立于插件下载的系统代理；不关闭TLS校验。禁止自动跳转，只手动跟随同源监控根、首页或固定登录页，最多3跳，不跨源、不越界、不从HTTPS降级HTTP。
- 解析前检查地址，拒绝未指定、多播、链路本地及已知metadata地址；允许用户明确填写的本机/内网目标。JDK HttpClient未固定DNS解析到连接地址，不能宣称完全防御DNS重绑定；本机服务仍只允许受控loopback请求，禁止向网络暴露。
- HTTP状态与业务内容分开：只识别已核实的完整业务拦截签名（HTTP402、`code=402`、`success=false`、指定登录提示）。结果为 `BUSINESS_AUTH_REQUIRED`，表示本来源未取得匿名数据、Druid自身鉴权未验证；普通402为HTTP异常。不会自动登录业务系统、复用阶段3Token或将业务登录态当作匿名结果。

### 证据与指定凭据

HTML仅证明页面指纹。JSON要求 `ResultCode=1`、合法Content结构和规则字段签名；空样本需要先识别组件指纹，不因空记录而视为受保护。不同JSON规则返回相同非空正文时降为通用响应、无法判断。原始SQL、Session、数据库连接串、Cookie及密码均不进入结果或报告，只保留固定字段名、类别、样本条数和说明。风险是本项目规则等级，不等于CVSS；未知、路径未发现及未完成均不能宣称安全。

为复测业主表格中的历史未授权漏洞，目标摘要、详情和每条证据新增 `retest: {status, conclusion}`，由 `DruidRetest` 统一判定，页面与xlsx/docx复用同一结论。`DATA_EXPOSED`表示匿名监控接口暴露；`PAGE_EXPOSED`表示HTML监控页面匿名暴露，不能推断数据泄露；JSON路径返回HTML时仍为 `INCONCLUSIVE`。任一暴露优先报告；全部13项明确认证/来源/业务登录拦截才为 `BLOCKED`（本次未复现、当前来源匿名访问已拦截）；全部404为 `PATH_NOT_FOUND`；混合未知、未完成、失败为 `INCONCLUSIVE`。不会把部分拦截汇总成已修复，也不以指定凭据登录成功覆盖匿名基线。导出可选 `retest` 列（历史漏洞复测结论），附录逐端点始终附复测结论。

凭据验证是独立单目标任务：先运行完整匿名基线，再对已识别且受保护的JSON规则尝试一组用户指定的Druid凭据。只适配Druid固定 `submitLogin` 表单（`loginUsername` / `loginPassword`），不使用字典、不重试、不替换匿名证据。登录 `success`、有效Cookie与受保护JSON复查都通过才标记VERIFIED；业务平台拦截不触发盲发账号。HTTP发送凭据必须传 `allowInsecureHttp:true`，页面每次确认。凭据只在运行闭包中短暂持有，结束清除引用，不进入公开任务、不持久化；这不等于保证JVM内存立即擦除。

### 任务、配额与API

`toolhelper.druid` 初值：max-targets=100、concurrency=4、request-seconds=5、target-seconds=30、max-requests=16、max-response-bytes=1048576、max-request-bytes=262144。启动校验范围，网页显示实际配额。深度64/单字符串64KiB限制在JSON解析前执行；每任务结果预算2MiB，最多保留20任务/结束后30分钟。结果与Cookie不落盘，服务重启清空，不恢复或重发。

每进程只运行一个Druid任务，最多4个不同origin并发，同origin不同监控根串行。取消停止后续请求派发，并等待底层HTTP清理；无法立即中断的DNS/IO仍占用槽位，超时不保证操作系统底层立刻退出。任务COMPLETED表示目标已处理，不代表每个目标全部规则成功；逐目标有执行状态、完成规则数和失败详情。requestKey为UUID，仅当前进程保留期去重；凭据不保留密码副本供比较，重新验证必须使用新key。

继续使用本机session、Host/Origin及严格JSON规则。统一前缀 `/api/v1/druid`；二进制外返回 `{requestId,data}` 或 `{requestId,error}`。

| 方法 | 相对路径 | 请求与用途 |
| --- | --- | --- |
| GET | `/capabilities` | 配额、规则版本、固定目录、整改建议 |
| POST | `/scans` | `{requestKey,targets}`，202返回任务；专用256KiB上限 |
| POST | `/credential-checks` | `{requestKey,target,username,password,allowInsecureHttp}`，202；仍为16KiB上限 |
| GET | `/scans`、`/scans/active` | 已保留任务与当前任务；不自动发起检测 |
| GET / DELETE | `/scans/{id}` | 查询进度 / 取消 |
| GET | `/scans/{id}/results?page=1&size=50` | 脱敏摘要，最多50条/页 |
| GET | `/scans/{id}/results/{targetId}` | 已完成目标的固定端点证据 |
| DELETE | `/results` | 清空本模块已结束结果；有活跃任务时拒绝，不重置目标 |
| POST | `/export` | `{taskId,targetIds,columns,format,details}`，返回xlsx/docx；仍为16KiB上限 |

导出按当前任务选择目标及列，序号必选，支持脱敏明细；XLSX为摘要/端点详情/整改说明工作表，所有单元格按文本写入、不产生公式；DOCX为原生摘要表格及详情、整改段落。浏览器下载，不接受服务端文件路径。GET会话失效可刷新后重试一次，POST/DELETE不自动重放。页面离开继续执行，返回时接续任务；仅URL草稿在应用内存保留，凭据离开即清空。

### 2026-11-25 验证与限制

- 前端39项通过、Vue构建成功。后端在业务402分类修正前全量111项无失败（109通过/2项可选外挂实包跳过）。修正后最新全量112项为109通过/1错误/2跳过：原有electerm恢复测试的Windows原子目录移动发生 `AccessDeniedException`；定向重测时原失败用例通过、同类另一移动用例报相同异常，根因尚未确定。未改动插件安装或恢复模块，不把最新全量记作通过。
- 阶段5覆盖页面外壳、通用JSON、空记录、字段脱敏、深度/响应限制、13规则只读、Cookie隔离、指定凭据、业务402与普通402区分、首页受限但JSON公开、限流/超时/TLS、同源串行、4并发和取消；HTTP契约及原模块导出保留回归。
- 最终阶段5/请求过滤/HTTP契约/MAC/原表格导出的31项定向测试全部通过。随后使用 `mvn -f backend-java/pom.xml -DskipTests package` 生成可执行JAR用于隔离验收；打包成功不代表前述全量异常已解决。日志保存在项目 `.runtime/phase5-final-targeted.log`、`phase5-package.log`、`phase5-recovery-retest.log` 和 `phase5-package-final.log`。
- 历史漏洞复测结论补充后，最新同范围32项定向回归通过；覆盖全部拦截/404/普通402、页面与数据暴露优先级、部分失败不得判修复、JSON返回HTML、空样本、凭据基线隔离、HTTP结果字段及两类导出的结论。前端39项、构建、显式-DskipTests打包通过，日志为 `.runtime/phase5-retest-{java,frontend,build,package}.log`；没有重跑或宣称原electerm全量问题解决。
- 获授权的真实目标 `http://192.168.31.126:8088/druid/index.html` 的13个端点均返回HTTP402。已只读核对完整业务登录拦截签名；未发送任何目标凭据、未复用业务Token、未复现匿名数据暴露。Druid组件与自身鉴权未验证，不能称目标安全。
- 使用更新后的本功能再次匿名复测该目标，13/13项HTTP402并匹配业务登录拦截，任务 `c7ef3116-d22f-49dc-8568-2b06bb57982a`；API、页面汇总和13条详情及xlsx/docx均为 `BLOCKED`，结论“本次未复现：全部检查端点的匿名访问已拦截”。报告ZIP/XML与openpyxl/python-docx读回通过，隔离18085服务验收后已退出。
- 报告ZIP/XML及openpyxl/python-docx读回通过，摘要、13条脱敏详情和整改说明齐全。打包渲染器报 `LibreOffice soffice.exe was not found on PATH`，实际Office排版仍未视觉验收；未安装软件替代该验收。
- 用户授权后，在18085隔离页面检查了13项业务拦截结果、端点详情、Word/Excel/所选目标及列选择；模式切换清空测试凭据，刷新接续同一任务。未在浏览器实际下载或清空结果，二进制导出和清空行为由HTTP/回环测试覆盖。验收服务结束后关闭，不替换用户18081服务。
- 真实Druid独立登录、不同Druid版本/反向代理、真实HTTPS受保护目标、100目标长期并发与DNS重绑定风险仍需专门验收。回环夹具不等于这些真实场景通过。本轮无新增依赖、未改变系统网络、未提交或推送Git。

## 11. 阶段6：KylinOS运维策略

### 实现与依赖

`KylinOpsTool.vue` → `useKylinOps` / `kylinOps.js` → `KylinOpsController` / `KylinOpsService` → `kylin`包。保持既有分层，独立管理SSH连接和策略任务，不复用插件生命周期或业务平台凭据。用户批准后新增Apache MINA SSHD `sshd-core`与`sshd-sftp` 2.20.1，使用现有Java21与默认NIO2传输，无额外Provider。

七项策略及边界：

| 策略 | 已接入能力 | 限制 |
| --- | --- | --- |
| 激活 | 中英文状态识别、按状态显示到期时间；阶段7增加二维码密钥激活和xlsx导出 | 不根据日期推断激活；真实微信扫码及许可激活验收待完成，详见第12节 |
| 定时重启 | cron、固定root脚本、独立LightDM自动登录片段、清理服务、备份恢复 | 部署不立即重启；默认带自动登录，最多等待指定用户X11会话60秒后清理；真实启动时序待验收 |
| 日志 | 固定归档与/tmp保留期，journal vacuum；当前数量/容量统计；部署与立即清理分开 | 不截断活跃日志；立即清理只安装脚本并执行、不写cron；已删除内容不可恢复，数量仅预览快照 |
| VNC | x86_64资产摘要、X11/LightDM预检、服务部署/启停/恢复，stdin之外单独生成DES兼容口令文件 | 默认loopback；LAN地址显式填写；8字符ASCII是传统格式限制，不代表强加密；没有aarch64版，原资产上游签名未取得 |
| openGauss | 用户模板生成限制来源版本、固定目录/属主与6.0.1产品预检、备份替换、独立reload | 模板其他参数也整体替换；不自动restart；sha256需要已有用户具备相应口令摘要；真实配置语义/认证生效待验收 |
| 组件漏洞 | 固定用户补丁的版本扫描、确认后SFTP上传/dpkg安装、安装后独立验证 | Kylin V10 amd64，健康且未hold的旧版本已安装组件；无独立CVE映射，不提供软件包回滚 |
| 系统优化 | 14类状态扫描，逐项选择、停用mask或移除执行位、后置验证与原状态恢复 | 审计/更新/激活/存储/混合自启动只读，不杀进程，现有进程退出后才能验证执行位策略实际影响 |

### 连接、计划和数据

2026-11-26激活检测修正：SSH执行层为分类sudo错误设置`LC_ALL=C`，激活命令继承该环境后实际返回`System is not activated.`；旧解析仅支持中文，因此退出0仍显示UNKNOWN。现兼容目标命令中已核对的英文完整行`System is activated.`和`System is not activated.`，保留`系统未被激活`、`系统未激活`、`系统已激活`和`系统激活`中文输出。仍要求退出0且已激活/未激活标记不矛盾；试用到期时间、技术服务到期时间均不作为激活状态依据。UNKNOWN日志仅记录退出码和状态标记，不记录原始输出。

本次先用回归测试复现英文未激活输出误判，修复后Kylin相关24项测试通过，包含用户两张截图对应中文输出、两种英文状态、非零退出、混合语言矛盾及只有日期的输出；日志为`.runtime/activation-regression-before.log`和`.runtime/activation-regression-after.log`。使用已有信任指纹对授权测试机`192.168.31.126`只读复测，相同命令退出0，结果由UNKNOWN变为NOT_ACTIVATED；未执行激活或系统变更。已激活目标的中文输出由截图回归覆盖，未连接该已激活目标实测。测试夹具关闭时仍可见已记录的Windows NIO2线程池关闭告警，测试断言无失败。

同日补充到期时间展示：沿用`Finding.detail`，未激活显示`试用期到期时间：YYYY-MM-DD`，已激活显示`技术服务到期时间：YYYY-MM-DD`；结果表将“证据 / 说明”简化为“说明”，“activation”显示为“系统激活”。日期兼容中文`试用到期时间`/`试用期到期时间`/`技术服务到期时间`及在目标命令中核对的英文`Expiration date of trial`/`Expiration date of technical service`。仅展示对应状态的明确有效日期；缺失、无效或多个日期互相冲突时显示“未获取”，不混用另一类日期，不改变已识别的激活状态；UNKNOWN显示“未获取明确激活状态”。Java相关25项、前端53项测试通过，前端构建成功；日志为`.runtime/activation-expiration-{java,frontend,build}.log`。授权未激活测试机只读复测返回NOT_ACTIVATED及试用期到期时间`2026-07-29`，记录为`.runtime/activation-expiration-readonly.log`；已激活日期展示由截图及英文夹具覆盖。

2026-11-25交互简化：七项功能直接切换，使用扫描/部署/卸载/验证等独立按钮。变更按钮自动请求计划并打开集中确认弹窗，内容/摘要默认折叠；最终确认仍由用户点击。sudo密码在成功扫描或生成计划后仅保留于前端组件闭包，绑定当前SSH连接；后续扫描与部署直接复用。15分钟未成功扫描或生成计划、断开/更换连接、sudo失败、刷新或离开页面后清除，提供手动清除按钮。取消计划不清除已验证的连接凭据；部署仍由用户确认。指定历史备份恢复移到高级折叠区。

`UNINSTALL`沿用`POST /plans`和`POST /executions`，无需提供备份ID；恢复本工具首次变更前的状态。按主机、端口、指纹及功能聚合历史记录，为每个文件/服务保留首次状态与对应远程备份来源，按最新回执检查外部冲突。多次部署、不同操作范围都不会只恢复最近版本。明确未派发变更的失败记录保存`.unchanged`标记并跳过；缺少回执且没有该标记时拒绝自动卸载。该行为不恢复已删除日志或已发生的重启，系统优化对应恢复所有本功能已管理项。

首次密钥探测拒绝进入认证，仅返回公开SHA256指纹。经可信渠道核对后显式保存host:port信任；认证严格固定该指纹，密钥变化拒绝连接。不读取本地私钥、不修改全局known_hosts。SSH密码认证后移除identity，不保存；sudo密码仅经stdin传入`sudo -k -S`。日志只记录目标、任务/计划ID、功能、状态和错误码，不保留远程原始stderr或秘密。

2026-11-25状态读取故障修正：原实现将所有非零退出都转成`KYLIN_STATE_PROBE_FAILED`且丢弃stderr，历史日志无法确认该次失败的具体原因。现将有界stderr仅用于内存中的白名单分类；sudo诊断使用`LC_ALL=C`，缺少密码、认证失败、sudo授权不足、TTY限制分别返回`KYLIN_SUDO_PASSWORD_REQUIRED`、`KYLIN_SUDO_AUTH_FAILED`、`KYLIN_SUDO_FORBIDDEN`、`KYLIN_SUDO_TTY_REQUIRED`。状态读取工具缺失、动态库、systemd及访问权限错误也分别提示；无法识别时保留通用错误和退出码，日志不回显原始远程输出。sudo默认使用当前SSH用户的密码，前端明确标注用户名，输入框提交后清空，验证过的sudo凭据在当前连接内临时复用；不自动复用SSH密码或放宽远程授权。

本轮使用项目中已有的主机信任记录固定指纹，在指定`192.168.31.126`测试机以`jzq`只读验证：sudo后UID为0；重启、日志、VNC、openGauss、系统优化的状态脚本退出0，并通过完整性与格式验证（状态项分别8/3/4/2/36）。正确凭据下未复现旧故障，不能从历史通用错误断言用户当时输入错误。本次未执行部署、卸载、清理、服务启停或重启；不修改目标sudo或动态库配置。后端更新需重启原服务使新错误分类生效，再从页面重新连接并扫描。

本次增量验证：前端48项、Java定向回归47项（其中Kylin15项）通过，Vue构建及JAR打包成功。覆盖sudo输入为空、错误密码、sudo授权/TTY拒绝、远程stderr不回显、普通命令失败分类，以及前端预检失败后清空密码且不执行。记录位于`.runtime/phase6-probe-frontend.log`、`.runtime/phase6-probe-build.log`、`.runtime/phase6-probe-package.log`、`.runtime/phase6-probe-readonly.log`。SSH夹具关闭期间仍可见已记录的Windows NIO2线程池关闭告警，本轮未修复，不等同全量环境无异常。

一个连接、一个写任务；空闲15分钟断开，页面后台轮询不续期；计划5分钟一次有效，任务摘要保留30分钟/最多20项。连接/认证各15秒，普通命令30秒；立即清理300秒。每个stdout/stderr各256KiB。SFTP逐块检查取消，单请求30秒，上传60秒加当前在途请求等待；不是瞬时强制取消。SSH连接底层读超时16分钟，命令期限独立。

所有POST不自动重放，只有GET可恢复一次401。刷新只读取当前连接和任务，不发起新写操作。计划在提交时消耗，执行前及上传后核对状态；断线/超时区分FAILED、PARTIAL和UNKNOWN_REMOTE_STATE，取消不能承诺撤销已派发操作。

本机`<toolhelper.data-root>/kylin/known-hosts/`保存信任，`operations/`保存原状态和变更后状态索引，不含密码。默认数据根仍在项目`.runtime/local-data`。远程备份固定`/var/lib/toolhelper/operations/<UUID>/`，root所属0700目录/0600文件；临时SFTP目录`/tmp/toolhelper-<UUID>/`为0700，只清理本任务固定文件。未取得变更后状态回执时不盲目生成恢复计划，外部修改冲突拒绝覆盖。恢复原文件内容、属主/权限、服务启用和运行状态，不固定恢复成enable；异常/瞬态服务状态会拒绝变更。

### 用户提供补丁：扫描、修复与验证

`KylinPatch`使用内置`kylin-offline-upgrade_1.0.0.33-0k1.11_amd64.deb`，178172字节，SHA256=`92fbdd5421a9a0783c123a30005f87f007feff0f3a5b45424ebcb32823e7fdc8`。来源为用户本轮指定目录，未新增下载源或第三方依赖；包内元数据与摘要均已核对。摘要证明本次分发字节与提供文件一致，不代替厂商签名验证，尚无独立厂商公告/CVE映射。

扫描与验证均重新通过SSH读取状态，无需sudo，使用目标`dpkg --compare-versions`排序版本。查询错误不会当作未安装；验证要求目标为Kylin V10 amd64、组件完整配置且包审计正常、版本达到提供基线、`kylin-offline-upgrade.service`为loaded/active/enabled。`PATCH_REQUIRED`只表示低于提供的补丁版本，`PATCH_VERIFIED`只表示上述检查通过，不代表所有漏洞已修复。未安装、包异常、管理员hold、系统/架构不匹配以及版本已达到或更高时拒绝修复，避免全新安装或降级。

点击“修复”生成`REPAIR`一次性计划，展示当前/目标版本、固定摘要、服务影响及限制；用户确认后才派发。执行前复查计划状态，经SFTP上传到随机0700暂存目录，再复制到root所属0700目录中的0600快照。对root副本核对SHA256及`dpkg-deb`实际Package/Version/Architecture，再运行`dpkg --no-act --install`。预检或状态复查失败不派发安装。通过后执行`DEBIAN_FRONTEND=noninteractive dpkg --force-confold --install`，命令期限300秒；保留现有conffile，不自动安装依赖、运行apt修复或重启系统。

补丁维护脚本会更新桌面/MIME关联、启用并重启离线升级服务，可能中断正在执行的离线升级，须安排维护窗口。维护脚本也可能修改conffile之外的文件；退出0后仍重新检查包和服务。失败/断线/超时可能部分生效，不自动重试或宣称回滚；“验证”可在之后重新读取当前状态。

本机`kylin/operations/<UUID>.patch-before.json`、`.patch-after.json`及失败时的`.patch-failed.json`记录目标/指纹、时间、补丁摘要和包/服务状态，不含密码；它们是安装记录，不进入普通文件备份恢复列表。软件包升级不提供一键卸载/降级。只清理本任务固定临时文件；安装命令超时、断线或取消且未返回时，保留root副本，避免删除仍被dpkg读取的文件。此时可在`/var/lib/toolhelper/operations/<安装记录ID>/patch.deb`人工核对，不应盲目重装。

2026-11-25增量验证：Java定向回归54项（Kylin原15项、补丁7项及HTTP/过滤/MAC/Druid/导出32项）、前端53项通过，Vue生产构建与JAR打包成功。补丁测试通过内嵌SSH/SFTP实际上传固定字节，覆盖确认/重放、状态漂移、hold/新版本拒绝、预检失败不安装、安装部分失败不重试、安装后版本未变化/服务异常不能判成功；Bash夹具实际执行状态与校验脚本，检查摘要、包元数据及预检分支。日志为`.runtime/phase6-patch-{package,frontend,build}.log`。这些隔离测试不代表真实维护脚本安装已验收；Windows NIO2关闭告警仍保留。

使用已有固定主机指纹，只读检查授权目标`192.168.31.126`：Kylin Desktop V10SP1/amd64，组件`install ok installed`、版本`1.0.0.33-0k1.9`、dpkg审计CLEAN，服务loaded/active/enabled；扫描返回PATCH_REQUIRED/PATCH_NOT_VERIFIED。已确认目标dpkg支持no-act、audit、confold，记录位于`.runtime/phase6-patch-readonly.log`。本轮未向真实目标上传、安装补丁或执行清理，真实修复仍由用户在页面确认后实施。

18086隔离页面已检查扫描/修复/验证按钮、旧版本结果、验证重新读取时间、修复确认弹窗中的服务影响/版本/摘要以及取消返回。页面夹具没有SSH或安装能力；截图为`.runtime/phase6-patch-confirm.jpg`。验收临时标签与夹具服务已关闭，未替换用户18081后端服务。

### HTTP接口

均以`/api/v1/kylin`开头，沿用本机会话、Host/Origin、16KiB请求体及严格JSON字段校验。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/capabilities` | 七项策略、14类优化、资产摘要/状态和限制 |
| POST | `/host-key-probes`、`/host-key-trust` | 无凭据探测、明确确认信任 |
| POST | `/connections` | 认证建立连接 |
| GET | `/connections/current` | 当前连接和系统信息 |
| DELETE | `/connections/{id}` | 空闲时断开 |
| GET | `/connections/{id}/backups` | 当前目标与密钥匹配的备份索引 |
| POST | `/scans`、`/plans` | 固定策略只读检测、变更预览 |
| POST | `/executions` | 一次性计划与confirmed确认，202创建任务 |
| GET | `/tasks/active`、`/tasks/{id}` | 当前任务及结果 |
| DELETE | `/tasks/{id}` | 请求停止后续步骤 |

### 2026-11-25验证与待办

- 交互简化增量验证：前端47项通过；Kylin13项及相关模块合计45项Java定向回归通过，生产构建/JAR打包通过。覆盖直接操作只准备预览、取消/失败不执行、同次sudo免二次输入、重复部署后首次状态恢复、跨范围备份来源与外部冲突。浏览器通过18086本地夹具检查定时重启直接按钮、部署/卸载确认及取消返回，夹具没有SSH能力；页面截图为`.runtime/phase6-simple-operations.jpg`。夹具及临时标签已关闭。本轮未操作真实目标。

- 阶段6测试11项、HTTP/请求过滤/MAC/Druid/导出合计43项定向回归通过；前端44项、Vue生产构建和JAR打包通过，日志在`.runtime/phase6-*`。
- 内嵌真实SSH/SFTP覆盖主机密钥错误前不发凭据、错误密码、sudo stdin、实际文件上传、退出7、输出上限、超时和派发前取消。执行夹具覆盖状态变化拒绝写、确认门槛、计划重放拒绝、应用失败PARTIAL、回执、恢复冲突和原执行位恢复。执行夹具不代表真实Linux策略命令已验收。
- 已安装Git Bash对生成的重启/清理/状态/优化应用及恢复脚本执行`bash -n`，未运行这些脚本的远程副作用。真实`systemd-analyze verify`、LightDM生效时序、openGauss语义/认证与reload、VNC实际画面和日志删除均待真机验收。
- Bash夹具实际执行只读状态脚本，覆盖systemd服务不存在且查询退出非零、其他查询失败中止；后台读取备份不续期SSH的断言通过。夹具不访问宿主机systemd或目标服务。
- Windows NIO2在密钥拒绝/错误认证的连接关闭测试中仍偶发后台`IllegalStateException: Executor has been shut down`，等待关闭后仍有复现。测试断言、后续连接与进程退出正常；根因和长期连接稳定性尚未确认，不隐藏该诊断。
- 初版历史页面验收：用户授权后在18086隔离页面检查七项功能、重启默认自动登录、日志保留期/独立立即清理、VNC默认回环、openGauss独立reload、当时的补丁基线限制及14项优化的只读禁用状态；未连接时变更按钮禁用，底部显示“KylinOS：SSH未连接”。初版仅覆盖未连接状态和表单交互，未在浏览器执行远程策略；后续补丁增量验收见上文。
- 初版按用户要求未连接真机，后续已使用项目固定指纹进行上述只读检查，未执行目标写入、服务启停、日志清理或重启。原electerm全量Windows目录移动异常仍是前阶段待办，本轮没有宣称全量已解决。

## 12. 阶段7：KylinOS密钥激活

更新日期：2026-11-26。

### 实现范围

- 页面按钮统一为“激活检测”。结果六列为检查对象、结果、说明、服务序列号、激活密钥、主机SN码；说明按状态展示试用期或技术服务到期时间。密钥按用户回复明文展示。
- 独立安全提取 `/etc/.kyinfo` 的 KEY/key，不执行文件内容；缺失、无法读取或重复/无效字段显示未获取，不影响独立激活状态判断。
- 激活前重新检测，只有明确未激活才继续；已激活目标不提供再次激活、续期或取消激活。
- 表单要求变电站名称、服务序列号、原始激活密钥；主机SN由目标系统自动绑定，无须手工输入。序列号预填目标KEY；用户确认：**差异提示后允许明确确认继续**。
- Java沿用Apache MINA SSHD，固定PTY运行 `sudo kylin-system-verify`。原始激活密钥、变电站名称和SN留在当前页面，不发送给激活终端；激活时只传服务序列号和微信生成的激活码。
- 在固定bash子进程设置 `QRCODE_TYPE=XPM`，严格解析目标qrencode产生的两色XPM像素，由JDK ImageIO转换PNG供页面显示、放大；不自行生成注册载荷，无新依赖。
- 二维码出现后保持原进程/通道，最长等待10分钟。用户微信扫码并输入原始密钥取得激活码，回填后明确提交，服务才发送回车继续。任务互斥、一次提交，响应丢失只查询原任务，不重放POST。
- 新版按提示分别输入序列号和激活码。已核对的旧版命令自动带内置序列号，不提示输入序列号；仅与表单值一致时提交激活码，不一致即停止，避免向激活码提示误送序列号。
- 只有已核实成功标记 `[system_real]11, ret=0`、退出0且后置检测已激活才判成功。拒绝、未知提示、断线或超时不自动重试。提交后取消不能保证撤销许可变更。
- sudo沿用同一页面缓存，验证成功后当前连接内复用；清除、到期、失败、连接改变和离页释放引用。
- XLSX固定五列：变电站名称、服务序列号、激活密钥、激活码、主机SN码。复用TableExport、按文本写入；允许检测后补录，主机SN列注明“由目标系统自动绑定”，不要求手工填写。只保留当前页面当前连接一条记录，离页/断开清除，不写历史台账或浏览器存储。

### 分层与接口

`KylinOpsTool.vue` / `KylinActivationDialog.vue` → `useKylinOps.js` / `kylinOps.js` → `KylinOpsController` / `KylinOpsService` → `KylinActivationExecutor` / `KylinActivation` / `KylinSshClient`。

均位于 `/api/v1/kylin`，沿用本机会话、Host/Origin、严格JSON和禁止缓存策略：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/scans`，feature=activation | 状态、到期说明及KEY |
| POST | `/activation-tasks` | confirmed确认后启动任务；serialDifferenceConfirmed表示差异已确认 |
| GET | `/activation-tasks/current`、`/activation-tasks/{id}` | 查询原任务，不触发激活 |
| GET | `/activation-tasks/{id}/qr` | 当前任务PNG，失效后拒绝读取 |
| POST | `/activation-tasks/{id}/continue` | 一次性提交生成的激活码 |
| DELETE | `/activation-tasks/{id}` | 停止后续步骤，关闭专用通道 |
| POST | `/activation-export` | 内存生成五列xlsx，不执行目标命令 |

二维码和终端输出不落盘，DTO默认字符串脱敏，日志仅记录任务ID、状态和错误码。明文导出由用户明确点击确认。

### 本次验证

- 前端 `npm test`：58项通过，其中Kylin相关19项；覆盖二维码前不继续、只发送生成激活码、丢失响应不重发、序列号差异拒绝/确认、取消原任务及连接变更清除记录。
- Java定向33项通过：KylinActivationTest 7项、KylinOpsTest 18项、KylinPatchTest 7项、HttpContractTest 1项；覆盖内嵌真实SSH交互、中文分片提示、免密sudo、错误密码、等待/取消、重复提交、旧版内置序列号差异、无效二维码、未知远程结果、文本XLSX及HTTP保护。
- Vue生产构建、JAR打包通过。打包结果见项目 `.runtime/phase7-package-final.log`；测试日志为 `.runtime/phase7-{frontend-suite,java-final,build-final}.log`。
- 只读核对目标系统新旧激活脚本及qrencode 4.0.2，使用公共示例URL验证目标XPM格式；未运行真实 `kylin-system-verify`，未生成真实注册二维码，未提交真实序列号或激活码，不改变目标许可状态。
- Windows NIO2测试夹具关闭时仍会出现历史 `Executor has been shut down` 后台告警，断言及进程退出正常，本轮未修复该已有问题；不能据此宣称长期连接稳定性已验收。

### 待真实验收

1. 启动真实激活程序至二维码后取消的测试授权仍待回复；尚未验证该版本完整PTY流程。
2. 微信实际扫描Web显示的真实注册二维码、获取生成激活码及真正激活，均未执行；隔离测试不替代许可变更验收。
3. 浏览器页面验收访问 `http://127.0.0.1:18087/tools/kylin` 曾被自动审批拒绝，理由是该本机来源尚未授权；已发起授权问题，未收到回复前不绕过访问。页面视觉/交互仍待验收。
4. 目标版本或输出不受支持时返回明确错误，不自行跳过二维码，也不降级为盲目输入。

### 使用

更新后重启Java后端；开发环境继续 `npm run dev`，同页重新连接SSH。进入系统激活功能，先激活检测，再按弹窗完成扫码及提交。导出应在断开或离开页面前完成。当前实现未提交或推送Git。
