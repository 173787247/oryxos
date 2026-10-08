package io.oryxos.knowledge.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  /**
   * 同一篇 markdown 的行尾不该改变切分结果：Windows 上编辑、或 {@code core.autocrlf} 检出的文件都是 CRLF， 而段落分隔（连续空行）在 CRLF 下是
   * {@code \r\n\r\n}——{@code \n{2,}} 匹配不到它，整篇就并成一个片段。
   */
  @Test
  void crlfDocumentSplitsAtTheSameBoundariesAsLf() {
    String lf = "# 磁盘告警\n\n处置步骤一。\n\n# 网络故障\n\n处置步骤二。";
    String crlf = lf.replace("\n", "\r\n");

    List<String> fromLf = chunker.split(lf);
    List<String> fromCrlf = chunker.split(crlf);

    assertEquals(2, fromLf.size(), "前提：LF 版本按标题切成两片");
    assertEquals(fromLf, fromCrlf, "行尾不得改变切分结果");
    for (String chunk : fromCrlf) {
      assertFalse(chunk.contains("\r"), "片段里不得夹带回车符");
    }
  }

  @Test
  void loneCarriageReturnLineEndingsSplitTheSameWay() {
    String lf = "# 磁盘告警\n\n处置步骤一。\n\n# 网络故障\n\n处置步骤二。";

    assertEquals(chunker.split(lf), chunker.split(lf.replace("\n", "\r")));
  }
}
