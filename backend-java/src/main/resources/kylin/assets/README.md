# 阶段6内置资产

这些资产随应用分发，SHA256在`KylinTemplates`中固定核对。摘要用于检测包内文件变化，不能代替上游来源签名或目标系统兼容性验证。

| 文件 | 来源 | SHA256 |
| --- | --- | --- |
| `x11vnc_x86_64` | 用户原ToolHelper项目的`Resources/x11vnc/x11vnc_x86_64`原样迁入 | `0c085b6e9b33e2939ce34b2a160973966d171786bba7f4f75941998e45c7c797` |
| `pg_hba.conf` | 用户在阶段6提供的openGauss替换文件，原样保存为模板 | `8c68ba82d0f3d2a0f759de3257d1fde5781d20650ee5e69bbee004cfd9e22bca` |
| `postgresql.conf` | 同上 | `d045a2fd8893d85c1f74ccf71b4908cffe9e2f5a1fe96f2d08aa1f47a6d5d992` |
| `kylin-offline-upgrade_1.0.0.33-0k1.11_amd64.deb` | 用户提供的`Knowledge_Base/ToolHelper_Web_Base/需要替换的文件/KylinOS漏洞补丁/`，原样迁入，178172字节 | `92fbdd5421a9a0783c123a30005f87f007feff0f3a5b45424ebcb32823e7fdc8` |

x11vnc经本机`file`识别为x86_64动态链接ELF，解释器`/lib64/ld-linux-x86-64.so.2`。没有aarch64资产；上游构建来源和签名尚未取得，不声称已完成厂商溯源。部署前核对目标架构、LightDM及X11:0套接字，并在私有暂存目录运行`-version`验证解释器和运行依赖；任一步失败不安装、不启动服务。实际画面连接仍须真机验收。

数据库文件只作为用户确认的模板。生成阶段替换监听地址，删除模板中的全部active host规则，并将local trust收紧为sha256，再添加回环及明确CIDR来源。模板中的其他参数也会整体替换，页面必须逐项审查；不自动reload/restart。不会把openGauss配置套用于通用PostgreSQL。

补丁的control元数据已读取：Package=`kylin-offline-upgrade`，Version=`1.0.0.33-0k1.11`，Architecture=`amd64`，control中未声明Depends。包内虽有`_kylin.crt`及`_kylin.sig`，本轮未通过独立可信发布者验证其签名，不据此声称厂商来源已验证。版本只作为用户提供的修复基线，尚无独立厂商公告/CVE映射。

维护脚本影响已检查：preinst会将已有桌面文件复制到`/tmp`；postinst更新MIME/桌面关联，启用并重启`kylin-offline-upgrade.service`。其中部分命令通过管道输出可能掩盖失败，因此安装退出0之后仍复查包配置/审计和服务运行/自启。执行使用`dpkg --force-confold --install`保留已有conffile，但维护脚本仍可能修改其他文件；不会自动安装依赖、执行apt修复、重启系统或提供软件包降级回滚。详见后端README第11节。
