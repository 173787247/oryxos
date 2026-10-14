package io.oryxos.channel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖分段这一层。
 *
 * <p><b>为什么这里没有「逐段发送」的请求计数断言</b>（而 {@code MatrixMessageSenderTest} 有）： {@link
 * TeamsMessageSender#fetchToken()} 的目标是硬编码的 {@link TeamsMessageSender#LOGIN_HOST}，{@code send}
 * 一定会去连 {@code login.microsoftonline.com}。没有把 host 提成构造参数，就没有办法在不起真实网络请求的 前提下把 {@code send} 跑完 ——
 * 而为测试去改生产代码的构造签名不是这里该做的事。
 *
 * <p>所以这里钉住能钉的两件事：默认分段值与平台上限的关系、以及切分本身。发送循环的形状 （token 与 url 提到循环外、逐段调用 {@code postMessage}）由代码本身保证。
 */
class TeamsMessageSenderTest {

  /**
   * Teams 官方文档给的消息上限是 100 KB，且明说「消息本身在 80 KB 以内才能保证送达」， 那个 KB 是 {@code encoded as UTF-16} 且包含
   * text、@-mentions 与 reactions。
   *
   * <p>UTF-16 下中文属 BMP，一个字符 2 字节 ⇒ 80 KB 约合 40960 个中文字符。
   */
  private static final int PLATFORM_SOFT_CAP_BYTES = 80 * 1024;

  private static final int PLATFORM_SOFT_CAP_CHARS_FOR_CJK = PLATFORM_SOFT_CAP_BYTES / 2;

  @Test
  @DisplayName("默认分段上限在按 UTF-16 折算后仍不超「保证送达」的软上限")
  void defaultChunkSizeFitsUnderThePlatformCap() {
    assertTrue(TeamsMessageSender.DEFAULT_CHUNK_SIZE > 0);
    assertTrue(
        TeamsMessageSender.DEFAULT_CHUNK_SIZE <= PLATFORM_SOFT_CAP_CHARS_FOR_CJK,
        "默认分段 "
            + TeamsMessageSender.DEFAULT_CHUNK_SIZE
            + " 字符；全中文时 "
            + (TeamsMessageSender.DEFAULT_CHUNK_SIZE * 2)
            + " 字节（UTF-16），超过软上限 "
            + PLATFORM_SOFT_CAP_BYTES
            + " 字节");
  }

  @Test
  @DisplayName("超长回复被切成多段，每段不超上限，拼接后一字不差")
  void longReplyIsSplitAndReassembles() {
    String reply = "字".repeat(TeamsMessageSender.DEFAULT_CHUNK_SIZE * 2 + 137);
    List<String> parts = TeamsMessageSender.segment(reply, TeamsMessageSender.DEFAULT_CHUNK_SIZE);
    assertTrue(parts.size() >= 3, "应切成多段，实际 " + parts.size());
    for (String part : parts) {
      assertTrue(part.length() <= TeamsMessageSender.DEFAULT_CHUNK_SIZE, "有段超上限: " + part.length());
    }
    assertEquals(reply, String.join("", parts), "分段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只切一段，不额外分包")
  void shortReplyStaysWhole() {
    assertEquals(
        List.of("你好"), TeamsMessageSender.segment("你好", TeamsMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("空文本仍产出一段（保持「必有回复」语义）")
  void emptyYieldsOneEmptyPart() {
    assertEquals(
        List.of(""), TeamsMessageSender.segment("", TeamsMessageSender.DEFAULT_CHUNK_SIZE));
    assertEquals(
        List.of(""), TeamsMessageSender.segment(null, TeamsMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("代理对不被劈开（emoji 不会变成两个问号）")
  void surrogatePairsAreNotSplit() {
    // "a😀b" 的 char 序列是 a, 高代理, 低代理, b（length 4）。
    // 每段 2 个 char 时，第一个切点（下标 2）正落在 emoji 中间 ——
    // split 会把切点往回收一格，于是第一段是 "a"、第二段是完整的 "😀"。
    //
    // ★ 不能用 chunkSize=1 来测这条：split 自己有一个「退无可退」分支
    //   （chunkSize 为 1 且当前就是高代理时，宁可拆开也不能原地打转，见其 javadoc），
    //   那个值下代理对被拆开是设计如此。
    List<String> parts = TeamsMessageSender.segment("a😀b", 2);
    for (String part : parts) {
      assertTrue(
          part.isEmpty() || !Character.isHighSurrogate(part.charAt(part.length() - 1)),
          "有段以孤立高代理结尾: " + part);
      assertTrue(
          part.isEmpty() || !Character.isLowSurrogate(part.charAt(0)), "有段以孤立低代理开头: " + part);
    }
    assertEquals("a😀b", String.join("", parts), "拼接必须与原文本一致");
    assertTrue(parts.contains("😀"), "emoji 应完整落在某一段里，实际分段: " + parts);
  }
}
