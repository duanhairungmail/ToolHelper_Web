package com.toolhelper.cron;

import java.util.Arrays;
import java.util.stream.Collectors;

/** 先拆列表，再解释各项的范围和步进，避免混合规则被漏掉。 */
public final class CronDescription {
    private CronDescription() {}
    public static String describe(String value) {
        return Arrays.stream(value.split(",")).map(CronDescription::item).collect(Collectors.joining("；或 "));
    }
    private static String item(String value) {
        if (value.equals("*")) return "所有值";
        if (value.equals("?")) return "不指定，由另一日期维度约束";
        if (value.equals("LW")) return "本月最后一个周一至周五的日期（不调整法定节假日）";
        if (value.equals("L")) return "本月最后一天";
        if (value.matches("L-\\d+")) return "本月最后一天向前 " + value.substring(2) + " 天";
        if (value.matches("\\d+W")) return "最接近本月 " + value.substring(0, value.length() - 1) + " 日的周一至周五日期，不跨月、不调整节假日";
        if (value.contains("#")) {
            String[] parts = value.split("#");
            return "本月第 " + parts[1] + " 个星期值 " + parts[0];
        }
        if (value.endsWith("L")) return "本月最后一个星期值 " + value.substring(0, value.length() - 1);
        String[] step = value.split("/");
        String base = step[0];
        String description = base.equals("*") ? "字段完整范围" : base.contains("-") ? "范围 " + base.replace("-", " 至 ") : "指定值 " + base;
        if (step.length == 2) return (base.matches("[0-9]+") ? "从 " + base + " 到字段最大值" : description) + "，步长 " + step[1];
        return description;
    }
}
