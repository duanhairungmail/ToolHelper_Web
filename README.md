# ToolHelper_Web

调试工具助手 Web 版，将远程连接、数据库连接、接口验证、设备调试及运维工具集中到统一工作台。

项目基于 Vue 3 和 Vite，采用 Composition API、Vue Router 和组件化布局。已搭建 13 个工具入口；阶段 1 接入 Java 本机服务与 electerm，阶段 2 接入 DBX 插件管理及 MySQL/PostgreSQL 带参连接，阶段 3 接入极早期接口验证、设备与 MQTT 信息、自动检测和表格导出，阶段 4 接入设备 MAC 查询与台账管理。

> 远程、数据库外挂、极早期接口验证和设备 MAC 台账已调用真实 Java API。数据库功能已使用隔离 MySQL/PostgreSQL 实例验证；接口验证已使用获授权的局域网目标实测登录、13 个 GET、设备/MQTT 查询及六类表格导出；MAC 查询已核对两个授权目标的 IP/MAC。外挂连接的实际认证和会话结果在外挂中查看；远程四协议真实服务验收仍待完成。其余工具仍主要是界面原型；AES 页面尚未实现真正的 AES 加解密。

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
| 获取设备 MAC 地址 | `/tools/mac` | Java 查询 Windows 本机 IPv4 邻居映射；JSON 台账编辑/保存、单行/批量通信、取消、复制、选择行列导出 xlsx/docx |
| Node-RED 可视化编排 | `/tools/nodered` | 通用控制台及日志占位；未接入 Node-RED 运行时或编辑器 |
| Druid 漏洞检测 | `/tools/druid` | 通用控制台占位；未实现漏洞检测 |
| KylinOS 运维策略 | `/tools/kylin` | 通用控制台及日志占位；未实现系统运维操作 |
| 基本串口调试 | `/tools/serial` | 连接状态切换、发送内容校验和本地发送记录；未访问真实串口 |
| 极早期 Modbus 调试 | `/tools/modbus` | 参数、设备列表、解析结果及原始报文展示区域；未实现通信 |
| 群 Ping | `/tools/ping` | 目标输入和简单数量演示；未解析完整目标范围或执行 Ping |
| Cron 表达式 | `/tools/cron` | 通用控制台占位；未实现表达式生成或执行时间预览 |
| SQL 语句生成与格式化 | `/tools/sql` | 简单 SELECT 条件添加与字符串生成；格式化、复制等功能待实现 |
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

升级到阶段 2/3/4 后，需要启动新 Java 后端（已有实例则先重启），才能加载新增 API；仅刷新前端不能更新运行中的 Java 代码。数据库页面先下载插件，再填写类型、主机、端口、用户名及可选数据库名。MySQL 默认端口 `3306`，PostgreSQL 为 `5432`。

密码默认不传，DBX 可能继续要求认证。勾选“本次传递密码”后，密码随本次连接发给 DBX，发起时清空表单，不自动重试、不写入浏览器存储。**密码会进入 DBX 进程命令行，本机有权限的程序可能读取，URI 编码不是加密。** 不接受该限制时，请在 DBX 中完成认证。

当前 DBX 适配发行 tag `v0.6.72`，已校验官方便携包的实际版本为 `0.6.72-beta.20261119.2`；界面同时展示两者。更新或删除前需要退出 DBX，完整 `data`（包括存在时的密钥文件）会保留。版本白名单不代表未来任意发行包已经兼容。

插件、下载暂存、任务和日志默认保存在本项目的 `.runtime/local-data/`，日志为 `.runtime/local-data/logs/service.log`。VS Code 和 Maven 启动使用项目根目录作为工作目录；JAR 命令也需在项目根目录执行。

外挂版本检查及下载默认跟随本机系统代理规则：有适用的 HTTP 代理时使用代理，没有适用代理时直连，不要求用户安装代理软件。控制台和服务日志中的 `mode=HTTP_PROXY` / `mode=DIRECT` 表示实际选择的路由；代理软件自身的规则由该软件处理。配置与限制见 [下载网络策略](backend-java/README.md#下载网络策略)。

已有本地项目时，直接在项目根目录执行 `npm ci` 和 `npm run dev`。有意调整依赖后，应同步提交更新后的 `package.json` 与 `package-lock.json`。

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
| `npm test` | 使用 Node 内建 test runner 验证插件和接口验证的请求、任务、密码、选择与清空行为 |

开发及预览服务均监听 `127.0.0.1:5173`。API 代理额外检查客户端 loopback 地址；不要向局域网或公网暴露本机进程管理接口。Vite 已排除后端、缓存和运行数据目录的文件监视，避免 Windows 切换安装目录时被占用。

已添加前端请求/状态测试及后端业务/HTTP 测试，未新增 lint 或类型检查工具。自动化测试通过不等于远程认证、GUI 或实际服务已验收。

2026-11-23 阶段 2 验证：前端 17 项通过；后端 66 项中 65 项通过、1 项 electerm 可选实包验收跳过，本次 DBX 实包验收已运行；Vue 构建和 Java 可执行 JAR 打包通过。真实数据库验证使用项目内的独立测试实例，没有修改用户原有业务数据库。TLS 握手、真实跨发行版数据迁移、缺失 WebView2 等场景仍待验收。

## 项目结构

```text
ToolHelper_Web/
├── backend-java/                     # electerm / DBX / 接口验证 Java 本机服务
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
│   │   ├── LogDrawer.vue              # 公共日志抽屉
│   │   ├── RemoteTool.vue             # 真实远程外挂管理与连接表单
│   │   └── ToolContent.vue            # 各工具的内容分支与交互逻辑
│   ├── composables/useLogDrawer.js    # 日志与抽屉状态复用
│   ├── composables/useManagedPlugin.js # 两个插件共用状态、任务接续与轮询
│   ├── composables/useRemotePlugin.js  # electerm 页面适配
│   ├── composables/useDatabasePlugin.js # DBX 页面适配
│   ├── composables/useDatabaseConnection.js # 连接字段与本次密码生命周期
│   ├── api/pluginClient.js            # 共用受控 API 与内存 session
│   ├── api/{remotePlugin,databasePlugin}.js # 固定插件 API 入口
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
- `ToolContent.vue`：通过 `ref` 管理表单、弹窗、SQL 条件及演示结果等页面状态。
- `RemoteTool.vue` / `DatabaseTool.vue`：分别通过插件 composable 和 API 模块接入 Java；共用 `useManagedPlugin.js` 与 `api/pluginClient.js`，其余工具分支保留原型。
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
