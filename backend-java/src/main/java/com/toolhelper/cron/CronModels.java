package com.toolhelper.cron;

import java.util.List;
import java.util.Map;

public final class CronModels {
    private CronModels() {}
    public record AnalyzeRequest(String mode, String expression, String zoneId, String startLocal, String startOffset, Integer count) {}
    public record FieldRule(String kind, List<Integer> values, Integer start, Integer end, Integer step) {}
    public record GenerateRequest(String mode, Map<String, FieldRule> fields, String zoneId, String startLocal, String startOffset, Integer count) {}
    public record Field(String name, String label, int index, String value, String description) {}
    public record TimeRow(String localDateTime, String offset, String instant, String weekday) {}
    public record Analysis(String mode, String normalizedExpression, String engineExpression, List<Field> fields,
            String zoneId, String effectiveStart, List<TimeRow> times, int requestedCount, int returnedCount,
            String windowEnd, String outcome, List<String> warnings) {}
    public record Template(String name, String expression) {}
}
