package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.auth.Role;
import io.oryxos.storage.WebUserService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code oryxos.web.rbac.enabled=true} 的**启动**路径：这是最容易"打开开关才发现起不来"的地方。
 *
 * <p>039 SC-005 的启动检查（{@code RbacEndpointCoverageCheck}）会枚举真实 handler，任何受保护前缀上解析不到动作的
 * 端点都会让它拒绝启动。全仓此前**没有测试真的把 RBAC 打开过**：只有单元级的 {@code RbacStartupCheckTest} 与不启动 上下文的规则测试 —— 于是 #968
 * 那种"新增控制器 ⇒ 一开 RBAC 就起不来"只能等用户打开开关时暴露。
 *
 * <p>这里启动整台服务并断言两件事：上下文能起来（端点覆盖检查通过），受保护路径的鉴权是活的。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = {OryxOsRuntime.class, RbacEnabledStartupTest.SeedAdmin.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oryxos.providers[0].name=mock",
      "oryxos.web.rbac.enabled=true",
      "oryxos.web.apikey.enabled=true"
    })
class RbacEnabledStartupTest {

  private static final Path ROOT = tempWorkspace();

  @Autowired private TestRestTemplate rest;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("rbac.db"));
  }

  @Test
  @DisplayName("RBAC 打开时整台服务能启动")
  void contextStartsWithRbacEnabled() {
    ResponseEntity<String> health = rest.getForEntity("/api/v1/health", String.class);

    assertThat(health.getStatusCode().is2xxSuccessful())
        .as("服务确实在响应；走到这一步同时说明 RbacEndpointCoverageCheck 没有拒绝启动")
        .isTrue();
  }

  @Test
  @DisplayName("受保护路径在 RBAC 下要求认证，而不是 404 或 500")
  void protectedEndpointRequiresAuthentication() {
    ResponseEntity<String> response = rest.getForEntity("/api/v1/capabilities", String.class);

    assertThat(response.getStatusCode())
        .as("未认证访问受保护端点：端点是映射的（不是 404），认证/授权闸是活的（401/403）")
        .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
  }

  /**
   * 在 {@code RbacStartupCheck} 之前播种一个 ADMIN 账号 —— 否则上下文会按 FR-012 fail-fast，那是 {@link
   * RbacStartupRefusalTest} 覆盖的另一半。
   *
   * <p>播种必须是**单例实例化期**的一部分：Spring 先实例化全部单例、再统一回调 {@code
   * SmartInitializingSingleton.afterSingletonsInstantiated()}，所以构造器里的播种一定早于启动校验。原先用 {@code Ordered}
   * 排 {@code ApplicationRunner} 的写法在检查提前后不再成立（runner 在 refresh 之后才跑，也就是在拒启之后）。
   */
  @TestConfiguration
  static class SeedAdmin {

    @Bean
    SeedAdminAccount seedAdminUser(WebUserService users) {
      return new SeedAdminAccount(users);
    }
  }

  /** 单例实例化即播种：早于所有 {@code afterSingletonsInstantiated} 回调。 */
  static final class SeedAdminAccount {

    SeedAdminAccount(WebUserService users) {
      if (!users.hasAdminAccount()) {
        users.create("admin", "admin-pass-123");
        users.setRoles("admin", Set.of(Role.ADMIN));
      }
    }
  }

  private static Path tempWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-rbac-on");
      Files.createDirectories(root.resolve("memory"));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
