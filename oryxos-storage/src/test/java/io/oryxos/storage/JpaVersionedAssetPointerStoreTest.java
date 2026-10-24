package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.core.workspace.versioned.VersionedAssetKind;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** JpaVersionedAssetPointerStore 单元：版本推进、查询委托与激活指针切换。 */
class JpaVersionedAssetPointerStoreTest {

  private static final VersionedAssetKind KIND = VersionedAssetKind.AGENTS;
  private static final String ASSET = "agent-a";

  private WorkspaceAssetVersionRepository versions;
  private WorkspaceAssetActiveRepository active;
  private JpaVersionedAssetPointerStore store;

  @BeforeEach
  void setUp() {
    versions = mock(WorkspaceAssetVersionRepository.class);
    active = mock(WorkspaceAssetActiveRepository.class);
    store = new JpaVersionedAssetPointerStore(versions, active);
  }

  @Test
  @DisplayName("nextVersion 从当前最大值推进")
  void nextVersionAdvancesFromMaximum() {
    when(versions.maxVersion("agents", ASSET)).thenReturn(0L);
    assertEquals(1, store.nextVersion(KIND, ASSET));

    when(versions.maxVersion("agents", ASSET)).thenReturn(3L);
    assertEquals(4, store.nextVersion(KIND, ASSET));
  }

  @Test
  @DisplayName("hasVersion 按 kind 目录名委托查询")
  void hasVersionDelegates() {
    when(versions.existsByKindAndAssetIdAndVersion("agents", ASSET, 2)).thenReturn(true);

    assertTrue(store.hasVersion(KIND, ASSET, 2));
    assertFalse(store.hasVersion(KIND, ASSET, 3));
  }

  @Test
  @DisplayName("listVersions 按仓库顺序映射版本号")
  void listVersionsMapsRows() {
    when(versions.findByKindAndAssetIdOrderByVersionDesc("agents", ASSET))
        .thenReturn(List.of(versionRow(3), versionRow(2), versionRow(1)));

    assertEquals(List.of(3L, 2L, 1L), store.listVersions(KIND, ASSET));
  }

  @Test
  @DisplayName("activeVersion 有指针返回值，无指针返回空")
  void activeVersionReadsPointer() {
    when(active.findById(new WorkspaceAssetActiveEntity.Pk("agents", ASSET)))
        .thenReturn(Optional.of(pointerRow(2, 1L)));
    assertEquals(OptionalLong.of(2), store.activeVersion(KIND, ASSET));

    when(active.findById(new WorkspaceAssetActiveEntity.Pk("agents", ASSET)))
        .thenReturn(Optional.empty());
    assertEquals(OptionalLong.empty(), store.activeVersion(KIND, ASSET));
  }

  @Test
  @DisplayName("previousVersion 忽略 null 指针")
  void previousVersionIgnoresNull() {
    when(active.findById(any())).thenReturn(Optional.of(pointerRow(2, 1L)));
    assertEquals(OptionalLong.of(1), store.previousVersion(KIND, ASSET));

    when(active.findById(any())).thenReturn(Optional.of(pointerRow(2, null)));
    assertEquals(OptionalLong.empty(), store.previousVersion(KIND, ASSET));
  }

  @Test
  @DisplayName("激活不存在的版本返回 false 且不写指针")
  void activateRejectsUnknownVersion() {
    when(versions.existsByKindAndAssetIdAndVersion("agents", ASSET, 9)).thenReturn(false);

    assertFalse(store.activate(KIND, ASSET, 9, "actor"));
    verifyNoInteractions(active);
  }

  @Test
  @DisplayName("首次激活写指针，previous 为空，actor 为空时记 anonymous")
  void activateCreatesPointer() {
    when(versions.existsByKindAndAssetIdAndVersion("agents", ASSET, 1)).thenReturn(true);
    when(active.findById(any())).thenReturn(Optional.empty());

    assertTrue(store.activate(KIND, ASSET, 1, "  "));

    WorkspaceAssetActiveEntity saved = capturedPointer();
    assertEquals(1, saved.getActiveVersion());
    assertEquals(null, saved.getPreviousVersion());
    assertEquals("anonymous", saved.getUpdatedBy());
  }

  @Test
  @DisplayName("切换激活版本时把旧版本记为 previous")
  void activateSwitchesPointer() {
    when(versions.existsByKindAndAssetIdAndVersion("agents", ASSET, 3)).thenReturn(true);
    when(active.findById(any())).thenReturn(Optional.of(pointerRow(2, 1L)));

    assertTrue(store.activate(KIND, ASSET, 3, "actor"));

    WorkspaceAssetActiveEntity saved = capturedPointer();
    assertEquals(3, saved.getActiveVersion());
    assertEquals(2L, saved.getPreviousVersion());
    assertEquals("actor", saved.getUpdatedBy());
  }

  @Test
  @DisplayName("重复激活当前版本不重写指针")
  void activateCurrentVersionIsNoOp() {
    when(versions.existsByKindAndAssetIdAndVersion("agents", ASSET, 2)).thenReturn(true);
    when(active.findById(any())).thenReturn(Optional.of(pointerRow(2, 1L)));

    assertTrue(store.activate(KIND, ASSET, 2, "actor"));

    verify(active, never()).save(any());
  }

  private WorkspaceAssetActiveEntity capturedPointer() {
    ArgumentCaptor<WorkspaceAssetActiveEntity> captor =
        ArgumentCaptor.forClass(WorkspaceAssetActiveEntity.class);
    verify(active).save(captor.capture());
    return captor.getValue();
  }

  private static WorkspaceAssetVersionEntity versionRow(long version) {
    WorkspaceAssetVersionEntity row = new WorkspaceAssetVersionEntity();
    row.setKind("agents");
    row.setAssetId(ASSET);
    row.setVersion(version);
    return row;
  }

  private static WorkspaceAssetActiveEntity pointerRow(long activeVersion, Long previousVersion) {
    WorkspaceAssetActiveEntity row = new WorkspaceAssetActiveEntity();
    row.setKind("agents");
    row.setAssetId(ASSET);
    row.setActiveVersion(activeVersion);
    row.setPreviousVersion(previousVersion);
    return row;
  }
}
