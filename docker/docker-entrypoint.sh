#!/bin/sh
# OryxOS 容器入口——bin/start.sh 的容器化等价物，三处刻意不同：
#   1) exec 前台运行：java 即 PID 1，SIGTERM 直达 Spring 优雅停机（容器不养后台进程/PID 文件）
#   2) 首启非交互：从镜像内模板生成配置后照常启动（容器可零 key 启动，
#      Provider 随后在管理台配置或挂载覆盖），不做 start.sh 的「填完 key 再来」交互退出
#   3) 日志走 stdout：交给 docker logs，不重定向到文件
set -eu

APP_HOME=/opt/oryxos
DATA_DIR=/data
PORT="${ORYXOS_PORT:-8080}"

mkdir -p "$DATA_DIR/config" "$DATA_DIR/logs" "${ORYXOS_ROOT:-$DATA_DIR/.oryxos}"

# 首启：配置不存在则从镜像内模板生成一份（已挂载自己的 application.yml 时跳过）
if [ ! -f "$DATA_DIR/config/application.yml" ] && [ -f "$APP_HOME/config/application.yml.example" ]; then
  cp "$APP_HOME/config/application.yml.example" "$DATA_DIR/config/application.yml"
  echo "[INFO] 已生成 $DATA_DIR/config/application.yml —— 可挂载覆盖，或在管理台配置 Provider"
fi

# 无参数缺省 serve；有参数则透传（docker run … oryxos init / status / chat …）
if [ "$#" -eq 0 ]; then
  set -- serve --port "$PORT"
fi

# JAVA_OPTS 故意不加引号以支持多项参数（如 -Xmx512m -XX:…）
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} \
  -Dspring.config.additional-location="optional:file:$DATA_DIR/config/" \
  -jar "$APP_HOME/oryxos.jar" \
  "$@"
