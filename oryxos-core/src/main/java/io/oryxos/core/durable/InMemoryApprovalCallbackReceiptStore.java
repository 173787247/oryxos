package io.oryxos.core.durable;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内回调收据（单测 / 未装配 JPA）。 */
public final class InMemoryApprovalCallbackReceiptStore implements ApprovalCallbackReceiptStore {

  private final ConcurrentHashMap<String, String> seen = new ConcurrentHashMap<>();

  @Override
  public boolean tryClaim(String channel, String callbackId, String checkpointId) {
    String key = key(channel, callbackId);
    String prev = seen.putIfAbsent(key, checkpointId == null ? "" : checkpointId);
    return prev == null;
  }

  @Override
  public boolean alreadySeen(String channel, String callbackId) {
    return seen.containsKey(key(channel, callbackId));
  }

  private static String key(String channel, String callbackId) {
    return Objects.requireNonNullElse(channel, "")
        + "\0"
        + Objects.requireNonNullElse(callbackId, "");
  }
}
