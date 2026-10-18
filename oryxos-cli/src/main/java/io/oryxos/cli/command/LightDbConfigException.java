package io.oryxos.cli.command;

/**
 * 轻命令的 datasource 解析失败：外部 {@code config/application.yml} 存在、但读不出/解析失败/结构非法。
 *
 * <p>抛它而不是回落内置 SQLite —— 静默换库会让用户看到"另一个库"的数据且毫无提示。重命令（Spring）遇到同一份坏配置是启动 失败，轻命令必须同样响亮。由 CLI 入口
 * {@code OryxOsCli} 统一落成 stderr 一行信息 + 退出码 1。
 */
public class LightDbConfigException extends RuntimeException {

  public LightDbConfigException(String message) {
    super(message);
  }

  public LightDbConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
