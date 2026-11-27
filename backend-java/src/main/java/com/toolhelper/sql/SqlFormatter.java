package com.toolhelper.sql;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.*;
import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.toolhelper.sql.SqlModels.*;
import static com.toolhelper.sql.SqlTokenGuard.*;

/** 引擎验证结构并给出排版，原token回放保证字面量和注释不被AST序列化改写。 */
public final class SqlFormatter {
    public Result format(FormatRequest request) {
        if (request == null || request.sql() == null || request.sql().isBlank()) throw SqlDialect.invalid("请提供待格式化SQL");
        SqlDialect dialect = SqlDialect.from(request.dialect()); boolean backslash = dialect.backslash(request.escapeMode());
        if (!Set.of("UPPER", "LOWER").contains(Objects.toString(request.keywordCase(), ""))) throw SqlDialect.invalid("关键字大小写选项无效");
        if (request.indentSize() == null || !Set.of(2, 4).contains(request.indentSize())) throw SqlDialect.invalid("缩进仅支持2或4个空格");
        List<Token> original = scan(request.sql(), dialect, backslash, INPUT_BYTES);
        List<Token> significant = original.stream().filter(t -> !t.comment()).toList();
        if (significant.isEmpty()) throw SqlDialect.invalid("只有注释或空白，无需格式化");
        String skeleton = masked(request.sql(), original);
        List<SQLStatement> statements;
        String upper, lower;
        try {
            // 不用SQLUtils.format：该方法在解析失败时可能回退原文，无法区分真实成功。
            statements = SQLUtils.parseStatements(skeleton, dialect.dbType, SQLParserFeature.KeepNameQuotes);
            if (statements.isEmpty() || statements.size() > 50) throw new ApiException(413, "SQL_TOO_COMPLEX", "SQL语句数量须为1–50");
            for (SQLStatement statement : statements) {
                if (!(statement instanceof SQLSelectStatement || statement instanceof SQLInsertStatement || statement instanceof SQLUpdateStatement
                        || statement instanceof SQLDeleteStatement || statement instanceof SQLCreateTableStatement || statement instanceof SQLCommentStatement))
                    throw unsupported("首版仅格式化SELECT/INSERT/UPDATE/DELETE/CREATE TABLE/COMMENT及其受支持的WITH查询");
            }
            upper = SQLUtils.toSQLString(statements, dialect.dbType, new SQLUtils.FormatOption(true, true));
            lower = SQLUtils.toSQLString(statements, dialect.dbType, new SQLUtils.FormatOption(false, true));
        } catch (ApiException failure) { throw failure; }
        catch (RuntimeException failure) { throw unsupported("SQL结构不受支持或无法解析；原文已保留"); }
        List<Token> up = scan(upper, dialect, backslash, OUTPUT_BYTES), low = scan(lower, dialect, backslash, OUTPUT_BYTES);
        if (up.size() != significant.size() || low.size() != up.size()) throw changed();
        String layout = "UPPER".equals(request.keywordCase()) ? upper : lower;
        List<Token> chosen = "UPPER".equals(request.keywordCase()) ? up : low;
        StringBuilder output = new StringBuilder(); int originalIndex = 0, end = 0;
        // 上/小写两份引擎输出只有真正语法关键字会不同，名称不套用静态关键字表。
        for (int index = 0; index < up.size(); index++) {
            Token before = significant.get(index), u = up.get(index), l = low.get(index), target = chosen.get(index);
            String expected = mask(before, index);
            boolean keyword = before.kind() == Kind.WORD && !u.raw().equals(l.raw()) && u.raw().equalsIgnoreCase(l.raw()) && u.raw().equalsIgnoreCase(before.raw());
            if (!keyword && (!u.raw().equals(expected) || !l.raw().equals(expected))) throw changed();
            output.append(layout.substring(end, target.start()).replace("\t", " ".repeat(request.indentSize())));
            while (originalIndex < original.size() && original.get(originalIndex).comment()) appendComment(output, original.get(originalIndex++));
            output.append(keyword ? target.raw() : before.raw()); originalIndex++;
            end = target.end();
        }
        while (originalIndex < original.size()) appendComment(output, original.get(originalIndex++));
        String formatted = output.toString();
        if (formatted.getBytes(StandardCharsets.UTF_8).length > OUTPUT_BYTES) throw new ApiException(413, "SQL_TOO_LARGE", "格式化结果超过1 MiB");
        List<Token> replay = scan(formatted, dialect, backslash, OUTPUT_BYTES);
        if (replay.size() != original.size()) throw changed();
        for (int index = 0, significantIndex = 0; index < original.size(); index++) {
            Token a = original.get(index), b = replay.get(index);
            if (a.kind() != b.kind()) throw changed();
            if (!a.raw().equals(b.raw())) {
                if (a.kind() != Kind.WORD || up.get(significantIndex).raw().equals(low.get(significantIndex).raw()) || !a.raw().equalsIgnoreCase(b.raw())) throw changed();
            }
            if (!a.comment()) significantIndex++;
        }
        return new Result(formatted, dialect.name(), statements.size(), List.of("按所选方言和转义规则排版；未验证表结构或执行兼容性。"));
    }
    private static void appendComment(StringBuilder out, Token token) {
        if (!out.isEmpty() && !Character.isWhitespace(out.charAt(out.length() - 1))) out.append(' ');
        out.append(token.raw());
        // 行注释必须结束，不能吞掉下一条语句。块注释保留原始内容和token边界。
        out.append(token.raw().startsWith("/*") ? ' ' : '\n');
    }
}
