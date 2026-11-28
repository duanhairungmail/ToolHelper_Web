# ToolHelper_Web

调试工具助手 Web 版，将远程连接、数据库连接、接口验证、设备调试及运维工具集中到统一工作台。

项目基于 Vue 3 和 Vite，采用 Composition API、Vue Router 和组件化布局。已搭建 13 个工具入口；阶段 1 接入 Java 本机服务与 electerm，阶段 2 接入 DBX 插件管理及 MySQL/PostgreSQL 带参连接，阶段 3 接入极早期接口验证、设备与 MQTT 信息、自动检测和表格导出，阶段 4 接入设备 MAC 查询与台账管理，阶段 5 接入 Druid 监控暴露与访问控制检测，阶段 6 接入 KylinOS SSH 运维策略与计划执行，阶段 7 接入二维码密钥激活与激活信息导出，阶段 8 接入本机串口调试，阶段 9 接入两种极早期 Modbus RTU 协议的模拟验收实现。

> 已实现工具的具体范围与验收限制见下表。数据库功能已使用隔离 MySQL/PostgreSQL 实例验证；接口验证已使用获授权的局域网目标实测登录、13 个 GET、设备/MQTT 查询及六类表格导出；MAC 查询已核对两个授权目标的 IP/MAC。Druid 按历史未授权漏洞复测，授权目标的 13 个端点均被业务平台登录拦截，结论为“本次未复现：全部检查端点的匿名访问已拦截”。外挂连接的实际认证和会话结果在外挂中查看；远程四协议真实服务验收仍待完成。Node-RED、AES 仍为原型，AES 页面尚未实现真正的 AES 加解密。

当前外挂功能仅支持 **Windows x64 当前登录用户的桌面会话**。Java、浏览器和外挂必须位于同一台电脑；将 Java 部署到远端服务器不能打开浏览器所在电脑的外挂。运行、接口及验收说明见 [Java 本机服务文档](backend-java/README.md)。

## 技术栈

| 项目 | 当前实现 |
| --- | --- |
| 前端框架 | Vue 3.5，单文件组件与 `<script setup>` |
| 路由 | Vue Router 4，HTML5 History 模式 |
| 构建工具 | Vite 7、`@vitejs/plugin-vue` 6 |
| 开发语言 | JavaScript，ES Modules |
| 全局状态 | Vue `reactive` + `provide/inject`，未引入 Pinia |
| 样式 | 原生 CSS、CSS 变量、Grid/Flex 和媒体查询 |
| 请求基础 | 原生 `fetch`、内存 session、业务错误码与请求标识 |
| 本机后端 | Java 21、Maven、Spring Boot 4.2.0 |
| 插件解压 | Apache Commons Compress 1.28.0，TAR.GZ / ZIP |
| 任务与存储 | 单任务互斥、JSON 元数据、切换记录；不依赖数据库 |
| 依赖管理 | npm，使用 `package-lock.json` 锁定依赖 |

项目当前没有引入 UI 组件库或额外的业务运行时依赖。

## 工具入口与实现状态

以下状态以当前 Vue 应用源码为准。全部入口均可通过侧边栏访问，默认页面为 `/tools/remote`。

