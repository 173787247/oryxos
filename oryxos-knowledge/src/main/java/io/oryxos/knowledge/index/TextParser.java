package io.oryxos.knowledge.index;

import io.oryxos.core.knowledge.KnowledgeImportException;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 纯文本解析：整篇一个单元。非 UTF-8（疑似二进制）明确拒绝，不静默产出乱码片段。 */
public final class TextParser implements DocumentParser {

  @Override
  public boolean supports(String fileName) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(".txt");
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
