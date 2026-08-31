# 注意：不用 `# syntax=docker/dockerfile:1` 指令——国内镜像源常缺 docker/dockerfile 前端镜像；
# Docker 23+ 内置 BuildKit 前端已覆盖本文件用到的全部特性。
# OryxOS 容器镜像——tar.gz 发行包之外的另一种分发形态（纯增量，不影响 bin/start.sh 路径）。
#
# jar 是平台无关的：镜像内不做 Maven 构建，只把已构建的胖 jar 拷进 JRE 基础镜像。
# 因此多架构构建（amd64 + arm64）只需换基础镜像层，无需在 QEMU 里跑 Maven——
# jar 始终由构建方（本地或 CI runner）原生构建一次。
#
# 本地构建：
#   mvn package -DskipTests          # 或 make build（含管理台前端）
#   docker build -t oryxos:dev .
#   docker run -d -p 8080:8080 -v oryxos-data:/data oryxos:dev
#
# JAR_FILE 可覆盖（默认 glob 命中 oryxos-boot/target/ 下的唯一胖 jar；target/ 残留多个旧版本 jar 时
# 该 glob 有歧义、构建会失败——先 mvn clean，或像 make docker 那样显式钉死版本）：
#   docker build --build-arg JAR_FILE=oryxos-boot/target/oryxos-boot-0.1.4-RELEASE.jar .

# ARG 声明在 FROM 之前，供 stage 内通过「无默认值的重复 ARG」继承
ARG JAR_FILE=oryxos-boot/target/oryxos-boot-*.jar

FROM eclipse-temurin:21-jre-jammy

ARG JAR_FILE

LABEL org.opencontainers.image.title="OryxOS" \
      org.opencontainers.image.description="Self-hosted Agent Operating System for the enterprise" \
      org.opencontainers.image.source="https://github.com/oryx-labs/oryxos" \
      org.opencontainers.image.licenses="Apache-2.0"

# curl 供 HEALTHCHECK 探测 /api/v1/health；tzdata 供 TZ 生效。
# 固定 uid/gid 1000 的非 root 运行用户：named volume 首挂时继承镜像内 /data 的属主。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 oryxos \
    && useradd --uid 1000 --gid oryxos --create-home --shell /usr/sbin/nologin oryxos

# 状态全部落在 /data（config/ 工作区 .oryxos/ oryxos.db logs/），挂 named volume 即可持久化。
# ORYXOS_ROOT 指向卷内工作区——解析顺序 -Doryxos.root > ORYXOS_ROOT > 默认 .oryxos，环境变量原生支持。
ENV ORYXOS_ROOT=/data/.oryxos \
    ORYXOS_PORT=8080 \
    JAVA_OPTS=-XX:MaxRAMPercentage=75.0 \
    TZ=Asia/Shanghai

WORKDIR /data

COPY docker/docker-entrypoint.sh /opt/oryxos/docker-entrypoint.sh
COPY config/application.yml.example /opt/oryxos/config/application.yml.example
COPY ${JAR_FILE} /opt/oryxos/oryxos.jar

RUN chmod 555 /opt/oryxos/docker-entrypoint.sh \
    && mkdir -p /data/config /data/logs /data/.oryxos \
    && chown -R oryxos:oryxos /data /opt/oryxos

USER oryxos

EXPOSE 8080

# 健康检查打 serve 已有的 /api/v1/health；启动期给足 90s（首次启动含 SQLite/工作区初始化）
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS "http://localhost:${ORYXOS_PORT}/api/v1/health" || exit 1

# 无参数时缺省 serve --port $ORYXOS_PORT；也可覆盖为其他子命令（如 docker run … oryxos init）
ENTRYPOINT ["/opt/oryxos/docker-entrypoint.sh"]
