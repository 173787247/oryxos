package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 039 授权基座契约测试（资源引用侧）：把「动作作用在什么上」的类型词表钉死。
 *
 * <p>为什么值得单独测一个只有两个字段的 record：{@code type} 是拒绝审计里的定位键——它同时出现在 {@code
 * AuthorizationService.Decision.reason()} 与日志里。词表一旦出现拼写漂移或两个工厂复用同一个常量， 审计记录就会静默地把「改 Agent
 * 定义」和「改渠道配置」记成同一种东西，事后无法区分。 这类缺陷不会让任何请求失败，只会让审计失真，因此必须靠断言守住。
 */
class ResourceRefTest {

  private static final String SAMPLE_NAME = "ops-agent";
  private static final String SAMPLE_ID = "member-bob";

  /** 空白 id：用于断言 describe() 不产出无法解析的 "type: " 形态。 */
  private static final String BLANK_ID = "   ";

  @Test
  @DisplayName("带标识的工厂_类型与标识都落到契约规定的位置")
  void factoriesWithIdentityKeepTypeAndId() {
    // 为什么逐个断言 type 与 id：这是调用方与审计之间的唯一约定。
    // 若 agent() 误用 TYPE_SKILL，授权裁决本身不会报错，但审计会指向错误的资源类别。
    assertThat(ResourceRef.agent(SAMPLE_NAME).type()).isEqualTo(ResourceRef.TYPE_AGENT);
    assertThat(ResourceRef.agent(SAMPLE_NAME).id()).isEqualTo(SAMPLE_NAME);

    assertThat(ResourceRef.knowledge(SAMPLE_NAME).type()).isEqualTo(ResourceRef.TYPE_KNOWLEDGE);
    assertThat(ResourceRef.knowledge(SAMPLE_NAME).id()).isEqualTo(SAMPLE_NAME);

    assertThat(ResourceRef.skill(SAMPLE_NAME).type()).isEqualTo(ResourceRef.TYPE_SKILL);
    assertThat(ResourceRef.skill(SAMPLE_NAME).id()).isEqualTo(SAMPLE_NAME);

    assertThat(ResourceRef.channel(SAMPLE_NAME).type()).isEqualTo(ResourceRef.TYPE_CHANNEL);
    assertThat(ResourceRef.channel(SAMPLE_NAME).id()).isEqualTo(SAMPLE_NAME);

    assertThat(ResourceRef.session(SAMPLE_NAME).type()).isEqualTo(ResourceRef.TYPE_SESSION);
    assertThat(ResourceRef.session(SAMPLE_NAME).id()).isEqualTo(SAMPLE_NAME);

    assertThat(ResourceRef.member(SAMPLE_ID).type()).isEqualTo(ResourceRef.TYPE_MEMBER);
    assertThat(ResourceRef.member(SAMPLE_ID).id()).isEqualTo(SAMPLE_ID);
  }

  @Test
  @DisplayName("整体性资源_无标识_描述退化为纯类型")
  void wholeBoundaryFactoriesCarryNoIdentity() {
    // 为什么断言 id 为 null：workspace/audit/policy 是「整个面」而不是某一条记录。
    // 若给它们编造一个 id，审计里会出现看似精确、实则无意义的定位信息。
    assertThat(ResourceRef.workspace().type()).isEqualTo(ResourceRef.TYPE_WORKSPACE);
    assertThat(ResourceRef.workspace().id()).isNull();
    assertThat(ResourceRef.workspace().describe()).isEqualTo(ResourceRef.TYPE_WORKSPACE);

    assertThat(ResourceRef.audit().type()).isEqualTo(ResourceRef.TYPE_AUDIT);
    assertThat(ResourceRef.audit().id()).isNull();
    assertThat(ResourceRef.audit().describe()).isEqualTo(ResourceRef.TYPE_AUDIT);

    assertThat(ResourceRef.policy().type()).isEqualTo(ResourceRef.TYPE_POLICY);
    assertThat(ResourceRef.policy().id()).isNull();
    assertThat(ResourceRef.policy().describe()).isEqualTo(ResourceRef.TYPE_POLICY);
  }

  @Test
  @DisplayName("describe_有标识时是type冒号id_便于审计定位")
  void describeJoinsTypeAndIdentity() {
    // 为什么断言这个精确格式：它是人读审计时的定位串（"改的是哪个 Agent"），
    // 格式漂移会让既有的日志检索/看板匹配失效。
    assertThat(ResourceRef.agent(SAMPLE_NAME).describe())
        .isEqualTo(ResourceRef.TYPE_AGENT + ":" + SAMPLE_NAME);
    assertThat(ResourceRef.member(SAMPLE_ID).describe())
        .isEqualTo(ResourceRef.TYPE_MEMBER + ":" + SAMPLE_ID);
  }

  @Test
  @DisplayName("describe_标识为空白时退化为纯类型_不产出无法解析的串")
  void describeDegradesForBlankIdentity() {
    ResourceRef blank = new ResourceRef(ResourceRef.TYPE_AGENT, BLANK_ID);

    // 为什么：调用点上「拿到空串」比「拿到 null」更常见（上游 trim 之后的结果）。
    // 若直接拼接会得到 "agent:   "，审计里既看不出是哪个 Agent，也无法用类型精确检索。
    assertThat(blank.describe()).isEqualTo(ResourceRef.TYPE_AGENT);
  }

  @Test
  @DisplayName("九个类型常量互不相同_防止复制粘贴导致审计口径合并")
  void typeVocabularyIsDistinct() {
    Set<String> vocabulary =
        Set.of(
            ResourceRef.TYPE_WORKSPACE,
            ResourceRef.TYPE_AGENT,
            ResourceRef.TYPE_KNOWLEDGE,
            ResourceRef.TYPE_SKILL,
            ResourceRef.TYPE_CHANNEL,
            ResourceRef.TYPE_SESSION,
            ResourceRef.TYPE_AUDIT,
            ResourceRef.TYPE_POLICY,
            ResourceRef.TYPE_MEMBER);

    // 为什么断言基数 9：新增资源类型时若复制了上一个常量，两个不同的资源类别会在审计里合并成一种。
    // 这种错误不产生任何异常，只让审计失真——所以必须由断言而非评审来守。
    assertThat(vocabulary).hasSize(9);
  }

  @Test
  @DisplayName("record 值语义_类型与标识相同即相等")
  void valueSemantics() {
    // 为什么：主体/资源要能作为 Map 键或参与审计去重，引用语义会让同一资源的两次记录无法聚合。
    assertThat(ResourceRef.agent(SAMPLE_NAME)).isEqualTo(ResourceRef.agent(SAMPLE_NAME));
    assertThat(ResourceRef.agent(SAMPLE_NAME)).hasSameHashCodeAs(ResourceRef.agent(SAMPLE_NAME));
    // 反向断言：不同资源类别不得相等（避免只比 id 的实现漏掉 type）。
    assertThat(ResourceRef.agent(SAMPLE_NAME)).isNotEqualTo(ResourceRef.skill(SAMPLE_NAME));
  }
}
