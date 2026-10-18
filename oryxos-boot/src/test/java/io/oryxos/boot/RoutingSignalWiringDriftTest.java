package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由信号生产者的**反向漂移闸**：residency / difficulty 控制依赖 {@code RoutingContext.open(...)} 提供每轮信号，而 生产代码里零调用
 * ⇒ {@code RoutingSignalStartupCheck} 会对"routing canary 开 + 策略已配"发 WARN，说明这些控制静默失效。 本测试断言生产源码里
 * {@code RoutingContext.open} 零次。
 *
 * <p><b>本测试变红 = 生产代码里出现了 RoutingContext.open(...)，即有人接上了生产者。</b>这不是"更新期望值"就能过的漂移， 必须在同一个改动里完成下面四件事：
 *
 * <ol>
 *   <li>删掉 {@code RoutingSignalStartupCheck} 的 WARN（信号已存在，它从此会误导）；
 *   <li>删除本测试，或把它改成"生产者存在且被消费"的正向断言；
 *   <li>把 {@code ModelRoutingServiceTest} / {@code ProviderRoutingPrimaryTest} 里用 mock 信号验证
 *       residency / difficulty 的用例换成真实生产者的端到端断言；
 *   <li>在 PR 正文说明生产者从哪来（AGENT.md 字段 / profile / 请求头）。
 * </ol>
 *
 * <p>照 #994 {@code InboundMediaJanitorWiringDriftTest} 的做法：扫源码而不是运行时实例（"没有调用者"在运行时没有可断言的痕迹）、
 * 先剥注释再判（把调用注释掉不算接上，裸 contains 会被注释骗过），并设扫描文件数下限防空集假绿。
 */
class RoutingSignalWiringDriftTest {

  /** 排除 routing 包自身（{@code RoutingContext} 的定义处；它不该被当成生产者调用）。 */
  private static final String ROUTING_PACKAGE = "io/oryxos/core/routing/";

  /** 扫描下限：当前约 960 个生产 java 文件；空集扫不出任何东西，那样这个闸永远绿，等于没有。 */
  private static final int EXPECTED_SOURCES = 800;

  @Test
  @DisplayName("生产源码里没有任何 RoutingContext.open 调用（若变红，按类注释 1-2-3-4 处理）")
  void productionCodeNeverOpensRoutingContext() throws IOException {
    Path repoRoot = repoRoot();
    List<Path> sources = productionSources(repoRoot);

    assertThat(sources)
        .as("扫描本身要有效：%s 下至少应有 %d 个生产 java 文件", repoRoot, EXPECTED_SOURCES)
        .hasSizeGreaterThanOrEqualTo(EXPECTED_SOURCES);

    for (Path source : sources) {
      String where = repoRoot.relativize(source).toString().replace(File.separatorChar, '/');
      assertThat(liveCode(source))
          .as(
              "%s 调用了 RoutingContext.open：生产者已接上，请在同一改动里删掉 RoutingSignalStartupCheck 的 WARN、"
                  + "更新本测试、并把 mock 信号用例换成真实端到端断言（见本类注释 1-2-3-4）",
              where)
          .doesNotContain("RoutingContext.open(");
    }
  }

  /** 从当前工作目录向上找仓库根（含 {@code pom.xml} 与 {@code oryxos-core/}），不假设测试从哪个目录启动。 */
  private static Path repoRoot() {
    Path start = Path.of("").toAbsolutePath().normalize();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("pom.xml"))
          && Files.isDirectory(dir.resolve("oryxos-core"))) {
        return dir;
      }
    }
    throw new IllegalStateException("从 " + start + " 向上找不到含 pom.xml 与 oryxos-core/ 的仓库根");
  }

  /**
   * 所有模块的生产源码：顶层模块的 {@code src/main/java} + {@code channels/*} 的 {@code src/main/java}。只走源码目录，不趟
   * {@code target/} 与前端 {@code node_modules/}。
   */
  private static List<Path> productionSources(Path repoRoot) throws IOException {
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> topLevel = Files.list(repoRoot)) {
      for (Path module : topLevel.filter(Files::isDirectory).sorted().toList()) {
        collectJava(module.resolve("src/main/java"), sources);
        if ("channels".equals(module.getFileName().toString())) {
          try (Stream<Path> channelModules = Files.list(module)) {
            for (Path channel : channelModules.filter(Files::isDirectory).sorted().toList()) {
              collectJava(channel.resolve("src/main/java"), sources);
            }
          }
        }
      }
    }
    return sources;
  }

  private static void collectJava(Path sourceRoot, List<Path> out) throws IOException {
    if (!Files.isDirectory(sourceRoot)) {
      return;
    }
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> normalize(path).endsWith(".java"))
          .filter(path -> !normalize(path).contains(ROUTING_PACKAGE))
          .sorted()
          .forEach(out::add);
    }
  }

  private static String normalize(Path path) {
    return path.toString().replace(File.separatorChar, '/');
  }

  /** 去掉注释后再判：被注释掉的调用不算接线（否则把调用改成 // ... 就能骗过本闸）。 */
  private static String liveCode(Path sourceFile) throws IOException {
    return Files.readString(sourceFile)
        .replaceAll("(?s)/\\*.*?\\*/", "")
        .replaceAll("//[^\\n]*", "");
  }
}
