package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 入站媒体清理的接线漂移闸：{@code InboundMediaJanitor} 不是装配层的定时任务，而是每个媒体下载渠道在自己的 resolver 里各调两处——resolve 入口的
 * {@code sweepIfDue}（TTL 与周期配额）与写盘前的 {@code ensureQuotaOrThrow}。
 *
 * <p>漏掉任一处都是静默故障：TTL 与配额不再生效，而 CI 依然全绿（janitor 自己的单测直接 new，绕过了 resolver；
 * 渠道测试只验证下载成功，不验证清理）。新渠道按命名约定加 {@code *Inbound*Resolver} 时，本闸要求它一并接线。
 *
 * <p>扫源码而不是运行时实例：这两个调用发生在下载路径里，没有可枚举的装配点，删掉调用也不会留下可断言的运行时痕迹。
 */
class InboundMediaJanitorWiringDriftTest {

  private static final String CHANNELS = "channels";

  /** 起点下限：什么都不扫到的用例永远不会红，等于没有。新增渠道时只增不减。 */
  private static final int EXPECTED_RESOLVERS = 11;

  @Test
  @DisplayName("每个 *Inbound*Resolver 都同时调用 sweepIfDue 与 ensureQuotaOrThrow")
  void everyResolverWiresBothJanitorCalls() throws IOException {
    Path repoRoot = repoRoot();
    List<Path> resolvers = resolverSources(repoRoot);

    assertThat(resolvers)
        .as("扫描本身要有效：%s 下至少应有 %d 个 *Inbound*Resolver", CHANNELS, EXPECTED_RESOLVERS)
        .hasSizeGreaterThanOrEqualTo(EXPECTED_RESOLVERS);

    for (Path resolver : resolvers) {
      String source = liveCode(resolver);
      String where = repoRoot.relativize(resolver).toString().replace(File.separatorChar, '/');
      assertThat(source)
          .as("%s 必须在 resolve 入口调用 sweepIfDue，否则 TTL 与周期配额不再生效", where)
          .contains("sweepIfDue(");
      assertThat(source)
          .as("%s 必须在写盘前调用 ensureQuotaOrThrow，否则配额形同虚设", where)
          .contains("ensureQuotaOrThrow(");
    }
  }

  /** 从当前工作目录向上找仓库根（含 {@code channels/} 与 {@code pom.xml}），不假设测试从哪个目录启动。 */
  private static Path repoRoot() {
    Path start = Path.of("").toAbsolutePath().normalize();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve(CHANNELS)) && Files.isRegularFile(dir.resolve("pom.xml"))) {
        return dir;
      }
    }
    throw new IllegalStateException("从 " + start + " 向上找不到含 " + CHANNELS + "/ 与 pom.xml 的仓库根");
  }

  private static List<Path> resolverSources(Path repoRoot) throws IOException {
    try (Stream<Path> files = Files.walk(repoRoot.resolve(CHANNELS))) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> normalize(path).endsWith("Resolver.java"))
          .filter(path -> normalize(path).contains("Inbound"))
          .filter(path -> normalize(path).contains("src/main/java/"))
          .sorted()
          .toList();
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
