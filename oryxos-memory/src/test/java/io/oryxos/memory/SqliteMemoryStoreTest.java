package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SQLite 档专属（015 T012）：agent 作用域随 ToolExecutionContext 走、无上下文回退全局（FR-014）。 */
class SqliteMemoryStoreTest {

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  @Test
  @DisplayName("有 Agent 上下文_读写全部带该 Agent 名（US1 场景 6）")
  void agentContextScopesAllReadsAndWrites() {
    MemoryEntryRepository repo = mock(MemoryEntryRepository.class);
    when(repo.findByAgentNameAndScopeOrderByIdAsc(anyString(), anyString())).thenReturn(List.of());
    when(repo.findByAgentNameAndScopeOrderByIdDesc(anyString(), anyString(), any()))
        .thenReturn(List.of());
    when(repo.searchArchival(anyString(), anyString())).thenReturn(List.of());
    SqliteMemoryStore store = new SqliteMemoryStore(repo);
    ToolExecutionContext.setAgentName("ops-agent");

    store.append("一条归档", MemoryScope.ARCHIVAL);
    store.load();
    store.recallByKeyword("关键词");
    store.archivalEntries();

    ArgumentCaptor<MemoryEntry> saved = ArgumentCaptor.forClass(MemoryEntry.class);
    verify(repo).save(saved.capture());
    assertEquals("ops-agent", saved.getValue().getAgentName(), "写入落当前 Agent 作用域");
    verify(repo).findByAgentNameAndScopeOrderByIdAsc("ops-agent", "CORE");
    verify(repo).searchArchival("ops-agent", "%关键词%");
    verify(repo).findByAgentNameAndScopeOrderByIdAsc("ops-agent", "ARCHIVAL"); // archivalEntries
    verify(repo)
        .findByAgentNameAndScopeOrderByIdDesc(
            org.mockito.ArgumentMatchers.eq("ops-agent"),
            org.mockito.ArgumentMatchers.eq("ARCHIVAL"),
            any()); // load 归档窗口
  }

  @Test
  @DisplayName("无 Agent 上下文_回退 __global__ 作用域")
  void missingContextFallsBackToGlobalScope() {
    MemoryEntryRepository repo = mock(MemoryEntryRepository.class);
    when(repo.searchArchival(anyString(), anyString())).thenReturn(List.of());
    SqliteMemoryStore store = new SqliteMemoryStore(repo);

    store.recallByKeyword("Needle");

    // 关键词压小写（FR-002 大小写统一，JPQL 侧 LOWER(content)）
    verify(repo).searchArchival(MemoryEntry.GLOBAL_AGENT, "%needle%");
  }

  @Test
  @DisplayName("capabilities 为 HYBRID_BUILTIN")
  void capabilitiesIsHybridBuiltin() {
    assertEquals(
        MemoryRecallCapability.HYBRID_BUILTIN,
        new SqliteMemoryStore(mock(MemoryEntryRepository.class)).capabilities());
  }
}