| 工具 | 路由 | 当前实现 |
| --- | --- | --- |
| 远程外挂连接 | `/tools/remote` | Java 插件管理、真实状态与任务轮询、四协议参数传递；当前适配 electerm 5.12.6，真实协议连接待验收 |
| 数据库外挂连接 | `/tools/database` | Java 管理 DBX 下载、启动、更新、删除、打开目录；MySQL/PostgreSQL 带参连接，密码显式选择本次传递 |
| 极早期接口验证 | `/tools/api` | Java 登录、固定接口串行验证、全量设备ID/MQTT主题查询、Cron自动检测、清空日志、当前表格xlsx/docx导出；不建立MQTT订阅连接 |
| 极早期Modbus协议模拟 | `/tools/early-modbus-mqtt` | Java 连接 MQTT 3.1.1 TCP Broker，多网关独立订阅 RTU 查询，按绑定设备应答 FC 01/03/04；操作说明见 backend-java/README.md |
| 获取设备 MAC 地址 | `/tools/mac` | Java 查询 Windows 本机 IPv4 邻居映射；JSON 台账编辑/保存、单行/批量通信、取消、复制、选择行列导出 xlsx/docx |
| Node-RED 可视化编排 | `/tools/nodered` | 通用控制台及日志占位；未接入 Node-RED 运行时或编辑器 |
| Druid 漏洞检测 | `/tools/druid` | Java 固定13项只读规则、显式批量/取消、脱敏证据、可选单组Druid凭据验证、按目标/列导出xlsx/docx；不覆盖全部CVE |
| KylinOS 运维策略 | `/tools/kylin` | Java SSH/SFTP、主机指纹、七项策略检测、变更预览/确认/备份/恢复；用户提供补丁的扫描/上传安装/验证、微信扫码密钥激活与xlsx导出，真实写入验收待完成 |
| 基本串口调试 | `/tools/serial` | Java 本机串口枚举、参数与流控、文本/HEX收发、校验、定时发送和有界内存报文；真实设备未验收 |
| 极早期 Modbus 调试 | `/tools/modbus` | Java 模拟串口实现申弘/南瑞怡和扫描、读取、轮询及确认式南瑞复位；真实设备未验收 |
| 群 Ping | `/tools/ping` | Vue 目标解析与任务进度页；Java 本机服务解析 IPv4/CIDR/末段范围、调用系统 Ping、取消任务并导出 CSV/XLSX；无 ICMP 回复只标“无响应”，不等同于离线 |
| Cron 表达式 | `/tools/cron` | Java 校验与生成：Spring六段/五段兼容子集、模板、字段解释、IANA时区与夏令时、未来执行时间分页预览及复制；只计算，不创建调度任务 |
| SQL 语句生成与格式化 | `/tools/sql` | Java MySQL/PostgreSQL 五类结构化生成、受限保真格式化、UTF-8导入/复制/.sql下载；不连接或执行数据库 |
| AES 加密/解密 | `/tools/aes` | 参数与输入输出表单；“加密”处理使用 Base64 编码表达式，未实现 AES 或解密 |

## 环境要求

- Node.js：满足 `^20.19.0 || >=22.12.0`，与当前 Vite 及其 Vue 插件的要求一致。
- npm：用于安装依赖和执行项目脚本。
- 外挂功能另需 Java 21、Maven 3.9+、Windows x64；本机须能访问对应官方 GitHub Releases，DBX 还需可用的 WebView2 Runtime。

可先检查本机环境：

```bash
node --version
npm --version
```

## 快速开始

```bash
git clone https://github.com/duanhairungmail/ToolHelper_Web.git
cd ToolHelper_Web
npm ci
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`，根路径自动跳转到 `/tools/remote`。另开终端在项目根目录启动 Java：

```powershell
mvn -f backend-java/pom.xml spring-boot:run
```

默认 API 为 `127.0.0.1:18081`，Vite 将 `/api` 代理到该服务；未启动 Java 时远程页面明确显示“本机服务不可用”。端口被占用时启动失败，不自动换端口。

升级 Java 功能后，需要启动新 Java 后端（已有实例则先重启），才能加载新增 API；仅刷新前端不能更新运行中的 Java 代码。数据库页面先下载插件，再填写类型、主机、端口、用户名及可选数据库名。MySQL 默认端口 `3306`，PostgreSQL 为 `5432`。

密码默认不传，DBX 可能继续要求认证。勾选“本次传递密码”后，密码随本次连接发给 DBX，发起时清空表单，不自动重试、不写入浏览器存储。**密码会进入 DBX 进程命令行，本机有权限的程序可能读取，URI 编码不是加密。** 不接受该限制时，请在 DBX 中完成认证。

当前 DBX 适配发行 tag `v0.6.72`，已校验官方便携包的实际版本为 `0.6.72-beta.20261119.2`；界面同时展示两者。更新或删除前需要退出 DBX，完整 `data`（包括存在时的密钥文件）会保留。版本白名单不代表未来任意发行包已经兼容。

插件、下载暂存、任务和日志默认保存在本项目的 `.runtime/local-data/`，日志为 `.runtime/local-data/logs/service.log`。VS Code 和 Maven 启动使用项目根目录作为工作目录；JAR 命令也需在项目根目录执行。

