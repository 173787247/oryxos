package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.ProfileValidationException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * specs/011-agent-lifecycle 验收 harness：AgentLifecycleServiceTest——编排顺序 + 失败回滚 + 删除时序；025
 * persona/导入链契约。
 */
class AgentLifecycleServiceTest {

  private static final String MD =
      "---\nname: demo\nprovider:\n  name: deepseek\n  model: m\n---\n正文";

  private AgentLoader agentLoader;
  private ProfileRegistry profileRegistry;
  private AgentScheduler agentScheduler;
  private AgentStore agentStore;
  private AgentLifecycleService service;

  @BeforeEach
  void setUp() {
    agentLoader = mock(AgentLoader.class);
    profileRegistry = mock(ProfileRegistry.class);
    agentScheduler = mock(AgentScheduler.class);
    agentStore = mock(AgentStore.class);
    service =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat",
            java.util.Map.of(),
            mock(io.oryxos.core.notify.NotifyChannelRegistry.class));
  }

  private static Profile profile(String name, ScheduleConfig... schedules) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedules),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("create 按序：脚手架写目录 → 派生 → 注册")
  void create_scaffoldsThenRegisters() throws Exception {
    Path dir = Path.of("agents", "demo");
    when(profileRegistry.exists("demo")).thenReturn(false);
    when(agentStore.writeAll(eq("demo"), any())).thenReturn(dir);
    Profile p = profile("demo");
    doReturn(p).when(agentLoader).deriveProfile(dir);

    assertSame(p, service.create("demo", "一个测试 Agent"));

    InOrder o = inOrder(agentStore, profileRegistry);
    o.verify(agentStore).writeAll(eq("demo"), any()); // 后台按模板脚手架出完整目录
    o.verify(profileRegistry).register(p);
    verify(agentScheduler, never()).registerProfile(any()); // 无 schedules
  }

  @Test
  @DisplayName("name 冲突第一步就拒、一个目录都不写")
  void create_nameConflict_rejectedBeforeAnyWrite() {
    when(profileRegistry.exists("demo")).thenReturn(true);

    assertThrows(IllegalArgumentException.class, () -> service.create("demo", "x"));

    verify(agentStore, never()).writeAll(any(), any());
  }

  @Test
  @DisplayName("注册失败_回滚已写的Agent目录_不留半个Agent")
  void create_registerFails_rollsBackWrittenDir() throws Exception {
    Path dir = Path.of("agents", "half");
    when(profileRegistry.exists("half")).thenReturn(false);
    when(agentStore.writeAll(eq("half"), any())).thenReturn(dir);
    doReturn(profile("half")).when(agentLoader).deriveProfile(dir);
    doThrow(new ProfileValidationException("bad")).when(profileRegistry).register(any());

    assertThrows(ProfileValidationException.class, () -> service.create("half", "x"));

    verify(agentStore).delete(dir); // 已写目录被删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
  }

  @Test
  @DisplayName("register 带 schedules 的 Agent 注册定时（三录入同一段代码）")
  void register_withSchedules_registersTimer() throws Exception {
    Path dir = Path.of("agents", "cron");
    Profile p = profile("cron", new ScheduleConfig("m", "m", "0 0 9 * * *", "Asia/Shanghai", "x"));
    doReturn(p).when(agentLoader).deriveProfile(dir);

    service.register(dir);

    verify(profileRegistry).register(p);
    verify(agentScheduler).registerProfile(p);
  }

  @Test
  @DisplayName("删除必须先停定时_再动索引和目录")
  void delete_unregistersThenRemovesThenArchives() {
    Profile p =
        profile("weather-daily", new ScheduleConfig("m", "m", "0 0 9 * * *", "Asia/Shanghai", "x"));
    when(profileRegistry.get("weather-daily")).thenReturn(Optional.of(p));

    service.delete("weather-daily");

    InOrder o = inOrder(agentScheduler, profileRegistry, agentStore);
    o.verify(agentScheduler).unregisterProfile(p); // 顺序反了：定时还在跑、Profile 已没 → 触发空指针
    o.verify(profileRegistry).remove("weather-daily");
    o.verify(agentStore).archive("weather-daily");
  }

  @Test
  @DisplayName("update 改 schedules 先注销旧再注册新")
  void update_scheduleChanged_unregistersBeforeRegister() throws Exception {
    Profile old = profile("w", new ScheduleConfig("m", "m", "0 0 9 * * *", "Asia/Shanghai", "旧"));
    Profile updated =
        profile("w", new ScheduleConfig("m", "m", "0 0 10 * * *", "Asia/Shanghai", "新"));
    when(profileRegistry.get("w")).thenReturn(Optional.of(old));
    Path dir = Path.of("agents", "w");
    when(agentStore.writeAll(eq("w"), any())).thenReturn(dir);
    doReturn(updated).when(agentLoader).deriveProfile(dir);

    service.update("w", MD);

    InOrder o = inOrder(agentScheduler);
    o.verify(agentScheduler).unregisterProfile(old); // 先注销旧句柄
    o.verify(agentScheduler).registerProfile(updated); // 再注册新的
  }

  @Test
  @DisplayName("运行期更新拒绝 legacy skills，迁移职责不会泄漏到普通 CRUD")
  void update_rejectsLegacySkills() {
    String legacy = MD.replace("---\n正文", "skills:\n  - report-format\n---\n正文");

    assertThrows(IllegalArgumentException.class, () -> service.update("w", legacy));
    verify(agentStore, never()).writeAll(any(), any());
  }

  @Test
  @DisplayName("updateBasicInfo 改 description/provider/model，正文与其它字段保留")
  void updateBasicInfo_editsFrontmatterPreservesBodyAndOtherKeys() throws Exception {
    when(agentStore.read("demo")).thenReturn(MD);
    Path dir = Path.of("agents", "demo");
    when(agentStore.writeAll(eq("demo"), any())).thenReturn(dir);
    Profile updated = profile("demo");
    doReturn(updated).when(agentLoader).parse(any(), eq("demo"));
    doReturn(updated).when(agentLoader).deriveProfile(dir);

    service.updateBasicInfo("demo", "新描述", "openai", "gpt-4o");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Map<String, String>> filesCaptor =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(agentStore).writeAll(eq("demo"), filesCaptor.capture());
    String written = filesCaptor.getValue().get("AGENT.md");
    assertTrue(written.contains("description: 新描述"), "description 被更新");
    assertTrue(written.contains("name: openai"), "provider.name 被更新");
    assertTrue(written.contains("model: gpt-4o"), "provider.model 被更新");
    assertTrue(!written.contains("skills:"), "Skill 绑定不写回 AGENT.md");
    assertTrue(written.contains("name: demo"), "name 保留");
    assertTrue(written.contains("正文"), "正文保留");
  }

  @Test
  @DisplayName("025：生成草稿缺 persona 时兜底填入默认人格（契约三）")
  void ensurePersona_fillsDefaultWhenMissing() {
    String draft =
        "---\nname: demo\nidentity:\n  agent_name: 演示\n  prompt: 你好\nprovider:\n  name: deepseek\n  model: deepseek-v4-flash\n---\n正文";
    String saved = service.ensurePersona(draft);
    Profile p = new AgentLoader(Path.of("."), java.util.Set.of("deepseek")).parse(saved, "demo");
    assertNotNull(p.persona());
    assertEquals("乐于助人的助手", p.persona().role());
  }

  @Test
  @DisplayName("025：import 走 saveFiles 校验链落盘注册；name 冲突拒绝")
  void importAgent_writesAndRegisters_conflictRejected() throws Exception {
    String importMd =
        "---\nname: software-architect\npersona:\n  name: 架构师\n  role: 软件架构专家\nprovider:\n  name: deepseek\n  model: m\n---\n正文";
    when(profileRegistry.exists("software-architect")).thenReturn(false);
    when(profileRegistry.get("software-architect")).thenReturn(Optional.empty());
    Path dir = Path.of("agents", "software-architect");
    when(agentStore.writeAll(eq("software-architect"), any())).thenReturn(dir);
    Profile p = profile("software-architect");
    doReturn(p).when(agentLoader).parse(any(), eq("software-architect"));
    doReturn(p).when(agentLoader).deriveProfile(dir);

    assertSame(p, service.importAgent("software-architect", importMd));
    verify(agentStore).writeAll(eq("software-architect"), any());
    verify(profileRegistry).register(p);

    when(profileRegistry.exists("architect")).thenReturn(true);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> service.importAgent("architect", importMd));
    assertTrue(ex.getMessage().contains("请先删除同名 Agent 再导入"), "冲突提示要指引先删再导，不静默覆盖");
    verify(agentStore, never()).writeAll(eq("architect"), any());
  }

  @Test
  @DisplayName("025：updatePersona 只改 persona 段，正文与其余配置原样保留")
  void updatePersona_onlyChangesPersonaPreservesBody() throws Exception {
    when(agentStore.read("demo")).thenReturn(MD);
    Path dir = Path.of("agents", "demo");
    when(agentStore.writeAll(eq("demo"), any())).thenReturn(dir);
    Profile updated = profile("demo");
    doReturn(updated).when(agentLoader).parse(any(), eq("demo"));
    doReturn(updated).when(agentLoader).deriveProfile(dir);

    service.updatePersona("demo", new Profile.Persona("老张", "运维专家", "严谨", "简洁", null, null, null));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Map<String, String>> filesCaptor =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(agentStore).writeAll(eq("demo"), filesCaptor.capture());
    String written = filesCaptor.getValue().get("AGENT.md");
    assertTrue(written.contains("name: 老张"), "persona.name 被写入");
    assertTrue(written.contains("role: 运维专家"), "persona.role 被写入");
    assertTrue(written.contains("traits: 严谨"), "persona.traits 被写入");
    assertTrue(written.contains("正文"), "正文保留");
    assertTrue(written.contains("provider:"), "其余 frontmatter 保留");
    assertTrue(!written.contains("sample_style:"), "null 字段不写");
  }

  @Test
  @DisplayName("025：updatePersona 缺 name/role 拒绝，不读文件")
  void updatePersona_missingNameRole_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.updatePersona(
                "demo", new Profile.Persona("", "运维专家", null, null, null, null, null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.updatePersona(
                "demo", new Profile.Persona("老张", "", null, null, null, null, null)));
    verify(agentStore, never()).read(any());
  }

  /** 用真实 AgentLoader 构建的 service（validateAgent 走真实 parse，不走 mock）。 */
  private AgentLifecycleService realLoaderService() {
    return new AgentLifecycleService(
        new AgentLoader(Path.of("."), java.util.Set.of("deepseek")),
        mock(ProfileRegistry.class),
        mock(AgentScheduler.class),
        mock(AgentStore.class),
        mock(io.oryxos.core.provider.ProviderService.class),
        "deepseek",
        "deepseek",
        "deepseek-chat",
        java.util.Map.of(),
        mock(io.oryxos.core.notify.NotifyChannelRegistry.class));
  }

  @Test
  @DisplayName("025：validateAgent dry-run——合法返回 ok 带派生 Profile；非法返回 fail 不抛")
  void validateAgent_okAndFail() {
    AgentLifecycleService real = realLoaderService();

    AgentValidation ok = real.validateAgent("demo", MD);
    assertTrue(ok.valid());
    assertNotNull(ok.profile());
    assertEquals("demo", ok.profile().name());
    assertEquals("deepseek", ok.profile().provider().name()); // 解析出的 provider 供预览展示

    AgentValidation noName = real.validateAgent("demo", "---\ndescription: x\n---\n正文");
    assertFalse(noName.valid());
    assertNull(noName.profile());
    assertTrue(noName.error() != null && !noName.error().isBlank());

    AgentValidation noProvider = real.validateAgent("demo", "---\nname: demo\n---\n正文");
    assertFalse(noProvider.valid());
    assertNull(noProvider.profile());

    AgentValidation badYaml = real.validateAgent("demo", "---\nname: [unclosed\n---\n正文");
    assertFalse(badYaml.valid());
    assertNull(badYaml.profile());
    assertTrue(badYaml.error() != null && !badYaml.error().isBlank());
  }
}
