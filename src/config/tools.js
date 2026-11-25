export const tools = [
  { id: 'remote', group: '连接与接口', name: '远程外挂连接', keywords: 'SSH SFTP RDP VNC', lead: '统一管理 SSH、SFTP 与远程桌面连接，并快速返回最近使用的主机。', action: '新建主机' },
  { id: 'database', group: '连接与接口', name: '数据库外挂连接', keywords: 'MySQL PostgreSQL', lead: '集中配置数据库连接，并通过分步诊断快速定位连接问题。', action: '导入配置' },
  { id: 'api', group: '连接与接口', name: '极早期接口验证', keywords: 'API MQTT', lead: '登录目标后验证固定接口、读取设备与MQTT主题，支持定时检测和表格导出。', action: '查看操作区' },
  { id: 'mac', group: '连接与接口', name: '获取设备 MAC 地址', keywords: 'ARP', lead: '查询本机局域网 IPv4 / MAC 映射，支持设备台账保存与导出。', action: '查看台账' },
  { id: 'nodered', group: 'MQTT 测试工具', name: 'Node-RED 可视化编排', keywords: 'MQTT Modbus HTTP', lead: '管理便携运行时并编排串口、Modbus 与 HTTP 流程。', action: '刷新状态' },
  { id: 'druid', group: '安全与运维', name: 'Druid 漏洞检测', keywords: '安全扫描', lead: '扫描常见暴露路径，汇总风险结果与修复方向。', action: '更新规则' },
  { id: 'kylin', group: '安全与运维', name: 'KylinOS 运维策略', keywords: '系统优化', lead: '管理系统激活、服务部署、漏洞扫描与优化策略。', action: '刷新状态' },
  { id: 'serial', group: '调试工具', name: '基本串口调试', keywords: 'COM', lead: '配置串口并以文本或十六进制收发数据。', action: '刷新串口' },
  { id: 'modbus', group: '调试工具', name: '极早期 Modbus 调试', keywords: 'RTU', lead: '连接 Modbus 设备，读取解析结果并核对原始报文。', action: '扫描设备' },
  { id: 'ping', group: '调试工具', name: '群 Ping', keywords: '网络检测', lead: '批量检测网段可达性、延迟与丢包。', action: '导入目标' },
  { id: 'cron', group: '调试工具', name: 'Cron 表达式', keywords: '日期', lead: '生成表达式并预览未来执行时间。', action: '复制表达式' },
  { id: 'sql', group: '调试工具', name: 'SQL 语句生成与格式化', keywords: 'SELECT INSERT UPDATE', lead: '同屏完成 SQL 生成、格式化、复制与下载。', action: '加载 SQL' },
  { id: 'aes', group: '调试工具', name: 'AES 加密/解密', keywords: 'CBC ECB GCM', lead: '配置 AES 参数并在本机完成加密和解密。', action: '重置参数' }
]

export const toolGroups = [...new Set(tools.map((tool) => tool.group))]
