package com.toolhelper.druid;

import java.util.List;

/** 固定允许目录。依据Alibaba Druid 1.2.27；任何重置/详情遍历操作均不在目录内。 */
public final class DruidRuleCatalog {
    private DruidRuleCatalog() {}
    public static final String VERSION = "toolhelper-1/druid-1.2.27";
    public record Rule(String id, String path, boolean json, List<String> fields, List<String> sensitive) {}
    private static Rule page(String id, String path) { return new Rule(id, path, false, List.of(), List.of()); }
    private static Rule json(String id, String path, List<String> fields, String... sensitive) {
        return new Rule(id, path, true, fields, List.of(sensitive));
    }
    public static final List<Rule> ALL = List.of(
        page("INDEX", "index.html"),
        json("BASIC", "basic.json", List.of("Version", "Drivers", "JavaVMName", "JavaVersion", "DruidVersion")),
        json("DATASOURCE", "datasource.json", List.of("Identity", "Name", "URL", "DbType", "ActiveCount", "PoolingCount")),
        json("SQL", "sql.json?page=1&perPageCount=10", List.of("ID", "SQL", "ExecuteCount", "TotalTime"), "SQL"),
        json("WEBSESSION", "websession.json?page=1&perPageCount=10", List.of("SESSIONID", "SessionID", "CreateTime", "RequestCount", "Principal"), "SESSIONID", "SessionID", "Principal"),
        json("WEBURI", "weburi.json?page=1&perPageCount=10", List.of("URI", "RequestCount", "JdbcExecuteCount")),
        json("WEBAPP", "webapp.json?page=1&perPageCount=10", List.of("ContextPath", "RequestCount", "SessionCount", "RunningCount")),
        json("SPRING", "spring.json?page=1&perPageCount=10", List.of("Class", "Method", "ExecuteCount", "JdbcExecuteCount")),
        page("SQL_PAGE", "sql.html"), page("SESSION_PAGE", "websession.html"), page("URI_PAGE", "weburi.html"),
        page("APP_PAGE", "webapp.html"), page("SPRING_PAGE", "spring.html")
    );
    public static final Rule LOGIN = page("LOGIN", "submitLogin");
    public static final Rule LOGIN_PAGE = page("LOGIN_PAGE", "login.html");
    public static final List<String> REMEDIATION = List.of(
        "不需要监控时关闭StatViewServlet映射；需要时配置独立强口令并限制可信来源。",
        "结合实际Servlet/Starter版本与代理拓扑核对认证和allow/deny；不要盲信客户端转发头。",
        "按需禁用resetEnable等重置能力；本工具不会修改目标或调用统计重置。",
        "复测同一来源和监控根下的JSON端点。当前结果不构成整体安全证明，规则等级不等于CVSS。"
    );
}
