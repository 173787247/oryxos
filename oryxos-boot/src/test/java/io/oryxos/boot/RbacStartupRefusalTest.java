package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.oryxos.cli.OryxOsRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 开 RBAC 但配置不完整时，启动必须**失败**（FR-012 的 fail-closed）：
 *
 * <ul>
 *   <li>没开 apikey ⇒ 没有任何门产出主体，授权会静默失效；
 *   <li>库里没有 ADMIN 账号 ⇒ 治理面永久锁死。
 * </ul>
 *
 * <p>校验本身在 {@code RbacStartupCheckTest} 里用单元测试钉着；这里补的是**它真的能拦住启动**：校验是个 {@code
 * ApplicationRunner}，只有整台服务起来才会跑，而"runner 抛异常 ⇒ 启动失败"这条链没有任何测试走过。
 *
 * <p>用程序化启动（而不是 {@code @SpringBootTest}）：本用例要断言的就是"启动失败"，测试上下文机制做不到这一点。
 */
class RbacStartupRefusalTest {

  @Test
  @DisplayName("开 RBAC 但没有 ADMIN 账号：启动被拒绝")
  void startupIsRefusedWithoutAnAdminAccount() {
    Path root = tempWorkspace();

    Throwable failure = catchThrowable(() -> start(root, true));

    assertThat(failure).as("治理面锁死保护：没有 ADMIN 账号时不该起来").isNotNull();
    assertThat(messagesOf(failure))
        .as("失败原因要是那条可执行的指路信息，而不是别的启动问题")
        .anyMatch(message -> message.contains("no ADMIN account found"));
  }

  @Test
  @DisplayName("开 RBAC 但没开 apikey：启动被拒绝")
  void startupIsRefusedWithoutApiKeyAuth() {
    Path root = tempWorkspace();

    Throwable failure = catchThrowable(() -> start(root, false));

    assertThat(failure).as("没有门产出主体时授权会静默失效，属误配，必须拒启").isNotNull();
    assertThat(messagesOf(failure))
        .as("失败原因要是那条可执行的指路信息，而不是别的启动问题")
        .anyMatch(message -> message.contains("but API key auth is off"));
  }

  /**
   * 用**命令行参数**而不是 {@code SpringApplicationBuilder.properties(...)}：后者是"默认属性"，优先级低于 {@code
   * application.yml}，于是 {@code oryxos.providers[0].name=mock} 会被 yml 盖掉，启动在 RBAC 校验之前 就先死于「没有可用的
   * Provider」——那是另一层失败，会让本用例假红/假绿。
   */
  private static void start(Path root, boolean apiKeyEnabled) {
    new SpringApplicationBuilder(OryxOsRuntime.class)
        .web(WebApplicationType.SERVLET)
        .run(
            "--server.port=0",
            "--spring.profiles.active=rbac-refusal-test",
            "--oryxos.root=" + root,
            "--spring.datasource.url=jdbc:sqlite:" + root.resolve("rbac.db"),
            "--oryxos.web.rbac.enabled=true",
            "--oryxos.web.apikey.enabled=" + apiKeyEnabled);
  }

  /** cause 链上的非空消息：Spring 会把 runner 的失败包一层 ISE，真正的原因在 cause 里。 */
  private static java.util.List<String> messagesOf(Throwable failure) {
    java.util.List<String> messages = new java.util.ArrayList<>();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null) {
        messages.add(cause.getMessage());
      }
    }
    return messages;
  }

  private static Path tempWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-rbac-refusal");
      Files.createDirectories(root.resolve("memory"));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
