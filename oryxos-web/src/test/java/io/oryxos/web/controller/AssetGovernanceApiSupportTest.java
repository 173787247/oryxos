package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.storage.AssetGovernanceEventRecorder;
import io.oryxos.storage.AssetGovernanceRevision;
import io.oryxos.storage.AssetGovernanceRevisionRecorder;
import io.oryxos.web.config.WebAssetGovernanceProperties;
import io.oryxos.web.controller.dto.AssetGovernanceView;
import io.oryxos.web.security.AssetBindGuard;
import io.oryxos.web.security.PrincipalHolder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AssetGovernanceApiSupportTest {

  @Test
  @DisplayName("listRevisions_flag关_返回空")
  void listRevisions_flagOff_empty() {
    WebAssetGovernanceProperties props = new WebAssetGovernanceProperties();
    AssetGovernanceRevisionRecorder revisions = mock(AssetGovernanceRevisionRecorder.class);
    assertThat(
            AssetGovernanceApiSupport.listRevisions(props, revisions, ResourceRef.agent("a"))
                .getData())
        .isEmpty();
    verify(revisions, never()).list(anyString(), anyString());
  }

  @Test
  @DisplayName("put_flag开_写全文快照")
  void put_flagOn_recordsRevision() {
    WebAssetGovernanceProperties props = new WebAssetGovernanceProperties();
    props.setVersionHistoryEnabled(true);
    AssetBindGuard guard = mock(AssetBindGuard.class);
    AssetGovernanceStore store = mock(AssetGovernanceStore.class);
    AssetGovernanceEventRecorder events = mock(AssetGovernanceEventRecorder.class);
    AssetGovernanceRevisionRecorder revisions = mock(AssetGovernanceRevisionRecorder.class);
    AssetGovernance saved =
        new AssetGovernance(
            "alice",
            "1.0",
            AssetGovernance.Visibility.PUBLIC,
            null,
            AssetGovernance.Health.ACTIVE,
            null);
    when(store.load(eq(ResourceRef.TYPE_AGENT), eq("bot"))).thenReturn(saved);

    MockHttpServletRequest request = new MockHttpServletRequest();
    PrincipalHolder.set(request, Principal.user("alice", "alice", Set.of(Role.ADMIN)));

    AssetGovernanceApiSupport.put(
        request,
        guard,
        store,
        events,
        props,
        revisions,
        Action.MANAGE_AGENTS,
        ResourceRef.agent("bot"),
        AssetGovernanceView.from(saved),
        (id, g) -> {});

    verify(revisions)
        .record(eq("USER:alice"), eq(ResourceRef.TYPE_AGENT), eq("bot"), eq("1.0"), anyString());
  }

  @Test
  @DisplayName("listRevisions_flag开_映射视图")
  void listRevisions_flagOn_maps() {
    WebAssetGovernanceProperties props = new WebAssetGovernanceProperties();
    props.setVersionHistoryEnabled(true);
    AssetGovernanceRevisionRecorder revisions = mock(AssetGovernanceRevisionRecorder.class);
    AssetGovernanceRevision row = new AssetGovernanceRevision();
    row.setResourceType(ResourceRef.TYPE_AGENT);
    row.setResourceId("bot");
    row.setVersionLabel("1.0");
    row.setSnapshotText("owner: alice\n");
    row.setActor("alice");
    when(revisions.list(ResourceRef.TYPE_AGENT, "bot")).thenReturn(List.of(row));

    assertThat(
            AssetGovernanceApiSupport.listRevisions(props, revisions, ResourceRef.agent("bot"))
                .getData())
        .hasSize(1)
        .first()
        .extracting("versionLabel", "snapshotText", "actor")
        .containsExactly("1.0", "owner: alice\n", "alice");
  }
}
