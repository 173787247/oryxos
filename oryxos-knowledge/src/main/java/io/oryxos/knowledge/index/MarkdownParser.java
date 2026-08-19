package io.oryxos.knowledge.index;

import io.oryxos.core.knowledge.KnowledgeImportException;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** markdown 解析：整篇一个单元，切分交给 Chunker（标题边界在切分层生效）。 */
public final class MarkdownParser implements DocumentParser {

  @Override
  public boolean supports(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    return lower.endsWith(".md") || lower.endsWith(".markdown");
  }

  @Override
  public List<ParsedUnit> parse(Path file) {
    try {
      String text = Files.readString(file);
      return text.isBlank() ? List.of() : List.of(new ParsedUnit(text, null));
    } catch (MalformedInputException e) {
      throw new KnowledgeImportException("文件不是合法 UTF-8 文本: " + file.getFileName(), e);
    } catch (IOException e) {
      throw new KnowledgeImportException("读取文档失败: " + file.getFileName(), e);
    }
  }
}
