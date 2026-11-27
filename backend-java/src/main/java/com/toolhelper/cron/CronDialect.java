package com.toolhelper.cron;

import com.toolhelper.support.ApiException;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;
import static com.toolhelper.cron.CronModels.*;

/** 明确方言边界，避免五段补秒后悄悄采用不同的日/周匹配语义。 */
public final class CronDialect {
    public static final String SIX = "SPRING_6", FIVE = "FIVE_FIELD_SUBSET";
    public static final List<String> NAMES = List.of("second", "minute", "hour", "day", "month", "week");
    public static final List<String> LABELS = List.of("秒", "分", "时", "日", "月", "周");
    private static final int[] MIN = {0, 0, 0, 1, 1, 0}, MAX = {59, 59, 23, 31, 12, 7};
    private static final String[] WEEK = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
    private CronDialect() {}
    public record Parsed(String normalized, String engine, CronExpression expression, List<Field> fields) {}
    public static boolean six(String mode) {
        if (!SIX.equals(mode) && !FIVE.equals(mode)) throw invalid("INVALID_CRON_MODE", "请选择 Spring 六段或五段兼容子集模式");
        return SIX.equals(mode);
    }
    public static Parsed parse(String mode, String text) {
        boolean six = six(mode);
        if (text == null || text.isBlank() || text.length() > 128) throw invalid("INVALID_CRON_FIELDS", "表达式须为1–128字符");
        String[] fields = text.trim().toUpperCase(Locale.ROOT).split("\\s+");
        int expected = six ? 6 : 5, offset = six ? 0 : 1;
        if (fields.length != expected) throw invalid("INVALID_CRON_FIELDS", "当前模式须有 " + expected + " 个字段，不接受年份、宏或命令");
        List<Field> details = new ArrayList<>();
        for (int index = 0; index < fields.length; index++) {
            int position = index + offset;
            String value = fields[index];
            if (!validSyntax(value, position, six)) throw fieldError(position, index);
            // 使用实际引擎校验单个字段的范围与高级组合，定位错误而不解析英文异常文本。
            String[] isolated = {"*", "*", "*", "*", "*", "*"}; isolated[position] = value;
            try { CronExpression.parse(String.join(" ", isolated)); }
            catch (IllegalArgumentException failure) { throw fieldError(position, index); }
            details.add(new Field(NAMES.get(position), LABELS.get(position), index, value, CronDescription.describe(value)));
        }
        if (!unrestricted(fields[3 - offset]) && !unrestricted(fields[5 - offset]))
            throw invalid("CRON_DAY_WEEK_CONFLICT", "日与周不能同时设置具体约束；请将其中一个改为 *（六段也可用 ?）");
        String normalized = String.join(" ", fields), engine = six ? normalized : "0 " + normalized;
        try { return new Parsed(normalized, engine, CronExpression.parse(engine), List.copyOf(details)); }
        catch (IllegalArgumentException failure) { throw invalid("INVALID_CRON_FIELDS", "表达式组合不合法，请检查字段规则"); }
    }
    private static boolean validSyntax(String field, int position, boolean six) {
        String atom = six && (position == 4 || position == 5) ? "(?:[0-9]+|[A-Z]{3})" : "[0-9]+";
        String basic = "(?:\\*|" + atom + "(?:-" + atom + ")?)(?:/[0-9]+)?";
        for (String item : field.split(",", -1)) {
            if (item.matches(basic)) continue;
            if (six && (position == 3 || position == 5) && field.equals("?")) continue;
            if (six && position == 3 && item.matches("L(?:-(?:[0-9]|[12][0-9]|30))?|LW|(?:[1-9]|[12][0-9]|3[01])W")) continue;
            if (six && position == 5 && item.matches(atom + "(?:L|#[1-5])")) continue;
            return false;
        }
        return true;
    }
    private static boolean unrestricted(String field) { return field.equals("*") || field.equals("?"); }
    private static ApiException fieldError(int position, int index) {
        return invalid("INVALID_CRON_FIELD_VALUE", "第 " + (index + 1) + " 字段（" + LABELS.get(position) + " / " + NAMES.get(position) + "，索引 " + index + "）格式或取值不正确；基础范围 " + MIN[position] + "–" + MAX[position]);
    }
    public static String generate(String mode, Map<String, FieldRule> rules) {
        boolean six = six(mode); int offset = six ? 0 : 1;
        if (rules == null || !rules.keySet().equals(new HashSet<>(NAMES.subList(offset, 6))))
            throw invalid("INVALID_CRON_FIELDS", "生成器字段必须与所选模式一致，且不能为空");
        String[] fields = new String[6 - offset];
        for (int p = offset; p < 6; p++) fields[p - offset] = generateField(rules.get(NAMES.get(p)), p, six);
        if (six) {
            if (!fields[3].equals("*") && !fields[5].equals("*")) throw invalid("CRON_DAY_WEEK_CONFLICT", "生成器日与周只可设置一个具体约束");
            if (fields[3].equals("*") && !fields[5].equals("*")) fields[3] = "?";
            else fields[5] = "?";
        }
        return parse(mode, String.join(" ", fields)).normalized();
    }
    private static String generateField(FieldRule rule, int p, boolean six) {
        if (rule == null || rule.kind() == null) throw fieldError(p, p - (six ? 0 : 1));
        return switch (rule.kind()) {
            case "ALL" -> "*";
            case "FIXED", "LIST" -> {
                List<Integer> values = rule.values();
                if (values == null || values.isEmpty() || values.size() > MAX[p] - MIN[p] + 1
                        || (rule.kind().equals("FIXED") && values.size() != 1)) throw fieldError(p, p - (six ? 0 : 1));
                yield values.stream().map(v -> number(v, p, six)).distinct().reduce((a, b) -> a + "," + b).orElseThrow();
            }
            case "RANGE" -> {
                check(rule.start(), p); check(rule.end(), p);
                if (rule.start() > rule.end()) throw fieldError(p, p - (six ? 0 : 1));
                // 周日7保留数字，避免 SUN-SUN 将0到7范围缩成一个星期值。
                yield rule.start() + "-" + rule.end();
            }
            case "STEP" -> {
                check(rule.start(), p);
                if (rule.step() == null || rule.step() < 1 || rule.step() > MAX[p] - MIN[p] + 1) throw fieldError(p, p - (six ? 0 : 1));
                yield rule.start() + "/" + rule.step();
            }
            default -> throw fieldError(p, p - (six ? 0 : 1));
        };
    }
    private static String number(Integer value, int p, boolean six) { check(value, p); return six && p == 5 ? WEEK[value] : value.toString(); }
    private static void check(Integer value, int p) {
        if (value == null || value < MIN[p] || value > MAX[p]) throw invalid("INVALID_CRON_FIELD_VALUE", LABELS.get(p) + "字段数值须在 " + MIN[p] + "–" + MAX[p] + " 之间");
    }
    public static ApiException invalid(String code, String message) { return new ApiException(400, code, message); }
}
