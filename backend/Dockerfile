# OctoGuide 后端镜像：多阶段构建。
# 构建阶段用 Maven + JDK 8 打可执行 jar，运行阶段只保留 JRE，减小镜像体积。
# 目标平台为 linux/amd64：数据库驱动、Flyway 均为纯 Java 依赖，可跨平台运行。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-8 AS build

WORKDIR /build

# 先只复制 pom.xml 并预热依赖，源码变动时这一层仍可命中缓存。
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src

# 项目只包含单元测试（H2 内存库），跳过测试以加快镜像构建；测试请在 CI/本地执行。
# 注意：Spring Boot 除可执行主 jar 外还会产出 *.jar.original（未重打包的原始 jar），
# 故不能用 target/*.jar 通配符盲选，否则可能复制到不可执行的原始 jar。
RUN mvn -B -DskipTests clean package \
    && JAR=$(find target -maxdepth 1 -name '*.jar' ! -name '*.original' | head -n 1) \
    && cp "$JAR" /build/app.jar \
    && ls -l /build/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:8-jre-jammy

WORKDIR /app

# 时区固定为上海，保证日志与知识库 updated_at 的可读性。
# 显式安装 curl 供健康检查使用，不假设基础镜像预装（jammy 基础镜像通常不含 curl）。
# 以 root 安装后立即清理 apt 缓存；应用仍以非 root 用户运行。
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8080

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 以非 root 用户运行，降低容器逃逸风险。
RUN groupadd -r octoguide && useradd -r -g octoguide -d /app octoguide

COPY --from=build /build/app.jar /app/app.jar
RUN chown -R octoguide:octoguide /app
USER octoguide

EXPOSE 8080

# 健康检查：请求只读接口 /api/status 判断 Spring 上下文是否就绪。
# 端口写成字面量 8080（容器内固定值），避免 ${SERVER_PORT} 在 HEALTHCHECK 中不展开。
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/status || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
