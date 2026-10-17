package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 沙箱白名单幂等契约（两库同套）：{@code add} 的判据是**跨实例**幂等，不是进程内幂等。
 *
 * <p>真实形态见 {@code ConcurrentMigrationIT}：两个副本同时启动，各自把配置里的白名单播种一遍；两边都通过存在性 检查、然后都插入同一条 —— 唯一键 {@code
 * (category, entry_value)} 必须由存储层接住（按契约返回 false）， 而不是让第二个副本的 {@code sandbox} bean 创建失败、整个实例起不来。
 *
 * <p>NOT_SUPPORTED 压制测试事务：这些用例要真的提交，唯一键才可能被撞上。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class SandboxWhitelistIdempotencyContractTest {

  private static final Category CATEGORY = Category.FILE;

  private static final String ENTRY = "/tmp/oryxos-whitelist-race";

  @Autowired private SandboxWhitelistRepository repository;

  private JpaSandboxWhitelistStore store;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    store = new JpaSandboxWhitelistStore(repository);
  }

  @Test
  @DisplayName("add_第二次返回 false_库里仍只有一行")
  void secondAddIsIdempotent() {
    assertThat(store.add(CATEGORY, ENTRY)).isTrue();

    assertThat(store.add(CATEGORY, ENTRY)).isFalse();
    assertThat(repository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("存在性检查与插入之间被抢先_冲突按已存在处理且不抛")
  void aRowInsertedBetweenCheckAndInsertIsTreatedAsAlreadyPresent() {
    // 另一个副本先提交了同一条
    assertThat(store.add(CATEGORY, ENTRY)).isTrue();

    // 本副本在对方提交前读到了「不存在」：让存在性检查谎报 false，add 就会真的走插入路径撞唯一键
    SandboxWhitelistRepository raced = mock(SandboxWhitelistRepository.class);
    when(raced.existsByCategoryAndEntryValue(anyString(), anyString())).thenReturn(false);
    when(raced.saveAndFlush(any()))
        .thenAnswer(call -> repository.saveAndFlush(call.getArgument(0)));

    assertThat(new JpaSandboxWhitelistStore(raced).add(CATEGORY, ENTRY)).isFalse();
    assertThat(repository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("非约束失败原样上抛_不被当成已存在")
  void nonConstraintFailuresAreNotSwallowed() {
    SandboxWhitelistRepository broken = mock(SandboxWhitelistRepository.class);
    when(broken.existsByCategoryAndEntryValue(anyString(), anyString())).thenReturn(false);
    IllegalStateException diskOnFire = new IllegalStateException("disk on fire");
    when(broken.save(any())).thenThrow(diskOnFire);
    when(broken.saveAndFlush(any())).thenThrow(diskOnFire);

    assertThatThrownBy(() -> new JpaSandboxWhitelistStore(broken).add(CATEGORY, ENTRY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disk on fire");
  }
}
