package com.toolhelper.sql;

import com.toolhelper.support.ApiException;
import java.util.*;
import java.util.stream.Collectors;
import static com.toolhelper.sql.SqlDialect.*;
import static com.toolhelper.sql.SqlModels.*;

/** 只从已校验的结构化字段构造SQL，不接受用户SQL片段。 */
public final class SqlGenerator {
    public Result generate(GenerateRequest request) {
        if (request == null) throw invalid("请填写生成参数");
        SqlDialect dialect = from(request.dialect());
        boolean escape = dialect.backslash(request.escapeMode());
        String table = dialect.name(request.table(), "table");
        if (request.schema() != null && !request.schema().isEmpty()) table = dialect.name(request.schema(), "schema") + "." + table;
        int payloads = (request.select() == null ? 0 : 1) + (request.insert() == null ? 0 : 1) + (request.update() == null ? 0 : 1)
                + (request.delete() == null ? 0 : 1) + (request.create() == null ? 0 : 1);
        if (payloads != 1 || request.statementType() == null) throw invalid("请提供且仅提供当前语句类型的参数");
        String sql = switch (request.statementType()) {
            case "SELECT" -> select(required(request.select()), table, dialect, escape);
            case "INSERT" -> insert(required(request.insert()), table, dialect, escape);
            case "UPDATE" -> update(required(request.update()), table, dialect, escape);
            case "DELETE" -> "DELETE FROM " + table + where(required(request.delete()).conditions(), request.delete().junction(), dialect, escape, true) + ";";
            case "CREATE_TABLE" -> create(required(request.create()), table, dialect, escape);
            default -> throw invalid("不支持的语句类型");
        };
        List<String> warnings = new ArrayList<>();
        warnings.add("仅生成文本，未连接数据库校验；执行前请核对方言、名称和转义模式。");
        if (Set.of("UPDATE", "DELETE").contains(request.statementType())) warnings.add("含数据修改语句，执行前请核对 WHERE。");
        return new Result(sql, dialect.name(), sql.endsWith(";") && request.create() != null && dialect == POSTGRESQL
                && request.create().comment() != null && !request.create().comment().isEmpty() ? 2 : 1, warnings);
    }
    private String select(Select input, String table, SqlDialect dialect, boolean escape) {
        List<String> columns = list(input.columns(), 100, "columns");
        if (Boolean.TRUE.equals(input.allColumns()) && !columns.isEmpty()) throw invalid("全部列与列列表不能同时设置");
        String names = Boolean.TRUE.equals(input.allColumns()) ? "*" : String.join(", ", names(columns, dialect, "columns"));
        if (names.isEmpty()) throw invalid("请添加查询列或选择全部列");
        String sql = "SELECT " + names + "\nFROM " + table + where(input.conditions(), input.junction(), dialect, escape, false);
        List<String> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Order order : list(input.orders(), 10, "orders")) {
            required(order);
            String name = dialect.name(order.column(), "orders.column");
            if (!seen.add(key(order.column(), dialect))) throw invalid("排序列不可重复");
            if (!Set.of("ASC", "DESC").contains(Objects.toString(order.direction(), ""))) throw invalid("排序方向须为 ASC 或 DESC");
            orders.add(name + " " + order.direction());
        }
        if (!orders.isEmpty()) sql += "\nORDER BY " + String.join(", ", orders);
        if (input.limit() != null && !input.limit().isEmpty()) {
            if (!input.limit().matches("[1-9][0-9]{0,9}") || Long.parseLong(input.limit()) > Integer.MAX_VALUE) throw invalid("LIMIT 须为1–2147483647整数");
            sql += "\nLIMIT " + input.limit();
        }
        return sql + ";";
    }
    private String insert(Insert input, String table, SqlDialect dialect, boolean escape) {
        List<Assignment> values = assignments(input.values(), dialect);
        return "INSERT INTO " + table + " (" + values.stream().map(v -> dialect.name(v.column(), "values.column")).collect(Collectors.joining(", "))
                + ")\nVALUES (" + values.stream().map(v -> dialect.value(v.value(), escape, "values.value")).collect(Collectors.joining(", ")) + ");";
    }
    private String update(Update input, String table, SqlDialect dialect, boolean escape) {
        List<Assignment> values = assignments(input.values(), dialect);
        return "UPDATE " + table + "\nSET " + values.stream().map(v -> dialect.name(v.column(), "values.column") + " = " + dialect.value(v.value(), escape, "values.value"))
                .collect(Collectors.joining(",\n    ")) + where(input.conditions(), input.junction(), dialect, escape, true) + ";";
    }
    private List<Assignment> assignments(List<Assignment> input, SqlDialect dialect) {
        List<Assignment> values = list(input, 100, "values");
        if (values.isEmpty()) throw invalid("请至少添加一个赋值字段");
        names(values.stream().map(v -> required(v).column()).toList(), dialect, "values.column");
        return values;
    }
    private String where(List<Condition> input, String junction, SqlDialect dialect, boolean escape, boolean mandatory) {
        List<Condition> conditions = list(input, 50, "conditions");
        if (conditions.isEmpty()) {
            if (mandatory) throw new ApiException(400, "SQL_WHERE_REQUIRED", "UPDATE / DELETE 必须提供有效 WHERE 条件");
            return "";
        }
        if (!"AND".equals(junction) && !"OR".equals(junction)) throw invalid("条件组合须为 AND 或 OR");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            Condition c = required(conditions.get(i)); String path = "conditions[" + i + "]";
            String column = dialect.name(c.column(), path + ".column"), op = c.operator();
            if (op == null || !Set.of("=", "!=", ">", "<", ">=", "<=", "LIKE", "NOT LIKE", "IS NULL", "IS NOT NULL").contains(op)) throw invalid(path + "：不支持的运算符");
            if (op.startsWith("IS ")) {
                if (c.value() != null || c.likeMode() != null) throw invalid(path + "：NULL 判断不接受值或LIKE模式");
                parts.add(column + " " + op); continue;
            }
            if (c.value() == null || "NULL".equals(c.value().type())) throw invalid(path + "：NULL 请使用 IS NULL / IS NOT NULL");
            String value;
            if (op.endsWith("LIKE")) {
                if (!"STRING".equals(c.value().type()) || c.value().value() == null) throw invalid(path + "：LIKE 需要字符串");
                String text = c.value().value();
                if ("CONTAINS".equals(c.likeMode())) {
                    text = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                    value = dialect.string(text, escape) + " ESCAPE '!'";
                } else if ("PATTERN".equals(c.likeMode())) value = dialect.string(text, escape);
                else throw invalid(path + "：请选择包含或原始模式");
            } else {
                if (c.likeMode() != null) throw invalid(path + "：非LIKE条件不能包含LIKE模式");
                value = dialect.value(c.value(), escape, path + ".value");
            }
            parts.add(column + " " + op + " " + value);
        }
        return "\nWHERE (" + String.join(" " + junction + " ", parts) + ")";
    }
    private String create(Create input, String table, SqlDialect dialect, boolean escape) {
        List<Column> columns = list(input.columns(), 100, "columns");
        if (columns.isEmpty()) throw invalid("请至少添加一个建表字段");
        names(columns.stream().map(v -> required(v).name()).toList(), dialect, "columns.name");
        List<String> lines = new ArrayList<>(), primary = new ArrayList<>(); int identities = 0;
        for (Column c : columns) {
            String name = dialect.name(c.name(), "columns.name"), type = type(c, dialect);
            boolean pk = Boolean.TRUE.equals(c.primaryKey()), identity = Boolean.TRUE.equals(c.identity());
            if (dialect == MYSQL && c.type().equals("TEXT") && (pk || Boolean.TRUE.equals(c.unique()))) throw invalid("MySQL TEXT 主键/唯一键需要前缀定义，首版请手写SQL");
            if (pk && !Boolean.FALSE.equals(c.nullable())) throw invalid("主键字段必须关闭可空");
            if (identity) {
                if (++identities > 1 || !Set.of("SMALLINT", "INTEGER", "BIGINT").contains(c.type()) || c.defaultValue() != null) throw invalid("自增/Identity 限一个整数字段，且不能另设默认值");
                if (dialect == MYSQL && (!pk || !primary.isEmpty())) throw invalid("MySQL 自增字段须为主键的第一列");
                if (!Boolean.FALSE.equals(c.nullable())) throw invalid("自增/Identity 字段不可为空");
            }
            if (pk) primary.add(name);
            String line = "    " + name + " " + type
                    + (Boolean.FALSE.equals(c.nullable()) ? " NOT NULL" : "")
                    + (identity && dialect == MYSQL ? " AUTO_INCREMENT" : "") + (Boolean.TRUE.equals(c.unique()) ? " UNIQUE" : "")
                    + (identity && dialect == POSTGRESQL ? " GENERATED BY DEFAULT AS IDENTITY" : "");
            if (c.defaultValue() != null) {
                validateDefault(c);
                if (dialect == MYSQL && c.type().equals("TEXT")) throw invalid("MySQL TEXT 字面量默认值首版不生成，请手写并核对数据库版本");
                line += " DEFAULT " + dialect.value(c.defaultValue(), escape, "columns.defaultValue");
            }
            lines.add(line);
        }
        if (!primary.isEmpty()) lines.add("    PRIMARY KEY (" + String.join(", ", primary) + ")");
        String sql = "CREATE TABLE " + table + " (\n" + String.join(",\n", lines) + "\n)";
        if (dialect == MYSQL) sql += " ENGINE = InnoDB CHARSET = utf8mb4";
        if (input.comment() != null && !input.comment().isEmpty()) {
            String comment = dialect.string(input.comment(), escape);
            if (dialect == MYSQL) sql += " COMMENT " + comment;
            else return sql + ";\nCOMMENT ON TABLE " + table + " IS " + comment + ";";
        }
        return sql + ";";
    }
    private String type(Column c, SqlDialect dialect) {
        if (c.type() == null || !Set.of("SMALLINT", "INTEGER", "BIGINT", "DECIMAL", "CHAR", "VARCHAR", "TEXT", "DATE", "TIME", "TIMESTAMP", "DATETIME", "BOOLEAN").contains(c.type())
                || dialect == POSTGRESQL && c.type().equals("DATETIME")) throw invalid("当前方言不支持该字段类型");
        if (Set.of("CHAR", "VARCHAR").contains(c.type())) {
            if (c.length() == null || c.length() < 1 || c.length() > (dialect == MYSQL ? c.type().equals("CHAR") ? 255 : 16383 : 10485760) || c.precision() != null || c.scale() != null) throw invalid("字符类型需要有效长度，不能设置精度");
            return c.type() + "(" + c.length() + ")";
        }
        if ("DECIMAL".equals(c.type())) {
            if (c.length() != null || c.precision() == null || c.precision() < 1 || c.precision() > (dialect == MYSQL ? 65 : 1000)
                    || c.scale() == null || c.scale() < 0 || c.scale() > c.precision() || (dialect == MYSQL && c.scale() > 30)) throw invalid("DECIMAL 精度/小数位设置不合法");
            return "DECIMAL(" + c.precision() + ", " + c.scale() + ")";
        }
        if (c.length() != null || c.precision() != null || c.scale() != null) throw invalid("当前类型不接受长度或精度");
        return c.type();
    }
    private void validateDefault(Column c) {
        String kind = c.defaultValue().type();
        if ("NULL".equals(kind)) { if (Boolean.FALSE.equals(c.nullable())) throw invalid("非空字段不能默认NULL"); return; }
        String expected = switch (c.type()) { case "SMALLINT", "INTEGER", "BIGINT", "DECIMAL" -> "NUMBER"; case "BOOLEAN" -> "BOOLEAN"; default -> "STRING"; };
        if (!expected.equals(kind)) throw invalid("字段类型与默认值类型不匹配");
        if (Set.of("SMALLINT", "INTEGER", "BIGINT").contains(c.type()) && c.defaultValue().value() != null && c.defaultValue().value().contains(".")) throw invalid("整数字段不接受小数默认值");
        if ("NUMBER".equals(expected)) {
            String text = c.defaultValue().value();
            if (text == null || !text.matches("[+-]?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw invalid("数值默认值不合法");
            java.math.BigDecimal number = new java.math.BigDecimal(text);
            if (c.type().equals("DECIMAL")) {
                if (number.scale() > c.scale() || Math.max(0, number.precision() - number.scale()) > c.precision() - c.scale()) throw invalid("默认值超出DECIMAL精度/小数位");
            } else {
                long min = switch (c.type()) { case "SMALLINT" -> Short.MIN_VALUE; case "INTEGER" -> Integer.MIN_VALUE; default -> Long.MIN_VALUE; };
                long max = switch (c.type()) { case "SMALLINT" -> Short.MAX_VALUE; case "INTEGER" -> Integer.MAX_VALUE; default -> Long.MAX_VALUE; };
                if (number.compareTo(java.math.BigDecimal.valueOf(min)) < 0 || number.compareTo(java.math.BigDecimal.valueOf(max)) > 0) throw invalid("默认值超出整数类型范围");
            }
        }
        if (Set.of("CHAR", "VARCHAR").contains(c.type()) && c.defaultValue().value() != null && c.defaultValue().value().codePointCount(0, c.defaultValue().value().length()) > c.length()) throw invalid("默认值超出字符长度");
    }
    private List<String> names(List<String> values, SqlDialect dialect, String path) {
        Set<String> seen = new HashSet<>(); List<String> result = new ArrayList<>();
        for (String value : values) { result.add(dialect.name(value, path)); if (!seen.add(key(value, dialect))) throw invalid(path + "：字段不可重复"); }
        return result;
    }
    private String key(String value, SqlDialect dialect) { return dialect == MYSQL ? value.toLowerCase(Locale.ROOT) : value; }
    private static <T> T required(T value) { if (value == null) throw invalid("请求缺少当前类型的必需参数"); return value; }
    private static <T> List<T> list(List<T> value, int max, String path) {
        if (value == null) return List.of();
        if (value.size() > max) throw invalid(path + "：最多" + max + "项");
        return value;
    }
}
