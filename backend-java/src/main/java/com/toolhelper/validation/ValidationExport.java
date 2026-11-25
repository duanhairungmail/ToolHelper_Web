package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static com.toolhelper.validation.ValidationModels.*;

/** 仅生成文本表格OOXML，不接收客户端文件路径、XML、公式或宏；无第三方运行时依赖。 */
public final class ValidationExport {
    private ValidationExport() {}
    public static byte[] generate(String view, String format, List<Result> results, List<Device> devices, List<Topic> topics) {
        List<List<String>> rows = new ArrayList<>(); String title;
        switch (view) {
            case "results" -> {
                title = "验证结果"; rows.add(List.of("序号", "接口名称", "方法", "相对路径", "HTTP状态", "耗时(ms)", "结果", "说明"));
                for (Result row : results) rows.add(List.of("" + row.index(), row.name(), row.method(), row.path(), row.httpStatus() == null ? "" : row.httpStatus().toString(), "" + row.elapsedMs(), row.outcome(), row.message()));
            }
            case "devices" -> {
                title = "设备列表"; rows.add(List.of("设备名称", "通信地址", "设备ID"));
                for (Device row : devices) rows.add(List.of(row.name(), row.address(), row.id()));
            }
            case "topics" -> {
                title = "MQTT主题"; rows.add(List.of("网关名称", "下行主题（设备订阅）", "上行主题（设备发布）"));
                for (Topic row : topics) rows.add(List.of(row.gateName(), row.downTopic(), row.upTopic()));
            }
            default -> throw new ApiException(400, "INVALID_EXPORT_VIEW", "请选择验证结果、设备列表或MQTT主题");
        }
        int[] widths = rows.getFirst().size() == 8 ? new int[] {600, 2000, 650, 4650, 850, 1000, 1700, 3948}
                : title.equals("设备列表") ? new int[] {5000, 3000, 7398} : new int[] {4000, 5699, 5699};
        return com.toolhelper.support.TableExport.generate(title, format, rows, widths);
    }
}
