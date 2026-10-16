package io.oryxos.core.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 022 R5 的敏感配置名录：storage 侧加密（{@code JpaNotifyChannelRegistry} 落库、{@code SecretMigration} 存量迁移）与 web
 * 侧掩码（{@code NotifyChannelView}）共用这一个判定——它错了，两边一起错。
 *
 * <p>本测试钉住三件事：① <b>名录内容</b>（多一项就多加密一项，改它必须是有意识的决定）；② <b>判定口径 = 整键全等 + 只折大小写</b>（不裁剪空白、不做子串、不做
 * {@code -}/{@code _} 归一化——后两种是 021 {@code Redactor} 与 {@code AgentRunEventPayloads}
 * 各自层的匹配方式，与本判定同源的是名单，不是匹配语义）； ③ null 安全。
 */
class SensitiveConfigKeysTest {

  /** 022 R5 / data-model.md 定稿的名录（大小写不敏感）。 */
  private static final Set<String> ROSTER =
      Set.of("password", "passwd", "secret", "token", "api_key", "apikey", "access_key");

  /**
   * 各 {@code NotifyChannelAdapter} 从 config 读凭证用的键名：email 用 password，飞书/钉钉用 secret，
   * 其余（slack/matrix/telegram/whatsapp/qq/discord）用 token——都在名录内，所以凭证不会明文落库。
   */
  @Test
  void 名录内每一项都命中() {
    assertThat(ROSTER).hasSize(7);
    for (String key : ROSTER) {
      assertThat(SensitiveConfigKeys.isSensitive(key)).as("名录项 %s", key).isTrue();
    }
  }

  @Test
  void 大小写不敏感() {
    assertThat(SensitiveConfigKeys.isSensitive("PASSWORD")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("PassWord")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("Passwd")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("SECRET")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("Token")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("API_KEY")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("Api_Key")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("ApiKey")).isTrue();
    assertThat(SensitiveConfigKeys.isSensitive("ACCESS_KEY")).isTrue();
  }

  @Test
  void 折大小写用LocaleROOT_土耳其语默认区域下同样命中() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      // 'I'.toLowerCase(tr) = 'ı'：若哪天有人把 Locale.ROOT 改成无参 toLowerCase()，这两条会当场变红
      assertThat(SensitiveConfigKeys.isSensitive("API_KEY")).isTrue();
      assertThat(SensitiveConfigKeys.isSensitive("ApI_KeY")).isTrue();
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void 名录外的普通配置项不命中_保持明文可读() {
    // 022 R5：名录外（host/port/from/…）保持明文——不过度加密，排障现场完整
    List<String> plainKeys =
        List.of(
            "host",
            "port",
            "from",
            "to",
            "username",
            "subject",
            "encryption",
            "format",
            "msg_type",
            "title",
            "url",
            "chat_id",
            "webhook");
    for (String key : plainKeys) {
      assertThat(SensitiveConfigKeys.isSensitive(key)).as("名录外 %s", key).isFalse();
    }
  }

  @Test
  void webhook地址整条不命中_R5划定的边界() {
    // 飞书/企微/钉钉机器人地址内嵌 token，R5 明确「本版本不加密不掩码」（加密则列表页/编辑页无法完整回显）
    assertThat(SensitiveConfigKeys.isSensitive("url")).isFalse();
  }

  @Test
  void 判定是整键全等_不做子串也不做归一化() {
    // 键名里「含有」敏感词不算命中：这决定了 bot_token / client_secret / api-key 这类键名不会被 022 加密。
    // 要升级为语义匹配 = 改变已有部署的落库形态，属产品裁决（R5 已定：名录内置不可配），不是顺手扩的。
    List<String> nearMiss =
        List.of(
            "bot_token",
            "client_secret",
            "accessToken",
            "api-key",
            "token_expiry",
            "my_secret",
            "secretary");
    for (String key : nearMiss) {
      assertThat(SensitiveConfigKeys.isSensitive(key)).as("近似键名 %s", key).isFalse();
    }
  }

  @Test
  void 另两层脱敏名录的键不自动进入本名录() {
    // AgentRunEventPayloads（审计载荷脱敏）另有 authorization/cookie/bearer 等项；
    // 本名录只服务「通知渠道 config 的落库与回显」，不存在这些键，故不随对方扩张
    assertThat(SensitiveConfigKeys.isSensitive("authorization")).isFalse();
    assertThat(SensitiveConfigKeys.isSensitive("cookie")).isFalse();
    assertThat(SensitiveConfigKeys.isSensitive("bearer")).isFalse();
  }

  @Test
  void 不裁剪空白_键名必须整串相等() {
    assertThat(SensitiveConfigKeys.isSensitive(" token")).isFalse();
    assertThat(SensitiveConfigKeys.isSensitive("token ")).isFalse();
    assertThat(SensitiveConfigKeys.isSensitive(" api_key ")).isFalse();
  }

  @Test
  void null与空串不命中() {
    assertThat(SensitiveConfigKeys.isSensitive(null)).isFalse();
    assertThat(SensitiveConfigKeys.isSensitive("")).isFalse();
  }
}
