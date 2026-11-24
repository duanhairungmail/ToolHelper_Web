package com.toolhelper.plugin.dbx;

import com.toolhelper.config.*;
import com.toolhelper.plugin.common.*;
import java.time.Clock;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DbxReleaseClient extends OfficialReleaseClient {
    public DbxReleaseClient(ToolHelperProperties properties, DbxProperties dbx, JsonMapper json, OfficialDownloadClient downloads) {
        super(properties, json, downloads, Clock.systemUTC(), false, "t8y2/dbx", dbx.supportedVersions(), value -> {
            String[] parts = value.split(":", 2);
            return parts[1].equals("DBX_" + parts[0] + "_x64-portable.zip");
        });
    }
}
