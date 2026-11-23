package com.toolhelper;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.config.SystemProxySupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ToolHelperProperties.class)
public class ToolHelperApplication {
    public static void main(String[] args) {
        SystemProxySupport.initialize();
        SpringApplication.run(ToolHelperApplication.class, args);
    }
}
