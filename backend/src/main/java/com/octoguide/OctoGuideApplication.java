package com.octoguide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** OctoGuide Spring Boot 应用启动类。 */
@SpringBootApplication
public class OctoGuideApplication {
    /** 启动 Spring 应用上下文。 */
    public static void main(String[] args) {
        SpringApplication.run(OctoGuideApplication.class, args);
    }
}