外挂版本检查及下载默认跟随本机系统代理规则：有适用的 HTTP 代理时使用代理，没有适用代理时直连，不要求用户安装代理软件。控制台和服务日志中的 `mode=HTTP_PROXY` / `mode=DIRECT` 表示实际选择的路由；代理软件自身的规则由该软件处理。配置与限制见 [下载网络策略](backend-java/README.md#下载网络策略)。

已有本地项目时，直接在项目根目录执行 `npm ci` 和 `npm run dev`。有意调整依赖后，应同步提交更新后的 `package.json` 与 `package-lock.json`。

### 阶段12：SQL 语句生成与格式化

访问 `/tools/sql`，在“SQL 生成”和“SQL 格式化”之间切换。页面采用浅色参数区与深色代码区，生成页左侧填写参数、右侧预览；格式化页左侧输入、右侧输出，窄屏上下排列。代码区提供行号与辅助着色，大结果以纯文本显示；复制和下载均使用服务返回的原始SQL。五类表单分别保留模块内存草稿，刷新清空，不保存 SQL 历史。原设计 demo 已移除。

- MySQL / PostgreSQL 支持 SELECT、单行 INSERT、带 WHERE 的 UPDATE / DELETE、受控 CREATE TABLE。值明确区分字符串、数值、布尔和 NULL；数值按字符串传输，保留大整数和精确小数。空字符串不自动转换 NULL。
- MySQL 转义选项须与目标库 `sql_mode` 一致；PostgreSQL 生成字符串使用 `E'...'`。名称按原样引用，不自动转换大小写；LIKE 区分普通文本“包含”与原始通配模式。UPDATE / DELETE 不提供无 WHERE 生成选项。
- 格式化支持关键字大/小写、2/4 空格缩进；按原 token 回放字符串、名称与注释，结构变化时明确拒绝。首版不支持存储过程、客户端命令、模板/参数占位符、hint/可执行注释及 MySQL 双引号语法；部分合法 SQL 因引擎规范化也会被拒绝。
- 支持 UTF-8 `.sql` 导入、复制、下载，以及生成结果送入格式化。原文最多256 KiB、50条语句、50000 token、64层括号，输出最多1 MiB；工作线程2、等待队列4、响应预算3秒。超时不返回迟到结果，不保证强制结束解析。

后端锁定 `com.alibaba:druid:1.2.28`，仅使用 SQL 文本解析与排版，未启用连接池或监控；Apache 2.0 许可及来源保留在后端 `META-INF`。工具不校验数据库表结构，也不保证任意数据库版本的执行兼容性。接口、边界和配置见 [Java 文档阶段12](backend-java/README.md#15-阶段12sql-语句生成与格式化)。

### 阶段7：KylinOS密钥激活

沿用 `/tools/kylin` 的 SSH 指纹和 sudo 临时缓存。点击“激活检测”，显示检查对象、结果、说明、服务序列号、激活密钥、主机SN码；未激活展示试用期到期时间，已激活展示技术服务到期时间。序列号从 `/etc/.kyinfo` 的 KEY/key 读取，激活密钥仅来自当前页面记录，无记录显示“无”。主机SN由目标系统在激活时自动绑定，不要求手工填写。

“密钥激活”先检测目标，明确未激活才开放流程。填写变电站名称、服务序列号和原始激活密钥后，确认启动 `sudo kylin-system-verify`。主机SN由目标系统自动绑定，不需要填写。序列号预填检测所得KEY；输入不同时提示差异，用户明确确认后继续。Java通过固定PTY命令接收目标 `qrencode` 的XPM像素并转换PNG供Web展示，不重建注册信息。用户用手机微信扫码、输入原始密钥、取得生成的激活码，再在同一弹窗提交；等待最长10分钟，期间保持原进程。原始密钥不会代替激活码发送给目标。

旧版命令直接使用内置序列号、不单独提示输入序列号；只有它与填写值一致才能提交激活码，否则停止并提示人工核对。已激活目标禁止再次激活；续期及取消激活仍需手动操作。提交只允许一次，响应丢失查询原任务，失败不自动重试。取消或断线不能撤销已发生的许可变更。命令返回已核实成功标记、退出0且重新检测已激活，才显示成功。

检测后可点击“导出激活信息”补录并生成 `.xlsx`，固定五列：变电站名称、服务序列号、激活密钥、激活码、主机SN码。密钥按用户要求明文展示/导出；主机SN列说明由目标系统自动绑定，不要求填写。当前连接只保留单条页面记录，断开、更换连接或离开页面清除，不保存历史台账。

阶段7已完成隔离SSH交互和接口测试；真实注册二维码的微信扫描与许可激活、浏览器页面验收仍待授权验证。目标命令脚本与qrencode XPM格式已只读核对，不能据此宣称真实激活已验收。技术边界及测试记录见 [Java文档第12节](backend-java/README.md#12-阶段7kylinos密钥激活)。

### 阶段6：KylinOS运维策略

打开 `/tools/kylin`。填写SSH目标，先探测并通过可信渠道核对主机指纹，再显式信任和连接。Java使用Apache MINA SSHD 2.20.1（用户已批准）直接连接，不加载本机私钥，也不复用外挂代理或其他工具登录态。SSH密码提交后清空；非root操作首次输入sudo密码，验证后在当前页面及SSH连接内临时复用，密码通过stdin传递，不进入shell命令或持久存储。

sudo默认验证当前SSH登录用户的密码，通常不是root密码。例如以`jzq`连接时，首次操作填写`jzq`的sudo密码；验证成功后，后续扫描与部署自动复用，免密sudo可留空。密码仅在当前页面内存中保留，15分钟未成功扫描或生成计划、断开/更换连接、sudo认证失败、刷新或离开页面后清除，也可点击“清除已记住密码”。未提供密码、认证失败、授权不足或要求交互终端会分别提示具体错误。2026-11-25已在指定Kylin测试机验证sudo提权及五组文件/服务状态读取；尚未据此确认真实部署、卸载或服务启停验收通过。

- 七项功能：系统激活只读检测；定时重启默认带临时LightDM自动登录；日志保留策略部署/立即清理；x11vnc部署/启停；openGauss配置替换/reload；组件补丁扫描/修复/验证；14类系统优化。审计、更新、激活校验、存储及混合自启动默认只读。
- 所有策略变更先生成具体计划，核对文件内容、权限、服务动作和影响后确认执行。计划5分钟内一次有效，执行前复查状态；断线、超时或部分失败不自动重试。恢复也需要预览和确认，目标被外部修改时拒绝覆盖。
- 页面采用功能页签和直接操作按钮：扫描、部署、卸载、验证；VNC另有启动/停止，日志另有立即清理。点击变更按钮自动准备确认弹窗，详情可展开，无需选择操作下拉框或手动生成预览。sudo密码在当前连接内临时复用；取消计划不清除已验证的连接凭据，部署仍须明确确认，不持久化密码。
- 卸载恢复到本工具首次变更前的状态，保留原有配置；后端按文件/服务追溯最初备份及最新回执，重复部署不会只退回上一版本。无变更记录、回执缺失或外部修改冲突时拒绝猜测卸载。系统优化对应“恢复优化”；高级入口仍可选择指定历史备份。
- 定时重启部署不会立即重启，cron按目标时区运行。触发时写入独立LightDM临时配置，新启动后等待指定用户X11会话、最多60秒后清理；真实自动登录时序尚待验收。清理服务故障可能残留配置，页面明确提示。
- 日志立即清理不安装定时计划；先统计固定范围数量/容量，不截断活跃日志，已删除内容不能恢复。VNC默认127.0.0.1，需明确填写LAN地址才改变监听，不自动开放防火墙。旧x86_64资产已固定摘要，目标运行依赖仍要检查，没有aarch64版。
- openGauss使用用户提供文件作为模板，限制监听和来源CIDR、移除trust；其他模板参数也整体替换，必须检查全文。仅适配已确认的openGauss 6.0.1和固定数据目录；替换不自动reload/restart，reload是单独动作。
- 漏洞检测支持“扫描 → 修复确认 → 上传安装 → 验证”。内置用户提供的`kylin-offline-upgrade_1.0.0.33-0k1.11_amd64.deb`，仅升级Kylin V10 amd64上健康、未hold且低于此版本的已安装组件。上传后核对SHA256、包元数据及dpkg预检；安装保留现有conffile，不自动安装依赖或重启系统。包维护脚本会启用并重启离线升级服务，请安排维护窗口。验证复查版本、包配置/审计和服务运行/自启；按提供的补丁版本判定，尚无独立厂商公告/CVE映射。软件包升级不提供一键回滚。

本机信任/备份索引位于项目数据根的`kylin/`，远程原文件备份位于`/var/lib/toolhelper/operations/<备份ID>/`。恢复不撤销已发生的重启、删除日志或进程内存变化。

2026-11-25最新增量验证：Java定向回归54项、前端53项通过，生产构建及JAR打包成功。SSH/SFTP隔离测试覆盖实际补丁字节上传、安装预检失败中止、一次性确认、版本未变化及服务异常不能判成功。授权目标只读检查显示当前版本`1.0.0.33-0k1.9`、包审计正常、服务运行且自启，低于提供的补丁；本轮未向真机上传或安装。真实systemd/LightDM/openGauss配置生效及远程写策略仍待验收。Windows NIO2夹具关闭时仍可见异步`Executor has been shut down`，测试断言及进程退出正常，不能据此认定长期连接稳定性已验收。详见 [Java文档第11节](backend-java/README.md#11-阶段6kylinos运维策略)。

### 阶段 5：Druid 监控暴露与访问控制检测

打开 `/tools/druid`，填写完整的监控根 URL 或 `index.html`；支持应用上下文、自定义监控根、HTTP/HTTPS 和 IPv4/DNS。批量模式每行一个 URL，最多 100 个，不展开网段、端口范围或自动猜测目录。检测对象为 Alibaba Druid 数据库连接池的监控端点。

- 默认匿名，只请求固定的 13 个只读端点；HTML 页面可访问不等于数据泄露，JSON 需匹配已适配结构及字段。页面显示识别、访问、规则风险和执行进度，失败或未知不会显示为安全。
- 对照业主表格中的 `Alibaba druid index.html未授权` 类型复测用户填写的服务。页面、逐端点详情和报告统一显示复测结论：仍存在匿名监控接口/页面暴露、本次匿名访问已拦截、路径未发现或无法判断。任一暴露优先报告；全部13项明确拦截才判本次未复现，部分超时、未知或未执行不得当作已修复。
- 可选单目标、单组 Druid 独立凭据验证，先跑匿名基线，再验证已识别的登录机制；不使用口令字典、不复用阶段 3 的业务 Token。密码提交后清空，不保存或自动重放；HTTP 明文传输需当次确认。
- Java 默认直连，不沿用插件下载代理；保留 TLS 校验和同源受限跳转。默认最多 4 个不同 origin 并发、同 origin 串行，请求 5 秒/目标 30 秒，每目标最多 16 次请求、响应体最多 1 MiB。
- 支持停止、进度、详情、复制整改建议、选择目标/列导出 `.xlsx` / `.docx`，可附脱敏端点详情。清空只影响本模块已结束结果，不调用目标重置。结果仅内存保留 30 分钟/最多 20 个任务，Java 重启清空且不自动重发。
- 业务登录后的访问能力与匿名暴露是不同结论。已适配的业务 402 拦截会单独标注，普通 402 仍保留 HTTP 异常；不会把业务网关拦截误写为 Druid 自身鉴权通过。

2026-11-25 验证：前端 39 项通过，Vue 构建通过。后端在402判定修正前全量111项无失败（2项可选外挂实包跳过）；修正后最新全量112项为109通过、1错误、2跳过，错误来自原有electerm恢复测试中的Windows目录移动 `AccessDeniedException`，未改动该模块。授权目标的全部13个端点均被业务平台登录拦截，本轮未发送目标凭据、未复现匿名数据暴露；不能据此宣称目标整体安全。报告已通过 ZIP/XML 和 openpyxl/python-docx 内容读回；当前环境缺少 LibreOffice，Office 实际排版仍待验收。最终定向回归、打包、接口及限制见 [Java 文档第10节](backend-java/README.md#10-阶段5druid-监控暴露与访问控制检测)。

复测结论补充验证：Java 32项定向回归、前端39项、生产构建和JAR打包通过。2026-11-25再次通过本功能检查授权服务，13/13项HTTP402且匹配业务登录拦截签名，汇总为 `BLOCKED`，页面和两类报告一致；本次匿名检查未复现历史未授权暴露，不将结论扩大为系统整体安全。使用更新功能前需重启Java服务。

### 阶段 4：设备 MAC 地址与台账

打开 `/tools/mac`，新增行并填写 IPv4，再点击行内“通信”或“全部通信”。Java 和浏览器应位于用户本机，Windows 需具备 Windows PowerShell 和 NetTCPIP 查询命令；无须安装 Access/ACE 或 JNA。

- 根据实际出站路由和接口查询完整 IP 的邻居映射，经过网关的跨网段目标明确提示不可用，不填入网关 MAC。支持本机接口地址；不支持主机名、IPv6、IP 范围和网段扫描。
- 一次一个批次，最多 1000 行、4 个不同 IP 并发、单目标截止时间 10 秒；相同 IP 合并查询，空 IP 跳过，错误保留在对应行。支持停止并清理实际子进程。
- 区分已观察映射、缓存映射与查询失败。缓存或历史值不能证明设备当前在线；代理 ARP 返回的地址也不保证属于目标物理网卡。
- 新增、编辑、删除及查询只更新内存草稿，点击“保存”才写入 `.runtime/local-data/device-mac/records.json`；保存使用版本校验，冲突不覆盖。查询时禁用编辑、保存和导出，离开提示未保存，切换工具保留草稿；刷新或关闭浏览器会丢弃未保存草稿。
- 复制 MAC/整行，按全部或所选行导出 `.xlsx` / `.docx`，可选择列，序号必选。MAC 与长序列号按文本导出。服务重启保留已保存台账、清空内存任务，不自动重发查询。

已通过两个授权目标的实际查询、1000 行台账保存、冲突/取消及导出内容检查；浏览器已检查台账显示、历史标注、草稿编辑、横向操作区和导出选项。恢复草稿时浏览器控制超时，重新加载交互及 Office 实际排版仍待验收。配置、接口和限制详见 [Java 本机服务文档](backend-java/README.md#9-阶段4设备-mac-查询与台账)。

### 阶段 3：极早期接口验证

打开 `/tools/api`，填写主机、端口及本次凭据后登录。凭据支持明文或旧版 AES-256-CBC，登录格式可选 POST JSON 或旧版查询参数，不会在失败后自动切换。默认 HTTPS；只有明确确认可信网络后才允许 HTTP，固定 AES 不能保护 HTTP 链路。

- 开始验证：默认 13 个 GET，三个复位/写入 POST 默认不选、手动执行前二次确认，自动检测始终禁止 POST。HTTP 200 不等于业务成功，页面区分业务码成功与业务未确认。
- 获取设备 ID / MQTT 主题：读取全部分页，仅保留页面所需字段；MQTT 主题查询不连接 MQTT broker。默认每页 100 条，上限 1000 页/100000 条，异常或超限不发布部分结果。
- 自动检测：5/6 字段 Cron，拒绝年份字段；Java 串行执行，上一轮未结束则跳过触发点，保留最近 10 个完成轮次统计。离开页面继续，Java 重启停止；Token 失效后停止并要求手动登录。
- 清空结果：按已确认约定只清日志，保留表格和任务。导出当前 Tab 的全部记录，支持真正的 `.xlsx` / `.docx`，不是仅导出当前显示的 50 行。

目标 Token 只存在 Java 内存，页面不显示 Token，密码提交后立即清空且不用于自动续登。同一 Java 进程一次只支持一个目标，换目标/失败登录/登出清除旧结果；刷新页面可接续当前任务。Java 访问验证目标时直连，外挂下载仍按原系统代理策略执行。

2026-11-24 阶段 3 验证：前端 **26 项通过**；Java **87 项中 86 项通过、1 项 electerm 可选实包测试跳过**，DBX 可选实包测试已运行；Vue 构建与 Java 打包通过。通过获授权的 HTTP 8088 目标实测登录、4 条设备/主题、13 个 GET（5 项已识别业务码成功，8 项仅确认 HTTP 成功），以及六类导出；浏览器确认 Excel 下载、清空只清日志与接口选择。未调用真实复位/写入 POST，真实 Token 到期和长期调度仍待验收；Office 排版尚未完成视觉验收。详细契约见 [Java 文档第 8 节](backend-java/README.md#8-阶段-3极早期接口验证)。

### 可用脚本

| 命令 | 用途 |
| --- | --- |
| `npm run dev` | 启动 Vite 开发服务 |
| `npm run build` | 执行生产构建，输出到 `dist/` |
| `npm run preview` | 本地预览已有的生产构建产物 |
| `npm test` | 使用 Node 内建 test runner 验证插件、接口验证、MAC及Druid的请求、任务、密码、选择与清空行为 |

开发及预览服务均监听 `127.0.0.1:5173`。API 代理额外检查客户端 loopback 地址；不要向局域网或公网暴露本机进程管理接口。Vite 已排除后端、缓存和运行数据目录的文件监视，避免 Windows 切换安装目录时被占用。

已添加前端请求/状态测试及后端业务/HTTP 测试，未新增 lint 或类型检查工具。自动化测试通过不等于远程认证、GUI 或实际服务已验收。

2026-11-23 阶段 2 验证：前端 17 项通过；后端 66 项中 65 项通过、1 项 electerm 可选实包验收跳过，本次 DBX 实包验收已运行；Vue 构建和 Java 可执行 JAR 打包通过。真实数据库验证使用项目内的独立测试实例，没有修改用户原有业务数据库。TLS 握手、真实跨发行版数据迁移、缺失 WebView2 等场景仍待验收。

## 项目结构

```text
ToolHelper_Web/
├── backend-java/                     # electerm / DBX / 接口验证 / MAC / Druid Java 本机服务
│   ├── pom.xml
│   └── src/{main,test}/
├── public/
│   ├── favicon.ico
│   └── logo.png
├── src/
│   ├── assets/
│   │   ├── logo.png
│   │   └── styles/main.css            # 当前应用实际加载的全局样式
│   ├── components/
│   │   ├── AppHeader.vue              # 当前工具标题、描述与顶部操作
│   │   ├── AppSidebar.vue             # 工具分组、搜索与导航
│   │   ├── AppStatusBar.vue           # 全局状态提示
│   │   ├── BasePanel.vue              # 公共内容面板
│   │   ├── DatabaseTool.vue           # DBX 管理、数据库参数及本次密码选项
│   │   ├── DruidScanTool.vue          # Druid 检测、进度、详情与报告导出
│   │   ├── LogDrawer.vue              # 公共日志抽屉
│   │   ├── RemoteTool.vue             # 真实远程外挂管理与连接表单
│   │   ├── SqlTool.vue                # SQL生成/格式化双页签与输入输出
│   │   └── ToolContent.vue            # 各工具的内容分支与交互逻辑
│   ├── composables/useLogDrawer.js    # 日志与抽屉状态复用
│   ├── composables/useManagedPlugin.js # 两个插件共用状态、任务接续与轮询
│   ├── composables/useRemotePlugin.js  # electerm 页面适配
│   ├── composables/useDatabasePlugin.js # DBX 页面适配
│   ├── composables/useDatabaseConnection.js # 连接字段与本次密码生命周期
│   ├── composables/useSql.js          # SQL内存草稿、请求取消与结果失效
│   ├── api/pluginClient.js            # 共用受控 API 与内存 session
│   ├── api/{remotePlugin,databasePlugin}.js # 固定插件 API 入口
│   ├── api/sql.js                     # SQL本机会话与文本处理API
│   ├── config/tools.js                # 工具定义与分组
│   ├── layouts/AppLayout.vue          # 工作台布局
│   ├── router/index.js                # 根据工具配置生成路由
│   ├── stores/app.js                  # 侧边栏与全局状态
│   ├── utils/
│   │   ├── format.js                  # SQL 字符串转义、时间格式化辅助函数
│   │   └── request.js                 # 基础 JSON 请求封装
│   ├── views/ToolView.vue             # 根据路由装配工具页面
│   ├── App.vue                       # 根路由出口
│   ├── main.js                       # 应用初始化
│   └── style.css                     # 旧版 iframe 容器样式，当前未引用
├── .env                              # 公共基础配置
├── .env.production                   # 公共生产配置
├── .gitignore
├── index.html                        # Vite HTML 入口
├── LICENSE
├── package.json
├── package-lock.json
├── tests/                            # Node 内建测试，无额外前端测试依赖
├── README.md
├── server.mjs                        # 遗留静态服务，默认 HTML 入口当前缺失
└── vite.config.js
```

`node_modules/`、`dist/`、`backend-java/target/`、`.m2-cache/`、`.runtime/`、日志和 `*.local` 文件已由 `.gitignore` 排除。真实用户数据、官方插件包和临时测试数据不应提交到 Git。

## 架构与开发入口

### 页面组织

`src/config/tools.js` 是工具名称、分组、关键词和页面文案的统一来源，路由与侧边栏都会读取它。

```text
tools.js ──→ router/index.js ──→ ToolView.vue
         └─→ AppSidebar.vue          ├─ AppLayout.vue
                                    └─ ToolContent.vue
```

所有工具路由当前共用 `ToolView.vue`，由路由的 `meta.tool` 确定当前工具，再通过 `toolId` 选择 `ToolContent.vue` 中对应的内容分支。

### 状态与公共能力

- `stores/app.js`：通过 `reactive` 定义共享状态，由入口注入；组件使用 `useAppStore()` 获取。当前只管理侧边栏开关与状态栏消息。
- `ToolContent.vue`：按工具ID装配独立组件，仍保留部分未接入服务的演示分支。
- `RemoteTool.vue` / `DatabaseTool.vue`：分别通过插件 composable 和 API 模块接入 Java；共用 `useManagedPlugin.js` 与 `api/pluginClient.js`。其他已实现工具使用各自组件、composable 与 API 模块。
- `SqlTool.vue` → `useSql.js` / `api/sql.js` → Java `SqlController` / `sql` 包：生成和格式化均由本机服务完成，不访问数据库；页面修改输入会使旧结果失效。
- Java 的 `plugin/common` 复用官方传输、安装事务、进程基础操作与管理流程；`plugin/electerm`、`plugin/dbx` 分别限定包结构和连接参数。两个插件共用数据根锁和任务互斥，发行缓存与资产选择隔离，同域名限流冷却共享。
- `useLogDrawer.js`：管理日志数组、展开状态、追加与清空操作；`LogDrawer.vue` 负责展示。
- `utils/request.js`：处理 JSON、超时和业务错误，保留错误码及请求标识；副作用请求失败不会自动重放。
- `assets/styles/main.css`：统一定义主题变量、布局、表单、面板、弹窗与响应式样式。

### 新增或完善工具

1. 在 `src/config/tools.js` 中维护工具配置；新增配置后会同步生成路由和侧边栏入口。
2. 在 `ToolContent.vue` 对应分支实现页面与交互，避免把演示状态提示当作实际业务结果。
3. 通用界面和可复用状态逻辑分别放入现有 `components/` 与 `composables/` 层。
4. 接入实际服务时，明确接口、错误处理及运行环境，再接入请求封装或所需的本地服务。
5. 执行生产构建并验证受影响工具，同时更新本 README 中的实现状态。

## 环境配置

当前 `.env` 与 `.env.production` 仅声明 `VITE_APP_TITLE`，源码尚未读取该变量；浏览器标签标题目前由 `index.html` 的 `<title>` 定义。

Vite 的 `VITE_` 前缀变量可进入前端产物，不应用于保存密码、令牌或私钥。本地专用配置可使用已被忽略的 `*.local` 文件，但不要将敏感值暴露为前端变量。

## 构建与部署

```bash
npm run build
npm run preview
```

仅预览界面时可使用 `npm run preview`。外挂功能推荐将前端构建产物打入 Java JAR，在本机同源访问：

```powershell
npm run build
mvn -f backend-java/pom.xml clean package
java -jar backend-java/target/toolhelper-local-service-0.1.0.jar
```

随后访问 `http://127.0.0.1:18081`。Maven 在资源阶段复制已有 `dist/`；修改前端后必须重新构建前端并打包 Java。需要单独托管前端时，静态页面仍须通过本机服务及受控代理调用 API，不能把本机能力直接部署成公网服务。

Windows 下重新打包前，先退出正在运行该目标 JAR 的 Java 实例，避免文件占用；不要结束其他 Java 进程。运行与构建需同时进行时，可从 JAR 副本启动隔离验收服务。

路由使用 HTML5 History 模式，服务器需要在直接访问 `/tools/remote` 等前端路由时回退到 `index.html`。例如，在静态站点根目录指向 `dist/` 的 Nginx 站点中，可配置：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

当前 Vite 未配置子路径部署的 `base`，路由也未指定子路径基址。如果部署在 `/ToolHelper_Web/` 等子路径，需要同时调整资源基址、路由基址及服务器回退规则。

### 遗留静态服务

`server.mjs` 是独立的遗留静态服务，仅提供 `public/` 中的文件，不提供业务 API，也不用于托管 Vue 的 `dist/` 构建产物。当前 npm 脚本均不依赖它，Vue 应用也不会通过 iframe 加载旧版页面。

该服务的根路径仍指向 `toolhelper-interface-demo.html`，但当前 `public/` 中已无此文件，因此直接启动它并访问根路径会返回 404。运行当前项目请使用上文的 `npm run dev` 或 `npm run build` 后的 `npm run preview`。

## 许可证

本项目采用 Apache License 2.0，详见 [LICENSE](LICENSE)。
