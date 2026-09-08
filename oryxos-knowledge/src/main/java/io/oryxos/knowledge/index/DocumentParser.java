package io.oryxos.knowledge.index;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析 SPI——本地后端内部的可扩展分层（FR-003：后续增加格式只加实现，不改契约）。 解析失败（含扫描件 PDF）抛可读 {@link
 * io.oryxos.core.knowledge.KnowledgeImportException}。
 */
public interface DocumentParser {

  /** 按文件名（扩展名）判断是否受理。 */
  boolean supports(String fileName);

  /** 解析为单元列表；空内容返回空列表由上层按「空文档」处理。 */
  List<ParsedUnit> parse(Path file);
}
