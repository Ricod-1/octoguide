package com.octoguide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定知识更新管理接口的访问令牌。 */
@Component
@ConfigurationProperties(prefix = "octoguide.knowledge.update")
public class KnowledgeUpdateProperties {

    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /** 令牌非空时才允许调用管理接口。 */
    public boolean isConfigured() {
        return token != null && !token.trim().isEmpty();
    }
}
