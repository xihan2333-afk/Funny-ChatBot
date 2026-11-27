# 服务镜像通用多阶段构建（设计方案 2.8.2——构建层与运行层分离，运行层不带 Maven 与源码）。
# 三条服务共用本 Dockerfile，经 MODULE 参数区分（access-layer / agent-core / sandbox-executor）。
# 构建命令在仓库根执行：
#   docker build -f deploy/Dockerfile --build-arg MODULE=chatbot-agent-core -t funny-chatbot/agent-core:dev .

# ===== 构建层：Maven + JDK 21 =====
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
ARG MODULE=chatbot-access-layer
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public

RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && \
      printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi

# 先拷全部 pom 再拷源码：依赖下载层可被 Docker 缓存复用
COPY pom.xml ./
COPY chatbot-contract/pom.xml chatbot-contract/
COPY chatbot-access-layer/pom.xml chatbot-access-layer/
COPY chatbot-agent-core/pom.xml chatbot-agent-core/
COPY chatbot-sandbox-executor/pom.xml chatbot-sandbox-executor/
RUN mvn -q -pl chatbot-contract,chatbot-access-layer,chatbot-agent-core,chatbot-sandbox-executor dependency:go-offline || true

COPY chatbot-contract chatbot-contract
COPY chatbot-access-layer chatbot-access-layer
COPY chatbot-agent-core chatbot-agent-core
COPY chatbot-sandbox-executor chatbot-sandbox-executor
RUN mvn -q -pl ${MODULE} -am package -DskipTests

# ===== 运行层：JRE 21（内存预算按设计方案 2.8 部署章：access-layer 768MB、
# agent-core 1GB、sandbox-executor 768MB——各服务经 JAVA_OPTS 覆盖）=====
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG MODULE=chatbot-access-layer

COPY --from=builder /build/${MODULE}/target/${MODULE}-1.0.0.jar app.jar

# OTel javaagent 挂载预留：卷挂载到 /opt/otel 并设置 ENABLE_OTEL_AGENT=true 时自动启用
# （-javaagent 版本对齐纪律：agent 版本不得低于 opentelemetry-api 1.34.1，见设计方案 3.2.5）
RUN mkdir -p /opt/otel /data/media
ENV ENABLE_OTEL_AGENT=false \
    OTEL_AGENT_PATH=/opt/otel/opentelemetry-javaagent.jar \
    JAVA_OPTS="-Xmx512m -Xms512m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=64m -XX:+UseZGC"

EXPOSE 8081

# OTLP 导出地址经 OTEL_EXPORTER_OTLP_ENDPOINT 环境变量注入（部署层 .env）
ENTRYPOINT ["sh", "-c", \
  "AGENT_OPT=$( [ \"$ENABLE_OTEL_AGENT\" = true ] && [ -f \"$OTEL_AGENT_PATH\" ] && echo \"-javaagent:$OTEL_AGENT_PATH\" || echo '' ); \
   exec java $AGENT_OPT $JAVA_OPTS -jar app.jar"]
