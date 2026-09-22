package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI 2.x baseUrl 约定：OpenAI Java SDK 期望含版本段。用户填 /vN 保留；否则补 /v1（与 AI 1.x stripTrailingV1 相反）。 */
class ProviderChatModelFactoryHttpVersionTest {

  @Test
  @DisplayName("无版本段的baseUrl_补上/v1")
  void appendsV1WhenMissing() {
    assertEquals(
        "https://api.deepseek.com/v1",
        ProviderChatModelFactory.normalizeOpenAiBaseUrl("https://api.deepseek.com"));
    assertEquals(
        "https://api.deepseek.com/v1",
        ProviderChatModelFactory.normalizeOpenAiBaseUrl("https://api.deepseek.com/"));
  }

  @Test
  @DisplayName("已有/vN的baseUrl_原样保留（含GLM/v4与带路径的/v1）")
  void keepsTrailingVersion() {
    assertEquals(
        "https://opencode.ai/zen/go/v1",
        ProviderChatModelFactory.normalizeOpenAiBaseUrl("https://opencode.ai/zen/go/v1"));
    assertEquals(
        "https://open.bigmodel.cn/api/paas/v4",
        ProviderChatModelFactory.normalizeOpenAiBaseUrl("https://open.bigmodel.cn/api/paas/v4/"));
  }
}
