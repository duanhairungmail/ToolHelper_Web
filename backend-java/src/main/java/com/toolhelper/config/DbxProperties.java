package com.toolhelper.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("toolhelper.dbx")
public record DbxProperties(List<String> supportedVersions) {
    public DbxProperties { supportedVersions = List.copyOf(supportedVersions); }
}
