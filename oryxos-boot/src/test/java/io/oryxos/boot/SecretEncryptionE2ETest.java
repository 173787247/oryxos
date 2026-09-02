package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmProviderRepository;
import io.oryxos.storage.NotifyChannelRepository;
import io.oryxos.storage.SecretMigration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 022 端到端：真实 HTTP + SQLite——落库即密文（SC-001）、功能回归（SC-002）、零配置首启与 master.key
 * 自动生成（SC-003）、存量明文迁移（FR-005）。密钥错误拒启由 SecretStorageTest 直调守卫断言（@SpringBootTest 单上下文测不了启动失败），真机拒启走
 * quickstart V5。无 key、无网络、gate 内可跑。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretEncryptionE2ETest {

  private static final Path ROOT = seedWorkspace();
  private static final String CIPHERTEXT_PREFIX = "enc:v1:";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmProviderRepository providers;
  @Autowired private NotifyChannelRepository channels;
  @Autowired private SecretMigration secretMigration;
  @Autowired private io.oryxos.core.secret.SecretCipher cipher;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-secret-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("agent-a"));
      Files.writeString(
          root.resolve("agents/agent-a/AGENT.md"),
          """
          ---
          name: agent-a
          description: secret 走查 Agent
          identity:
            agent_name: 小欧
            prompt: 你是一个测试助手。
          provider:
            name: mock
            model: mock-model
          tools:
            - save_memory
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---
          你是一个测试助手，被触发时正常回应。
          """);
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("secret-e2e.db"));
    registry.add("oryxos.root", ROOT::toString);
  }

  @Test
  @Order(1)
  void 录入凭证_落库即密文_读回明文() {
    post(
        "/api/v1/providers",
        "{\"name\":\"p-e2e\",\"apiKey\":\"sk-e2e-real-key\",\"baseUrl\":\"https://api.example.com\"}");
    post(
        "/api/v1/notify-channels",
        "{\"name\":\"mail-e2e\",\"type\":\"email\",\"url\":\"smtp://h\","
            + "\"config\":{\"host\":\"smtp.example.com\",\"port\":\"465\",\"from\":\"a@b.c\","
            + "\"to\":\"ops@b.c\",\"password\":\"p@ss-e2e\"}}");

    // SC-001：库中列值只见密文，无任何明文
    String storedKey = providers.findById("p-e2e").orElseThrow().getApiKey();
    assertTrue(storedKey.startsWith(CIPHERTEXT_PREFIX), "api_key 落库应为密文: " + storedKey);
    assertFalse(storedKey.contains("sk-e2e-real-key"));
    String storedConfig = channels.findById("mail-e2e").orElseThrow().getConfig();
    assertFalse(storedConfig.contains("p@ss-e2e"), "config 敏感项落库应为密文");
    assertTrue(storedConfig.contains(CIPHERTEXT_PREFIX));
    assertTrue(storedConfig.contains("smtp.example.com"), "普通项保持明文可读");

    // US3（T017 收口）：渠道查询接口敏感项为掩码、全响应无明文（SC-005）
    ResponseEntity<String> detail =
        rest.getForEntity("/api/v1/notify-channels/mail-e2e", String.class);
    assertEquals(HttpStatus.OK, detail.getStatusCode());
    assertFalse(detail.getBody().contains("p@ss-e2e"), "查询响应不得含明文密码");
    JsonNode channel = readJson(detail.getBody()).get("data");
    assertEquals("****-e2e", channel.get("config").get("password").asText()); // ****+末4位
    assertEquals("smtp.example.com", channel.get("config").get("host").asText()); // 普通项原样
  }

  @Test
  @Order(2)
  void 功能回归_mock对话全链照常() {
    // SC-002：加密收口后 Agent 全链（读 provider → LLM 调用 → 工具）行为不变
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/agent-a/invoke",
            new HttpEntity<>("{\"content\":\"记住我喜欢咖啡\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(
        "好的，已经按你的要求记录并处理完成。", readJson(response.getBody()).get("data").get("reply").asText());
  }

  @Test
  @Order(3)
  void 存量明文_迁移生效且幂等() {
    // 模拟升级前旧库：手工把行改回明文（绕过注册表直写 entity）
    var legacy = providers.findById("p-e2e").orElseThrow();
    legacy.setApiKey("sk-downgraded-plaintext");
    providers.save(legacy);

    secretMigration.run(); // 等价于一次重启的启动迁移

    String migrated = providers.findById("p-e2e").orElseThrow().getApiKey();
    assertTrue(migrated.startsWith(CIPHERTEXT_PREFIX), "明文行应被迁移加密");
    assertFalse(migrated.contains("sk-downgraded-plaintext"));
    secretMigration.run(); // 幂等：再跑不变
    assertEquals(migrated, providers.findById("p-e2e").orElseThrow().getApiKey());
  }

  @Test
  @Order(4)
  void 零配置首启_masterKey自动生成且权限0600() throws Exception {
    // SC-003：本 E2E 全程未配置 ORYXOS_MASTER_KEY——密钥文件已在上下文启动时自动生成
    Path keyFile = ROOT.resolve("master.key");
    assertTrue(Files.exists(keyFile), "首启应自动生成 master.key");
    var perms = Files.getPosixFilePermissions(keyFile);
    assertEquals(
        java.util.Set.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        perms,
        "master.key 权限应为 0600");
    byte[] key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
    assertEquals(32, key.length, "密钥应为 Base64 的 32 字节");
  }

  @Test
  @Order(5)
  void 掩码原样PUT_原值保留_新值PUT生效() throws Exception {
    // 掩码原样提交 = 未修改：解密后仍为原密码（随机 IV 使密文字节每次不同，比对解密值才是正确口径）
    put(
        "/api/v1/notify-channels/mail-e2e",
        "{\"type\":\"email\",\"url\":\"smtp://h\",\"config\":{\"host\":\"smtp.example.com\","
            + "\"port\":\"465\",\"from\":\"a@b.c\",\"to\":\"ops@b.c\",\"password\":\"****-e2e\"}}");
    assertEquals("p@ss-e2e", storedPassword(), "掩码提交后原密码必须保留");

    // 新值提交 → 生效（读出为新明文，库中为新密文）
    put(
        "/api/v1/notify-channels/mail-e2e",
        "{\"type\":\"email\",\"url\":\"smtp://h\",\"config\":{\"host\":\"smtp.example.com\","
            + "\"port\":\"465\",\"from\":\"a@b.c\",\"to\":\"ops@b.c\",\"password\":\"new-p@ss-9\"}}");
    String storedAfter = channels.findById("mail-e2e").orElseThrow().getConfig();
    assertFalse(storedAfter.contains("new-p@ss-9"), "新密码同样密文落库");
    JsonNode updated = getJson("/api/v1/notify-channels/mail-e2e").get("data");
    assertEquals("****ss-9", updated.get("config").get("password").asText());
  }

  // —— helpers ——

  /** 直查库中 config JSON 的 password 密文并解密（断言"值"而非"密文字节"）。 */
  private String storedPassword() throws IOException {
    String configJson = channels.findById("mail-e2e").orElseThrow().getConfig();
    return cipher.decrypt(mapper.readTree(configJson).get("password").asText());
  }

  private void put(String path, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.exchange(
            path,
            org.springframework.http.HttpMethod.PUT,
            new HttpEntity<>(body, headers),
            String.class);
    assertEquals(
        HttpStatus.OK, response.getStatusCode(), "PUT " + path + " → " + response.getBody());
  }

  private void post(String path, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    assertEquals(
        HttpStatus.OK, response.getStatusCode(), "POST " + path + " → " + response.getBody());
  }

  private JsonNode getJson(String path) {
    ResponseEntity<String> response = rest.getForEntity(path, String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return readJson(response.getBody());
  }

  private JsonNode readJson(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("invalid json: " + raw, e);
    }
  }
}
