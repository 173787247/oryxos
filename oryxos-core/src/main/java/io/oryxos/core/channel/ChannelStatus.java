package io.oryxos.core.channel;

/**
 * 渠道运行状态快照：供 {@code GET /api/v1/channels/status} 与日志呈现（017 FR-014）。
 *
 * @param name 渠道条目名
 * @param type 渠道类型
 * @param agent 绑定的 Agent 名
 * @param state 连接实况
 * @param error ERROR 时的点名原因（凭证未解析 / Agent 不存在 / 连接失败）；正常态为 null
 */
public record ChannelStatus(String name, String type, String agent, State state, String error) {

  /** 渠道连接状态机（017 data-model §7）。 */
  public enum State {
    CONNECTED,
    DISCONNECTED,
    DISABLED,
    /** 026：独连型渠道待机——连接属主为另一副本，属主失效时本副本自动接管建连。 */
    STANDBY,
    ERROR
  }

  public static ChannelStatus ok(String name, String type, String agent, State state) {
    return new ChannelStatus(name, type, agent, state, null);
  }

  public static ChannelStatus error(String name, String type, String agent, String reason) {
    return new ChannelStatus(name, type, agent, State.ERROR, reason);
  }
}
