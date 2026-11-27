package com.toolhelper.sql;

import com.alibaba.druid.DbType;
import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static com.toolhelper.sql.SqlModels.*;

public enum SqlDialect {
    MYSQL(DbType.mysql), POSTGRESQL(DbType.postgresql);
    public final DbType dbType;
    SqlDialect(DbType dbType) { this.dbType = dbType; }
    public static SqlDialect from(String text) {
        if ("MYSQL".equals(text)) return MYSQL;
        if ("POSTGRESQL".equals(text)) return POSTGRESQL;
        throw new ApiException(400, "SQL_UNSUPPORTED_DIALECT", "请选择 MySQL 或 PostgreSQL");
    }
    public boolean backslash(String mode) {
        if (this == POSTGRESQL) {
            if (!"STANDARD".equals(mode)) throw invalid("PostgreSQL 使用 STANDARD 模式");
            return false;
        }
        if (!"BACKSLASH".equals(mode) && !"NO_BACKSLASH_ESCAPES".equals(mode)) throw invalid("请选择 MySQL 反斜杠模式");
        return "BACKSLASH".equals(mode);
    }
    public String name(String text, String path) {
        if (text == null || !text.matches("[\\p{L}_][\\p{L}\\p{N}_]*") || this == MYSQL && text.codePoints().anyMatch(c -> c > 0xFFFF))
            throw invalid(path + "：名称只能包含支持的字母、数字和下划线，且不能以数字开头");
        int size = this == MYSQL ? text.codePointCount(0, text.length()) : text.getBytes(StandardCharsets.UTF_8).length;
        if (size > (this == MYSQL ? 64 : 63)) throw invalid(path + "：名称超过方言长度上限");
        String quote = this == MYSQL ? "`" : "\"";
        return quote + text + quote;
    }
    public String string(String text, boolean backslash) {
        if (text == null) throw invalid("字符串值不可缺失，空字符串请保留空输入");
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw invalid("字符串包含不支持的控制字符");
        String value = text;
        if (this == POSTGRESQL || backslash) value = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        value = value.replace("'", "''");
        return (this == POSTGRESQL ? "E" : "") + "'" + value + "'";
    }
    public String value(Value value, boolean backslash, String path) {
        if (value == null || value.type() == null) throw invalid(path + "：请选择值类型");
        return switch (value.type()) {
            case "STRING" -> string(value.value(), backslash);
            case "NUMBER" -> {
                if (value.value() == null || !value.value().matches("[+-]?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw invalid(path + "：请输入普通十进制数，编号请选择字符串");
                yield value.value();
            }
            case "BOOLEAN" -> {
                if (!"true".equals(value.value()) && !"false".equals(value.value())) throw invalid(path + "：布尔值须为 true 或 false");
                yield value.value().toUpperCase(Locale.ROOT);
            }
            case "NULL" -> {
                if (value.value() != null) throw invalid(path + "：NULL 不能携带文本值");
                yield "NULL";
            }
            default -> throw invalid(path + "：不支持的值类型");
        };
    }
    public static ApiException invalid(String message) { return new ApiException(400, "SQL_INVALID_INPUT", message); }
}
