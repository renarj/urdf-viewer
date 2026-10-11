package com.oberasoftware.urdfviewer;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(UrdfViewerProperties.class)
public class UrdfViewerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrdfViewerApplication.class, args);
    }
}
