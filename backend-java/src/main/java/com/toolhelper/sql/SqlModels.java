package com.toolhelper.sql;

import java.util.List;

/** 请求按语句类型拆分；数值以文本传递，避免浏览器丢失精度。 */
public final class SqlModels {
    private SqlModels() {}
    public record Value(String type, String value) {}
    public record Condition(String column, String operator, Value value, String likeMode) {}
    public record Assignment(String column, Value value) {}
    public record Order(String column, String direction) {}
    public record Column(String name, String type, Integer length, Integer precision, Integer scale,
                         Boolean nullable, Boolean primaryKey, Boolean unique, Boolean identity, Value defaultValue) {}
    public record Select(List<String> columns, Boolean allColumns, String junction, List<Condition> conditions, List<Order> orders, String limit) {}
    public record Insert(List<Assignment> values) {}
    public record Update(List<Assignment> values, String junction, List<Condition> conditions) {}
    public record Delete(String junction, List<Condition> conditions) {}
    public record Create(List<Column> columns, String comment) {}
    public record GenerateRequest(String dialect, String escapeMode, String statementType, String schema, String table,
                                  Select select, Insert insert, Update update, Delete delete, Create create) {}
    public record FormatRequest(String dialect, String escapeMode, String sql, String keywordCase, Integer indentSize) {}
    public record Result(String sql, String dialect, int statementCount, List<String> warnings) {}
}
