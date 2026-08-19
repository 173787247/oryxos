package io.oryxos.knowledge.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

  private final Chunker chunker = new Chunker();

  @Test
  void splitsAtHeadingBoundaries() {
    List<String> chunks = chunker.split("# 磁盘告警\n\n处置步骤一。\n\n# 网络故障\n\n处置步骤二。");

    assertEquals(2, chunks.size());
    assertTrue(chunks.get(0).contains("磁盘告警"));
    assertTrue(chunks.get(1).contains("网络故障"));
  }

  @Test
  void hardSplitsOverlongParagraphAndKeepsOrder() {
    String longParagraph = "长内容。".repeat(1000); // 4000 字符 > MAX_CHARS
    List<String> chunks = chunker.split(longParagraph);

    assertTrue(chunks.size() >= 2, "超长段落必须被硬切");
    for (String chunk : chunks) {
      assertTrue(chunk.length() <= Chunker.MAX_CHARS);
    }
    assertEquals(longParagraph, String.join("", chunks), "切分可回溯：拼回原文不丢内容");
  }

  @Test
  void dropsBlankAndTrimsChunks() {
    List<String> chunks = chunker.split("\n\n  \n\n有效内容\n\n   \n");
    assertEquals(List.of("有效内容"), chunks);
  }
}
