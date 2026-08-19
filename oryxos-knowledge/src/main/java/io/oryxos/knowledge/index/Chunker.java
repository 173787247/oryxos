package io.oryxos.knowledge.index;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 切分器：标题边界优先、辅以长度上限（spec Assumptions）。契约是「不跨文档、带位置、可回溯」—— 具体参数为实现细节。 */
public final class Chunker {

  /** 单片段字符上限：兼顾 embedding 输入限制与检索粒度。 */
  static final int MAX_CHARS = 1600;

  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s.*");

  /** 段落分隔：连续空行。 */
  private static final String PARAGRAPH_SEPARATOR = "\\n{2,}";

  /** 把一个解析单元的文本切成有序片段；空白片段丢弃。 */
  public List<String> split(String text) {
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : paragraphsOf(text)) {
      boolean isHeading = HEADING.matcher(paragraph.strip()).matches();
      boolean overflow = current.length() + paragraph.length() + 2 > MAX_CHARS;
      boolean shouldFlush = current.length() > 0 && (isHeading || overflow);
      if (shouldFlush) {
        addChunk(chunks, current.toString());
        current.setLength(0);
      }
      if (paragraph.length() > MAX_CHARS) {
        for (int start = 0; start < paragraph.length(); start += MAX_CHARS) {
          addChunk(
              chunks, paragraph.substring(start, Math.min(start + MAX_CHARS, paragraph.length())));
        }
        continue;
      }
      if (current.length() > 0) {
        current.append("\n\n");
      }
      current.append(paragraph);
    }
    if (current.length() > 0) {
      addChunk(chunks, current.toString());
    }
    return chunks;
  }

  private static List<String> paragraphsOf(String text) {
    List<String> paragraphs = new ArrayList<>();
    for (String block : text.split(PARAGRAPH_SEPARATOR)) {
      if (!block.isBlank()) {
        paragraphs.add(block.strip());
      }
    }
    return paragraphs;
  }

  private static void addChunk(List<String> chunks, String chunk) {
    String trimmed = chunk.strip();
    if (!trimmed.isEmpty()) {
      chunks.add(trimmed);
    }
  }
}
