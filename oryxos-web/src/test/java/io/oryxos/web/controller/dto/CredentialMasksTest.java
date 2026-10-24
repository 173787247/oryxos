package io.oryxos.web.controller.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CredentialMasks 单元：回显掩码、未修改归并、webhook URL 打码的确定性/幂等性。 */
class CredentialMasksTest {

  @Test
  @DisplayName("maskValue_留末4位且短值与空白按掩码处理")
  void maskValue_keepsLastFourAndMasksShortValues() {
    assertEquals("", CredentialMasks.maskValue(null));
    assertEquals("", CredentialMasks.maskValue(""));
    assertEquals("", CredentialMasks.maskValue("   "));
    assertEquals("****", CredentialMasks.maskValue("abcd"));
    assertEquals("****cdef", CredentialMasks.maskValue("abcdef"));
  }

  @Test
  @DisplayName("maskValue_幂等")
  void maskValue_isIdempotent() {
    for (String value : new String[] {null, "", "ab", "abcdef", "****cdef"}) {
      assertEquals(
          CredentialMasks.maskValue(value),
          CredentialMasks.maskValue(CredentialMasks.maskValue(value)));
    }
  }

  @Test
  @DisplayName("maskCredential_敏感键名一律打码")
  void maskCredential_masksSensitiveKeys() {
    assertEquals("****cret", CredentialMasks.maskCredential("client_secret", "my-secret"));
    assertEquals("****", CredentialMasks.maskCredential("api-key", "xy"));
    assertEquals("****cret", CredentialMasks.maskCredential("Authorization", "my-secret"));
    // 键名命中时，即使值是 ${ENV} 引用也不回显（两条判据取并集）
    assertEquals("****KEY}", CredentialMasks.maskCredential("api_key", "${MY_API_KEY}"));
  }

  @Test
  @DisplayName("maskCredential_非敏感键的字面量打码、纯占位符原样回显")
  void maskCredential_masksLiteralsButKeepsEnvReferences() {
    assertEquals("****://h", CredentialMasks.maskCredential("DATABASE_URI", "postgres://h"));
    assertEquals(
        "${MEM0_API_KEY}", CredentialMasks.maskCredential("MEMORY_KEY", "${MEM0_API_KEY}"));
    assertEquals("  ${TOKEN}  ", CredentialMasks.maskCredential("CUSTOM", "  ${TOKEN}  "));
    // 嵌在文本里的占位符不是"一整条引用"，按字面量打码
    assertTrue(CredentialMasks.maskCredential("CUSTOM", "Bearer ${TOKEN}").startsWith("****"));
    // 非敏感键 + null 保持 null
    assertEquals(null, CredentialMasks.maskCredential("CUSTOM", null));
  }

  @Test
  @DisplayName("maskCredentialValues_返回新Map且不篡改入参")
  void maskCredentialValues_returnsNewMapWithoutMutatingInput() {
    Map<String, String> input = new LinkedHashMap<>();
    input.put("token", "abcdef");
    input.put("HOST", "https://example.com");
    input.put("ENDPOINT", "${ENDPOINT}");

    Map<String, String> masked = CredentialMasks.maskCredentialValues(input);

    assertNotSame(input, masked);
    assertEquals("abcdef", input.get("token"));
    assertEquals("****cdef", masked.get("token"));
    assertEquals("****.com", masked.get("HOST"));
    assertEquals("${ENDPOINT}", masked.get("ENDPOINT"));
    assertEquals(Map.of(), CredentialMasks.maskCredentialValues(null));
    assertEquals(Map.of(), CredentialMasks.maskCredentialValues(Map.of()));
  }

  @Test
  @DisplayName("maskWebhookUrl_保留scheme和host，末段path与query打码")
  void maskWebhookUrl_masksLastPathSegmentAndQuery() {
    assertEquals(
        "https://open.feishu.cn/open-apis/bot/v2/hook/****",
        CredentialMasks.maskWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/abc123"));
    assertEquals(
        "https://oapi.dingtalk.com/robot/****?****",
        CredentialMasks.maskWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=xyz"));
    assertEquals(
        "https://example.com/hook/****?****",
        CredentialMasks.maskWebhookUrl("https://example.com/hook/abc?token=1"));
    // 单段 path 保留（便于辨认端点）
    assertEquals(
        "https://example.com/hook", CredentialMasks.maskWebhookUrl("https://example.com/hook"));
    assertEquals("", CredentialMasks.maskWebhookUrl(null));
    assertEquals("", CredentialMasks.maskWebhookUrl("   "));
  }

  @Test
  @DisplayName("maskWebhookUrl_幂等")
  void maskWebhookUrl_isIdempotent() {
    for (String url :
        new String[] {
          "https://open.feishu.cn/open-apis/bot/v2/hook/abc123",
          "https://oapi.dingtalk.com/robot/send?access_token=xyz",
          "https://example.com/hook/****?****",
          ""
        }) {
      String once = CredentialMasks.maskWebhookUrl(url);
      assertEquals(once, CredentialMasks.maskWebhookUrl(once));
    }
  }

  @Test
  @DisplayName("maskWebhookUrl_无scheme的URL也要打码末段（否则凭证原样回显）")
  void maskWebhookUrl_masksSchemeLessUrls() {
    assertEquals(
        "open.feishu.cn/open-apis/bot/v2/hook/****",
        CredentialMasks.maskWebhookUrl("open.feishu.cn/open-apis/bot/v2/hook/abc123"));
    assertEquals(
        "example.com/hook/****?****",
        CredentialMasks.maskWebhookUrl("example.com/hook/abc?token=1"));
  }

  @Test
  @DisplayName("mergeUnchanged_提交掩码等于原值掩码时保留原值")
  void mergeUnchanged_keepsOldValueWhenSubmittedMaskMatches() {
    Map<String, String> existing = Map.of("token", "abcdef");
    Map<String, String> submitted = Map.of("token", "****cdef");
    assertEquals(Map.of("token", "abcdef"), CredentialMasks.mergeUnchanged(existing, submitted));
  }

  @Test
  @DisplayName("mergeUnchanged_新字面量按提交值保存")
  void mergeUnchanged_storesNewLiteral() {
    Map<String, String> existing = Map.of("token", "abcdef");
    Map<String, String> submitted = Map.of("token", "new-secret");
    assertEquals(
        Map.of("token", "new-secret"), CredentialMasks.mergeUnchanged(existing, submitted));
  }

  @Test
  @DisplayName("mergeUnchanged_无对应原值的掩码形状提交点名拒绝")
  void mergeUnchanged_rejectsMaskShapedValueWithoutExistingValue() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> CredentialMasks.mergeUnchanged(Map.of(), Map.of("X-Custom", "****abcd")));
    assertTrue(ex.getMessage().contains("X-Custom"));
    assertFalse(ex.getMessage().contains("****abcd"));
  }

  @Test
  @DisplayName("mergeUnchanged_提交null返回空Map")
  void mergeUnchanged_nullSubmittedIsEmpty() {
    assertEquals(Map.of(), CredentialMasks.mergeUnchanged(Map.of("token", "abcdef"), null));
  }
}
