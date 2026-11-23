package com.toolhelper.config;

/** 在首次初始化 JDK 代理选择器前，启用当前用户的系统代理配置读取。 */
public final class SystemProxySupport {
    private SystemProxySupport() {}

    public static void initialize() {
        // JDK 在代理选择器初始化时读取该开关，必须早于 Spring 和 HttpClient。
        // 仅影响本 JVM；保留用户显式传入的 -D 配置，不修改 Windows 系统设置。
        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true");
    }
}
