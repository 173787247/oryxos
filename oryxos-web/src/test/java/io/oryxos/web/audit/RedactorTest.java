package io.oryxos.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 021 US3：四类敏感形态掩码 + 不误伤 + 边界安全。 */
class RedactorTest {

  @Test
  void APIKey已知前缀_前4字符加掩码() {
    assertThat(Redactor.redact("调用凭证 sk-abcdef1234567890 已配置")).isEqualTo("调用凭证 sk-a**** 已配置");
    assertThat(Redactor.redact("oryx_YWJjZGVmZ2hpamts")).isEqualTo("oryx****");
  }

  @Test
  void Authorization凭证段掩码() {
    assertThat(Redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload"))
        .isEqualTo("Authorization: Bearer eyJh****");
    assertThat(Redactor.redact("{\"Authorization\":\"Basic dXNlcjpwYXNz\"}"))
        .contains("Basic dXNl****")
        .doesNotContain("dXNlcjpwYXNz");
  }

  @Test
  void URL账密段_口令掩码用户名保留() {
    assertThat(Redactor.redact("https://admin:p4ssw0rd@db.example.com/path"))
        .isEqualTo("https://admin:****@db.example.com/path");
  }

  @Test
  void 敏感字段值掩码_JSON与键值对两种形态() {
    assertThat(Redactor.redact("{\"password\":\"p@ss123\"}"))
        .isEqualTo("{\"password\":\"p@ss****\"}");
    assertThat(Redactor.redact("api_key=deepseek12345")).isEqualTo("api_key=deep****");
    assertThat(Redactor.redact("{\"secret\": \"ab\"}")).isEqualTo("{\"secret\": \"****\"}");
  }

  @Test
  void 转义JSON形态_审计参数内嵌字符串同样掩码() {
    // 工具参数落库常为字符串内嵌 JSON：{"content":"配置 \"password\":\"p@ss123\""}
    String escaped = "{\"content\":\"配置 \\\"password\\\":\\\"p@ss123\\\" 完成\"}";
    String redacted = Redactor.redact(escaped);
    assertThat(redacted).contains("p@ss****").doesNotContain("p@ss123");
  }

  @Test
  void 普通内容不误伤() {
    String json = "{\"content\":\"记住我喜欢咖啡\",\"totalTokens\":876}";
    assertThat(Redactor.redact(json)).isEqualTo(json);
    String url = "https://example.com/docs?page=1";
    assertThat(Redactor.redact(url)).isEqualTo(url);
    String chinese = "工具执行完成：写入 MEMORY.md 成功";
    assertThat(Redactor.redact(chinese)).isEqualTo(chinese);
  }

  @Test
  void 混合文本_只掩敏感段() {
    String mixed = "记录 {\"password\":\"topsecret9\"} 与普通内容，凭证 sk-abcdefgh12345678";
    String redacted = Redactor.redact(mixed);
    assertThat(redacted)
        .contains("\"password\":\"tops****\"")
        .contains("sk-a****")
        .contains("与普通内容")
        .doesNotContain("topsecret9")
        .doesNotContain("abcdefgh12345678");
  }

  @Test
  void null与空串安全() {
    assertThat(Redactor.redact(null)).isNull();
    assertThat(Redactor.redact("")).isEmpty();
  }
}
