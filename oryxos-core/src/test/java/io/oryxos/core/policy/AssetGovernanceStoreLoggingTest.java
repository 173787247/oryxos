package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * 治理读不出来时的可观测性契约（041）：fail-open 的取舍不变（读不出来仍按未设治理继续跑，<b>不是</b>拒启）， 但它是「安全控制失效」而不是噪音——按 ERROR
 * 记、带资产身份、同一份坏文件每个进程只报一次。
 *
 * <p>动机：这些加载点会被入站消息门禁（每条消息）与授权判定（每个请求）反复调用，没有节流就会刷屏；而原来的固定文案既没有 级别也没有身份，排障时看不出是哪个资产的门禁悄悄关掉了。
 */
class AssetGovernanceStoreLoggingTest {

  /** 未闭合的 flow sequence：snakeyaml 稳定抛 YAMLException。 */
  private static final String BROKEN_YAML = "owner: [unclosed";

  private static final String VALID_YAML = "owner: alice\n";

  @TempDir Path root;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(AssetGovernanceStore.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    AssetGovernanceStore.clearReportedForTest(); // 静态节流表跨用例隔离
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  private List<ILoggingEvent> errors() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }

  private Path sidecar(String dir, String name) throws Exception {
    Path file = root.resolve(dir).resolve(name).resolve(AssetGovernanceStore.FILE_NAME);
    Files.createDirectories(file.getParent());
    return file;
  }

  @Test
  @DisplayName("坏侧车：一条 ERROR、含资产标识，反复访问不刷屏，且仍按未设治理 fail-open")
  void brokenSidecarLogsOneErrorWithAssetIdentity() throws Exception {
    Files.writeString(sidecar("agents", "bad-agent"), BROKEN_YAML);
    AssetGovernanceStore store = new AssetGovernanceStore(root);

    for (int i = 0; i < 5; i++) {
      // fail-open 语义不变：读不出来 ≠ 拒绝，治理按空处理
      assertThat(store.loadAgent("bad-agent").isPresent()).isFalse();
    }

    List<ILoggingEvent> errors = errors();
    assertThat(errors).hasSize(1); // 每个坏文件每进程一条，不随访问次数增长
    assertThat(errors.get(0).getFormattedMessage())
        .contains("asset=agent:bad-agent") // 定位得到是哪个资产
        .contains(AssetGovernanceStore.FILE_NAME) // 以及哪个文件
        .contains("未设治理"); // 说明仍是 fail-open，不是拒启
  }

  @Test
  @DisplayName("文件修好后再坏：重新报（恢复时清掉节流标记）")
  void reportAgainAfterTheFileIsFixedAndBreaksAgain() throws Exception {
    Path file = sidecar("knowledge", "kb-1");
    Files.writeString(file, BROKEN_YAML);
    AssetGovernanceStore store = new AssetGovernanceStore(root);
    store.loadKnowledge("kb-1");
    assertThat(errors()).hasSize(1);

    Files.writeString(file, VALID_YAML);
    assertThat(store.loadKnowledge("kb-1").owner()).isEqualTo("alice"); // 恢复正常

    Files.writeString(file, BROKEN_YAML);
    store.loadKnowledge("kb-1");
    assertThat(errors()).hasSize(2); // 再坏会重新报，不是一次之后永久静音
  }

  @Test
  @DisplayName("合法配置：零 ERROR")
  void healthySidecarLogsNothing() throws Exception {
    Files.writeString(sidecar("skills", "good-skill"), VALID_YAML);

    AssetGovernance loaded = new AssetGovernanceStore(root).loadSkill("good-skill");

    assertThat(loaded.owner()).isEqualTo("alice");
    assertThat(errors()).isEmpty();
  }

  @Test
  @DisplayName("坏 channels.yaml：一条 ERROR、含渠道名与文件名，不随渠道访问次数刷屏")
  void brokenChannelsFileLogsOneErrorWithChannelIdentity() throws Exception {
    Files.writeString(root.resolve(AssetGovernanceStore.CHANNELS_FILE), "channels: [unclosed");
    AssetGovernanceStore store = new AssetGovernanceStore(root);

    for (int i = 0; i < 3; i++) {
      assertThat(store.loadChannel("mail").isPresent()).isFalse();
    }

    List<ILoggingEvent> errors = errors();
    assertThat(errors).hasSize(1); // 文件级 key：坏的是文件，不是某个渠道
    assertThat(errors.get(0).getFormattedMessage())
        .contains("asset=channel:mail")
        .contains(AssetGovernanceStore.CHANNELS_FILE)
        .contains("未设治理");
  }

  @Test
  @DisplayName("坏治理快照：ERROR 带资产与版本标识；标识缺失时打 <unknown> 且不刷屏")
  void brokenSnapshotLogsAssetIdentity() {
    AssetGovernanceStore.parseSnapshotYaml(BROKEN_YAML, "agent:snap-agent revision=7");
    AssetGovernanceStore.parseSnapshotYaml(BROKEN_YAML, "agent:snap-agent revision=7"); // 重复不再报
    AssetGovernanceStore.parseSnapshotYaml(BROKEN_YAML, null);

    List<ILoggingEvent> errors = errors();
    assertThat(errors).hasSize(2); // 第二次是另一个 throttle key（<unknown>），所以是独立的一条
    assertThat(errors.get(0).getFormattedMessage())
        .contains("asset=agent:snap-agent revision=7")
        .contains("治理版本快照");
    assertThat(errors.get(1).getFormattedMessage()).contains("asset=<unknown>"); // 空标识不写 null
  }
}
