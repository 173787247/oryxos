package io.oryxos.web.controller.dto;

import io.oryxos.core.channel.ChannelStatus;

/** 渠道在线状态视图（017 FR-014）：GET /api/v1/channels/status。 */
public record ChannelStatusView(
    String name, String type, String agent, String state, String error) {

  public static ChannelStatusView from(ChannelStatus status) {
    return new ChannelStatusView(
        status.name(), status.type(), status.agent(), status.state().name(), status.error());
  }
}
