package com.toolhelper.sql;

import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 有界词法保护层，不替代引擎的语法解析。所有原始字面量、名称和注释均保留快照。 */
final class SqlTokenGuard {
    enum Kind { WORD, NUMBER, LITERAL, IDENTIFIER, COMMENT, SYMBOL }
    record Token(String raw, Kind kind, int start, int end) {
        boolean comment() { return kind == Kind.COMMENT; }
        boolean protectedText() { return kind == Kind.LITERAL || kind == Kind.IDENTIFIER; }
    }
    static final int INPUT_BYTES = 256 * 1024, OUTPUT_BYTES = 1024 * 1024;
    private static final List<String> OPERATORS = List.of("->>", "#>>", "::", ">=", "<=", "<>", "!=", "||", "&&", "<<", ">>", "->", "#>", ":=", "@>", "<@");
    private SqlTokenGuard() {}
    static List<Token> scan(String sql, SqlDialect dialect, boolean backslash, int maxBytes) {
        if (sql.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new ApiException(413, "SQL_TOO_LARGE", "SQL文本超过字节配额");
        List<Token> tokens = new ArrayList<>(); int i = 0, depth = 0, statements = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isISOControl(c) || Character.isSurrogate(c)) throw unsupported("SQL包含不支持的控制字符或补充平面名称");
            int start = i; Kind kind;
            if (sql.startsWith("#{", i) || sql.startsWith("${", i)) throw unsupported("首版不支持模板占位符");
            boolean line = c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-'
                    && (dialect != SqlDialect.MYSQL || i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)));
            if (line || (c == '#' && dialect == SqlDialect.MYSQL)) {
                kind = Kind.COMMENT; i += line ? 2 : 1;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
            } else if (sql.startsWith("/*", i)) {
                if (sql.startsWith("/*!", i) || sql.startsWith("/*+", i)) throw unsupported("首版不格式化可执行注释或优化器hint");
                kind = Kind.COMMENT; int nested = 1; i += 2;
                while (i < sql.length() && nested > 0) {
                    if (sql.startsWith("/*", i)) {
                        if (dialect == SqlDialect.MYSQL) throw unsupported("MySQL嵌套注释不受支持");
                        if (++nested > 64) throw unsupported("注释嵌套超过64层"); i += 2;
                    } else if (sql.startsWith("*/", i)) { nested--; i += 2; }
                    else i++;
                }
                if (nested != 0) throw unsupported("SQL注释未闭合");
            } else if (dialect == SqlDialect.POSTGRESQL && (c == 'E' || c == 'e') && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                kind = Kind.LITERAL; i = quoted(sql, i + 1, '\'', true);
            } else if (c == '\'') {
                kind = Kind.LITERAL; i = quoted(sql, i, c, dialect == SqlDialect.MYSQL && backslash);
            } else if (c == '"' && dialect == SqlDialect.POSTGRESQL || c == '`' && dialect == SqlDialect.MYSQL) {
                kind = Kind.IDENTIFIER; i = quoted(sql, i, c, false);
            } else if (c == '"' || c == '`') throw unsupported("当前方言不支持此引号形式；MySQL双引号受ANSI_QUOTES影响，首版请使用单引号字符串/反引号名称");
            else if (c == '$' && dialect == SqlDialect.POSTGRESQL) {
                int tagEnd = sql.indexOf('$', i + 1);
                if (tagEnd < 0 || !sql.substring(i + 1, tagEnd).matches("[A-Za-z_][A-Za-z0-9_]*|")) throw unsupported("首版不支持参数占位符或模板");
                String tag = sql.substring(i, tagEnd + 1); int end = sql.indexOf(tag, tagEnd + 1);
                if (end < 0) throw unsupported("PostgreSQL dollar字符串未闭合");
                kind = Kind.LITERAL; i = end + tag.length();
            } else if (Character.isLetter(c) || c == '_') {
                kind = Kind.WORD; i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                if (sql.substring(start, i).equalsIgnoreCase("DELIMITER")) throw unsupported("不支持客户端DELIMITER命令");
            } else if (Character.isDigit(c)) {
                kind = Kind.NUMBER; i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.' || sql.charAt(i) == '_')) i++;
            } else {
                kind = Kind.SYMBOL;
                if (c == '?' || c == ':' && !sql.startsWith("::", i) || c == '$' || c == '\\' || sql.startsWith("#{", i))
                    throw unsupported("首版不支持占位符、模板或客户端命令");
                String op = null;
                for (String candidate : OPERATORS) if (sql.startsWith(candidate, i)) { op = candidate; break; }
                i += op == null ? 1 : op.length();
                if (c == '(' && ++depth > 64) throw new ApiException(413, "SQL_TOO_COMPLEX", "SQL括号嵌套超过64层");
                if (c == ')' && --depth < 0) throw unsupported("SQL括号不匹配");
                if (c == ';' && ++statements > 50) throw new ApiException(413, "SQL_TOO_COMPLEX", "SQL语句超过50条");
            }
            if (i < 0) throw unsupported("SQL引号未闭合");
            tokens.add(new Token(sql.substring(start, i), kind, start, i));
            if (tokens.size() > 50_000) throw new ApiException(413, "SQL_TOO_COMPLEX", "SQL token超过50000");
        }
        if (depth != 0) throw unsupported("SQL括号不匹配");
        return List.copyOf(tokens);
    }
    /** 字面量用不含转义的占位字面量交给引擎，以避免引擎SQL_MODE不同而改变解析边界。 */
    static String masked(String source, List<Token> tokens) {
        StringBuilder result = new StringBuilder(); int end = 0, index = 0;
        for (Token token : tokens) {
            result.append(source, end, token.start());
            if (token.comment()) result.append(' ');
            else result.append(mask(token, index++));
            end = token.end();
        }
        result.append(source, end, source.length());
        return result.toString();
    }
    static String mask(Token token, int index) {
        if (token.kind() == Kind.LITERAL) return "'th_literal_" + index + "'";
        if (token.kind() == Kind.IDENTIFIER) return token.raw().charAt(0) + "th_identifier_" + index + token.raw().charAt(0);
        return token.raw();
    }
    private static int quoted(String sql, int start, char quote, boolean backslash) {
        for (int i = start + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == 0) throw unsupported("SQL字符串包含NUL");
            if (backslash && c == '\\') { i++; continue; }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { i++; continue; }
                return i + 1;
            }
        }
        return -1;
    }
    static ApiException unsupported(String message) { return new ApiException(400, "SQL_UNSUPPORTED_SYNTAX", message); }
    static ApiException changed() { return new ApiException(400, "SQL_FORMAT_NOT_PRESERVED", "引擎排版改变了token结构，无法保持原文；本次未输出结果"); }
}
