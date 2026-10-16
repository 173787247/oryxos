package io.oryxos.knowledge.store;

/** 内存档实跑（knowledge.store=memory）：与 SQLite 档同套用例。 */
class InMemoryChunkStoreContractTest extends ChunkStoreContractTest {

  @Override
  protected ChunkStore newStore() {
    return new InMemoryChunkStore();
  }

  /** 分叉（需维护者定夺）：未知 id 视为 upsert，按给定 id 落库。 */
  @Override
  protected boolean rejectsUnknownDocumentId() {
    return false;
  }
}
