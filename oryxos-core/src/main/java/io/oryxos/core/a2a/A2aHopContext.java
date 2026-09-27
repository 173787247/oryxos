package io.oryxos.core.a2a;

/** Inbound A2A hop count for the current request thread (outbound increments by one). */
public final class A2aHopContext {

  public static final String HOP_HEADER = "X-A2A-Hop";

  private static final ThreadLocal<Integer> HOP = ThreadLocal.withInitial(() -> 0);

  private A2aHopContext() {}

  public static int current() {
    Integer v = HOP.get();
    return v == null ? 0 : v;
  }

  public static void set(int hop) {
    HOP.set(Math.max(0, hop));
  }

  public static void clear() {
    HOP.remove();
  }
}
