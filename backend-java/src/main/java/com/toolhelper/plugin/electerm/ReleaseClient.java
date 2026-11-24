package com.toolhelper.plugin.electerm;
import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.common.*;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReleaseClient extends OfficialReleaseClient {
    @Autowired
    public ReleaseClient(ToolHelperProperties properties, JsonMapper json, OfficialDownloadClient downloads) {
        this(properties, json, downloads, Clock.systemUTC(), false);
    }
    public ReleaseClient(ToolHelperProperties properties, JsonMapper json) { this(properties, json, new OfficialDownloadClient(properties), Clock.systemUTC(), true); }
    ReleaseClient(ToolHelperProperties properties, JsonMapper json, HttpClient http, Clock clock) { this(properties, json, new OfficialDownloadClient(properties, http, clock), clock, true); }
    private ReleaseClient(ToolHelperProperties properties, JsonMapper json, OfficialDownloadClient downloads, Clock clock, boolean owns) {
        super(properties, json, downloads, clock, owns, "electerm/electerm", properties.supportedVersions(), value -> {
            String[] parts = value.split(":", 2);
            return parts[1].equals("electerm-" + parts[0] + "-win-x64-portable.tar.gz") || parts[1].equals("electerm-" + parts[0] + "-win-x64-portable.zip");
        });
    }
}
