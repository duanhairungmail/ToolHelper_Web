# ToolHelper_Web

调试工具助手 Web 版，将远程连接、数据库连接、接口验证、设备调试及运维工具集中到统一工作台。

项目基于 Vue 3 和 Vite，采用 Composition API、Vue Router 和组件化布局。当前已搭建前端基础架构、13 个工具入口及部分交互原型，后续可在现有结构上逐步接入实际业务能力。

> 当前版本以界面与交互演示为主，尚未接入真实的远程连接、串口通信、数据库连接或系统运维服务。界面中的成功提示和示例数据不代表实际操作已执行；AES 页面也尚未实现真正的 AES 加解密。

## 技术栈

| 项目 | 当前实现 |
| --- | --- |
| 前端框架 | Vue 3.5，单文件组件与 `<script setup>` |
| 路由 | Vue Router 4，HTML5 History 模式 |
| 构建工具 | Vite 7、`@vitejs/plugin-vue` 6 |
| 开发语言 | JavaScript，ES Modules |
| 全局状态 | Vue `reactive` + `provide/inject`，未引入 Pinia |
| 样式 | 原生 CSS、CSS 变量、Grid/Flex 和媒体查询 |
| 请求基础 | 原生 `fetch` 封装，尚未被工具业务调用 |
| 依赖管理 | npm，使用 `package-lock.json` 锁定依赖 |

项目当前没有引入 UI 组件库或额外的业务运行时依赖。

## 工具入口与实现状态

以下状态以当前 Vue 应用源码为准。全部入口均可通过侧边栏访问，默认页面为 `/tools/remote`。

| 工具 | 路由 | 当前实现 |
| --- | --- | --- |
| 远程外挂连接 | `/tools/remote` | 插件管理、连接表单、协议默认端口切换及日志演示；未接入 SSH/SFTP/RDP/VNC |
| 数据库外挂连接 | `/tools/database` | 插件管理、连接表单及日志演示；未接入数据库服务 |
| 极早期接口验证 | `/tools/api` | 通用控制台占位；未实现 HTTP/MQTT 请求收发 |
| 获取设备 MAC 地址 | `/tools/mac` | 通用控制台占位；未实现设备发现与 MAC 获取 |
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

浏览器访问终端输出的地址。访问根路径 `/` 时会自动跳转到 `/tools/remote`。

已有本地项目时，直接在项目根目录执行 `npm ci` 和 `npm run dev`。有意调整依赖后，应同步提交更新后的 `package.json` 与 `package-lock.json`。

### 可用脚本

| 命令 | 用途 |
| --- | --- |
| `npm run dev` | 启动 Vite 开发服务 |
| `npm run build` | 执行生产构建，输出到 `dist/` |
| `npm run preview` | 本地预览已有的生产构建产物 |

开发服务和预览服务均配置了 `--host 0.0.0.0`。具体地址及端口以终端输出为准。

项目当前尚未配置自动化测试、lint 或类型检查脚本。构建成功仅说明应用可以打包，不代表各工具已具备真实业务能力。

## 项目结构

```text
ToolHelper_Web/
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
│   │   ├── LogDrawer.vue              # 公共日志抽屉
│   │   └── ToolContent.vue            # 各工具的内容分支与交互逻辑
│   ├── composables/useLogDrawer.js    # 日志与抽屉状态复用
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
├── README.md
├── server.mjs                        # 遗留静态服务，默认 HTML 入口当前缺失
└── vite.config.js
```

`node_modules/`、`dist/`、日志和 `*.local` 文件已由 `.gitignore` 排除。

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
- `useLogDrawer.js`：管理日志数组、展开状态、追加与清空操作；`LogDrawer.vue` 负责展示。
- `utils/request.js`：提供基础 `fetch` 和 JSON 响应处理，尚未配置业务 API 地址、认证或开发代理。
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

部署时将 `dist/` 内容交给静态文件服务器托管；`npm run preview` 用于本地检查构建结果。

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
