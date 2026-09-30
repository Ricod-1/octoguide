package com.octoguide.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启 Spring 定时任务能力，具体任务由业务 Service 声明执行周期。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // 配置类本身无需额外逻辑。
}
