package com.toolhelper;

import com.toolhelper.config.ToolHelperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ToolHelperProperties.class)
public class ToolHelperApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToolHelperApplication.class, args);
    }
}
