package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

public final class ValidationCron {
    private final CronExpression expression;
    private ValidationCron(CronExpression expression) { this.expression = expression; }
    public static ValidationCron parse(String text) {
        if (text == null || text.length() > 128) throw invalid();
        String[] fields = text.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) throw invalid();
        // 明确只接受数字、通配符、范围、列表及步进；不悄悄忽略年份或接受未说明的扩展语法。
        for (String field : fields) if (!field.matches("[0-9*?,/\\-]+")) throw invalid();
        try { return new ValidationCron(CronExpression.parse((fields.length == 5 ? "0 " : "") + text.trim())); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    public ZonedDateTime next(ZonedDateTime from) {
        ZonedDateTime next = expression.next(from);
        if (next == null) throw invalid();
        return next;
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_CRON", "Cron 只支持5/6字段的数字、*、?、范围、列表与步进，不支持年份"); }
}
