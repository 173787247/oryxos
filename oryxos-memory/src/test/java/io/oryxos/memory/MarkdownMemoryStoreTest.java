package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.MemoryScope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Markdown 档专属：字符串截断边界、区块解析、空文件。 */
class MarkdownMemoryStoreTest {

  @TempDir Path root;

  @Test
  @DisplayName("空记忆文件_load 返回空区块不报错")
  void emptyFileLoadsEmptySections() {
    String loaded = new MarkdownMemoryStore(root).load();

    assertTrue(loaded.contains("## 核心记忆"));
    assertTrue(loaded.contains("## 归档记忆"));
  }

  @Test
  @DisplayName("归档恰好等于上限不截断_超过才裁最早")
  void archivalTruncatesOnlyBeyondLimit() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(root);
    memory.append("核心恒在", MemoryScope.CORE);
    // 每条约 20+ 字符，200 条远超 4000 字符上限
    for (int i = 0; i < 200; i++) {
      memory.append("archival-entry-number-" + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    assertTrue(loaded.contains("核心恒在"), "核心区不受字符串截断影响");
    assertFalse(loaded.contains("archival-entry-number-0"), "最早的归档被裁");
    assertTrue(loaded.contains("archival-entry-number-199"), "最近的保留");
  }

  @Test
  @DisplayName("核心与归档写入互不串区")
  void coreAndArchivalStaySeparate() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(root);
    memory.append("我是核心", MemoryScope.CORE);
    memory.append("我是归档", MemoryScope.ARCHIVAL);

    // 归档检索只命中归档条目
    assertEquals(1, memory.recallByKeyword("归档").size());
    assertTrue(memory.recallByKeyword("核心").isEmpty());
  }

  @Test
  @DisplayName("条目含区块头字面量_不截断核心也不串区")
  void contentWithSectionHeaders_doesNotCorruptPartitions() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(root);
    memory.append("用户偏好 Java", MemoryScope.CORE);
    memory.append("笔记里提到 ## 归档记忆 作为标题", MemoryScope.CORE);
    memory.append("另一条含 ## 核心记忆 的核心笔记", MemoryScope.CORE);
    memory.append("归档也写 ## 核心记忆 字样", MemoryScope.ARCHIVAL);

    String loaded = memory.load();
    int archiveHeaderAt = loaded.indexOf("## 归档记忆");
    assertTrue(archiveHeaderAt > 0);
    String corePart = loaded.substring(0, archiveHeaderAt);
    String archivePart = loaded.substring(archiveHeaderAt);

    assertTrue(corePart.contains("用户偏好 Java"), "先写入的核心不得被截断");
    assertTrue(corePart.contains("「归档记忆」"), "核心条目中的区块头应被中和");
    assertTrue(corePart.contains("「核心记忆」"), "核心条目中的区块头应被中和");
    assertFalse(corePart.contains("## 归档记忆"), "内容不得再引入结构头");
    assertTrue(archivePart.contains("「核心记忆」"));
    assertEquals(1, memory.recallByKeyword("字样").size());
    assertTrue(memory.recallByKeyword("用户偏好").isEmpty(), "核心不进 recall");
  }

  @Test
  @DisplayName("sanitize_压换行并替换区块头")
  void sanitizeNeutralizesHeadersAndNewlines() {
    assertEquals(
        "行1 行2 「核心记忆」 与 「归档记忆」",
        MarkdownMemoryStore.sanitizeEntryContent("行1\n行2 ## 核心记忆 与 ## 归档记忆"));
  }

  @Test
  @DisplayName("并发追加_条目一条不丢")
  void concurrentAppendsLoseNoEntry() throws Exception {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(root);
    int writers = 32;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < writers; i++) {
        int id = i;
        results.add(
            pool.submit(
                () -> {
                  start.await(); // 卡闸齐发，最大化读-改-写窗口重叠
                  memory.append("concurrent-entry-" + id, MemoryScope.CORE);
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> result : results) {
        result.get();
      }
    }

    String loaded = memory.load();
    for (int i = 0; i < writers; i++) {
      assertTrue(loaded.contains("concurrent-entry-" + i), "不加锁时并发追加会互相覆盖丢条目: " + i);
    }
  }
}
